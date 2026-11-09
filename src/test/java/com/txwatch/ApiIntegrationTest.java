package com.txwatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "txwatch.ctr-generation-cron=-")
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    private String createCustomer(String idToken, String phone, String name) throws Exception {
        String ref = "C-" + UUID.randomUUID();
        String idJson = idToken == null ? "null" : "\"" + idToken + "\"";
        String phoneJson = phone == null ? "null" : "\"" + phone + "\"";
        mvc.perform(post("/api/customers").contentType(MediaType.APPLICATION_JSON).content("""
                {"externalRef":"%s","name":"%s","countryCode":"KR","occupationRisk":"LOW","pep":false,
                 "nationalIdToken":%s,"phone":%s}""".formatted(ref, name, idJson, phoneJson)))
                .andExpect(status().isCreated());
        return ref;
    }

    private ResultActions ingest(String externalId, String customerRef, String amount, String at) throws Exception {
        return mvc.perform(post("/api/transactions").header("X-Actor", "feed").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalId":"%s","customerRef":"%s","amount":%s,"channel":"CASH","direction":"DEPOSIT",
                         "occurredAt":"%s"}""".formatted(externalId, customerRef, amount, at)));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static String id() {
        return "T-" + UUID.randomUUID();
    }

    private long createThresholdAlert() throws Exception {
        String customer = createCustomer("tok", "010", "Kim");
        JsonNode response = body(ingest(id(), customer, "12000000", "2026-04-01T01:00:00Z").andExpect(status().isOk()));
        return response.get("alerts").get(0).get("id").asLong();
    }

    private ResultActions transition(long alert, String actor, String action, String note) throws Exception {
        String noteJson = note == null ? "null" : "\"" + note + "\"";
        return mvc.perform(post("/api/alerts/{id}/transitions", alert).header("X-Actor", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"%s\",\"note\":%s}".formatted(action, noteJson)));
    }

    @Test
    void reachingTheThresholdRaisesOneAlertThatLaterTransactionsExtend() throws Exception {
        String customer = createCustomer("tok", "010", "Kim");

        ingest(id(), customer, "6000000", "2026-03-02T01:00:00Z").andExpect(jsonPath("$.alerts.length()").value(0));
        JsonNode raised = body(ingest(id(), customer, "4000000", "2026-03-02T02:00:00Z")
                .andExpect(jsonPath("$.alerts[0].ruleCode").value("CASH_THRESHOLD")));
        long alertId = raised.get("alerts").get(0).get("id").asLong();

        ingest(id(), customer, "1000000", "2026-03-02T03:00:00Z").andExpect(jsonPath("$.alerts.length()").value(0));

        mvc.perform(get("/api/alerts/{id}", alertId)).andExpect(jsonPath("$.transactionIds.length()").value(3));
    }

    @Test
    void replayingATransactionIsIdempotent() throws Exception {
        String customer = createCustomer("tok", "010", "Kim");
        String externalId = id();

        ingest(externalId, customer, "1000.00", "2026-03-02T01:00:00Z").andExpect(jsonPath("$.replayed").value(false));
        ingest(externalId, customer, "1000", "2026-03-02T01:00:00Z").andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void reusingAnIdWithDifferentDataIsRejected() throws Exception {
        String customer = createCustomer("tok", "010", "Kim");
        String externalId = id();

        ingest(externalId, customer, "1000", "2026-03-02T01:00:00Z").andExpect(status().isOk());
        ingest(externalId, customer, "2000", "2026-03-02T01:00:00Z").andExpect(status().isConflict());
    }

    @Test
    void invalidAndUnknownInputIsRejected() throws Exception {
        String customer = createCustomer("tok", "010", "Kim");

        ingest(id(), customer, "-5", "2026-03-02T01:00:00Z").andExpect(status().isBadRequest());
        ingest(id(), "missing", "5", "2026-03-02T01:00:00Z").andExpect(status().isNotFound());
    }

    @Test
    void alertWorkflowRequiresASecondReviewerToCloseEscalations() throws Exception {
        long alert = createThresholdAlert();

        transition(alert, "alice", "START_REVIEW", null).andExpect(jsonPath("$.assignee").value("alice"));
        transition(alert, "alice", "ESCALATE", "Source of funds unclear").andExpect(status().isOk());
        transition(alert, "alice", "CLOSE_REPORTED", "Filed").andExpect(status().isForbidden());
        transition(alert, "bob", "CLOSE_REPORTED", "Filed")
                .andExpect(jsonPath("$.status").value("CLOSED_REPORTED"))
                .andExpect(jsonPath("$.closingNote").value("Filed"));
    }

    @Test
    void alertWorkflowRejectsSkippedStepsAndMissingNotes() throws Exception {
        long alert = createThresholdAlert();

        transition(alert, "alice", "ESCALATE", "Too early").andExpect(status().isConflict());
        transition(alert, "alice", "START_REVIEW", null).andExpect(status().isOk());
        transition(alert, "alice", "CLOSE_FALSE_POSITIVE", " ").andExpect(status().isBadRequest());
    }

    @Test
    void ctrReportWaitsForMissingFieldsThenExportsEscapedXml() throws Exception {
        String customer = createCustomer(null, null, "A&B <Trading>");
        ingest(id(), customer, "15000000", "2026-05-11T02:00:00Z").andExpect(status().isOk());

        JsonNode reports = body(mvc.perform(post("/api/ctr-reports/generate").param("date", "2026-05-11")));
        assertThat(reports).hasSize(1);
        long report = reports.get(0).get("id").asLong();
        assertThat(reports.get(0).get("status").asText()).isEqualTo("NEEDS_INFO");

        mvc.perform(get("/api/ctr-reports/{id}/xml", report)).andExpect(status().isConflict());
        mvc.perform(post("/api/ctr-reports/{id}/submit", report).header("X-Actor", "alice"))
                .andExpect(status().isConflict());

        mvc.perform(patch("/api/ctr-reports/{id}/supplement", report).header("X-Actor", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nationalIdToken\":\"tok-1\",\"phone\":\"010-0000-0000\"}"))
                .andExpect(jsonPath("$.status").value("READY"));

        mvc.perform(get("/api/ctr-reports/{id}/xml", report))
                .andExpect(content().string(containsString("<name>A&amp;B &lt;Trading&gt;</name>")))
                .andExpect(content().string(containsString("<totalAmount>15000000.00</totalAmount>")));

        mvc.perform(post("/api/ctr-reports/{id}/submit", report).header("X-Actor", "alice"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void regeneratingKeepsSupplementedFieldsAndFreezesSubmittedReports() throws Exception {
        String customer = createCustomer(null, null, "Lee");
        ingest(id(), customer, "15000000", "2026-06-15T02:00:00Z").andExpect(status().isOk());
        long report = body(mvc.perform(post("/api/ctr-reports/generate").param("date", "2026-06-15")))
                .get(0).get("id").asLong();
        mvc.perform(patch("/api/ctr-reports/{id}/supplement", report).header("X-Actor", "alice")
                .contentType(MediaType.APPLICATION_JSON).content("{\"nationalIdToken\":\"t\",\"phone\":\"p\"}"));

        ingest(id(), customer, "1000000", "2026-06-15T03:00:00Z").andExpect(status().isOk());
        mvc.perform(post("/api/ctr-reports/generate").param("date", "2026-06-15"))
                .andExpect(jsonPath("$[0].status").value("READY"))
                .andExpect(jsonPath("$[0].totalAmount").value(16000000.00));

        mvc.perform(post("/api/ctr-reports/{id}/submit", report).header("X-Actor", "alice")).andExpect(status().isOk());
        ingest(id(), customer, "1000000", "2026-06-15T04:00:00Z").andExpect(status().isOk());
        mvc.perform(post("/api/ctr-reports/generate").param("date", "2026-06-15"))
                .andExpect(jsonPath("$[0].status").value("SUBMITTED"))
                .andExpect(jsonPath("$[0].totalAmount").value(16000000.00));
    }

    @Test
    void csvImportCommitsGoodRowsAndReportsBadOnes() throws Exception {
        String customer = createCustomer("tok", "010", "Park");
        String good = id();
        String csv = String.join("\n",
                "externalId,customerRef,amount,channel,direction,counterpartyCountry,occurredAt",
                good + "," + customer + ",1000,CASH,DEPOSIT,,2026-07-01T01:00:00Z",
                id() + "," + customer + ",abc,CASH,DEPOSIT,,2026-07-01T01:00:00Z",
                good + "," + customer + ",1000,CASH,DEPOSIT,,2026-07-01T01:00:00Z",
                "too,few,columns");

        mvc.perform(post("/api/transactions/import").header("X-Actor", "batch").contentType("text/csv").content(csv))
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.replayed").value(1))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].line").value(3))
                .andExpect(jsonPath("$.errors[1].line").value(5));
    }

    @Test
    void auditChainDetectsTamperingAndRecovers() throws Exception {
        createThresholdAlert();
        mvc.perform(get("/api/audit/verify")).andExpect(jsonPath("$.intact").value(true));

        Long seq = jdbc.queryForObject("select min(seq) from audit_event", Long.class);
        String original = jdbc.queryForObject("select detail from audit_event where seq = ?", String.class, seq);
        jdbc.update("update audit_event set detail = 'edited' where seq = ?", seq);
        try {
            mvc.perform(get("/api/audit/verify"))
                    .andExpect(jsonPath("$.intact").value(false))
                    .andExpect(jsonPath("$.firstBrokenSeq").value(seq));
        } finally {
            jdbc.update("update audit_event set detail = ? where seq = ?", original, seq);
        }
        mvc.perform(get("/api/audit/verify")).andExpect(jsonPath("$.intact").value(true));
    }
}
