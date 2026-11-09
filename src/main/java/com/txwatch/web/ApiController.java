package com.txwatch.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.txwatch.domain.Alert;
import com.txwatch.domain.Customer;
import com.txwatch.domain.Enums.AlertStatus;
import com.txwatch.repository.Repositories.CustomerRepository;
import com.txwatch.service.AlertService;
import com.txwatch.service.AuditService;
import com.txwatch.service.CsvImportService;
import com.txwatch.service.CtrService;
import com.txwatch.service.IngestionService;
import com.txwatch.web.Dtos.AlertView;
import com.txwatch.web.Dtos.CreateCustomer;
import com.txwatch.web.Dtos.CtrView;
import com.txwatch.web.Dtos.CustomerView;
import com.txwatch.web.Dtos.IngestResponse;
import com.txwatch.web.Dtos.IngestTransaction;
import com.txwatch.web.Dtos.PageView;
import com.txwatch.web.Dtos.SupplementRequest;
import com.txwatch.web.Dtos.TransitionRequest;

import jakarta.validation.Valid;

/** REST API. The acting user comes from the {@code X-Actor} header, which a gateway is expected to set. */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final String ACTOR = "X-Actor";

    private final CustomerRepository customers;
    private final IngestionService ingestion;
    private final CsvImportService csvImport;
    private final AlertService alerts;
    private final CtrService ctr;
    private final AuditService audit;
    private final Clock clock;

    public ApiController(CustomerRepository customers, IngestionService ingestion, CsvImportService csvImport,
            AlertService alerts, CtrService ctr, AuditService audit, Clock clock) {
        this.customers = customers;
        this.ingestion = ingestion;
        this.csvImport = csvImport;
        this.alerts = alerts;
        this.ctr = ctr;
        this.audit = audit;
        this.clock = clock;
    }

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerView createCustomer(@Valid @RequestBody CreateCustomer body) {
        Customer saved = customers.save(new Customer(body.externalRef(), body.name(), body.countryCode(),
                body.occupationRisk(), body.pep(), body.nationalIdToken(), body.phone(), clock.instant()));
        return CustomerView.of(saved);
    }

    @PostMapping("/transactions")
    public IngestResponse ingest(@RequestHeader(ACTOR) String actor, @Valid @RequestBody IngestTransaction body) {
        return IngestResponse.of(ingestion.ingest(body.toCommand(), actor));
    }

    @PostMapping(path = "/transactions/import", consumes = {"text/csv", MediaType.TEXT_PLAIN_VALUE})
    public CsvImportService.Summary importCsv(@RequestHeader(ACTOR) String actor, @RequestBody String content) {
        return csvImport.importCsv(content, actor);
    }

    @GetMapping("/alerts")
    public PageView<AlertView> listAlerts(@RequestParam(required = false) AlertStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        PageRequest request = PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "id"));
        Page<Alert> result = alerts.list(status, request);
        return new PageView<>(result.map(AlertView::of).getContent(), page, request.getPageSize(),
                result.getTotalElements());
    }

    @GetMapping("/alerts/{id}")
    public AlertView getAlert(@PathVariable Long id) {
        return AlertView.of(alerts.get(id));
    }

    @PostMapping("/alerts/{id}/transitions")
    public AlertView transition(@PathVariable Long id, @RequestHeader(ACTOR) String actor,
            @Valid @RequestBody TransitionRequest body) {
        return AlertView.of(alerts.transition(id, body.action(), actor, body.note()));
    }

    @PostMapping("/ctr-reports/generate")
    public List<CtrView> generate(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ctr.generate(date).stream().map(CtrView::of).toList();
    }

    @PatchMapping("/ctr-reports/{id}/supplement")
    public CtrView supplement(@PathVariable Long id, @RequestHeader(ACTOR) String actor,
            @RequestBody SupplementRequest body) {
        return CtrView.of(ctr.supplement(id, body.nationalIdToken(), body.phone(), actor));
    }

    @PostMapping("/ctr-reports/{id}/submit")
    public CtrView submit(@PathVariable Long id, @RequestHeader(ACTOR) String actor) {
        return CtrView.of(ctr.submit(id, actor));
    }

    @GetMapping(path = "/ctr-reports/{id}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String exportXml(@PathVariable Long id) {
        return ctr.toXml(id);
    }

    @GetMapping("/audit/verify")
    public AuditService.Verification verifyAudit() {
        return audit.verify();
    }
}
