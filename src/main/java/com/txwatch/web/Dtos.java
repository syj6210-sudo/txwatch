package com.txwatch.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.txwatch.domain.Alert;
import com.txwatch.domain.CtrReport;
import com.txwatch.domain.Customer;
import com.txwatch.domain.Enums.AlertAction;
import com.txwatch.domain.Enums.AlertStatus;
import com.txwatch.domain.Enums.Channel;
import com.txwatch.domain.Enums.CtrStatus;
import com.txwatch.domain.Enums.Direction;
import com.txwatch.domain.Enums.RiskLevel;
import com.txwatch.domain.Enums.Severity;
import com.txwatch.service.IngestionService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public final class Dtos {

    private Dtos() {
    }

    public record CreateCustomer(@NotBlank String externalRef, @NotBlank String name,
            @NotNull @Pattern(regexp = "[A-Z]{2}") String countryCode, @NotNull RiskLevel occupationRisk, boolean pep,
            String nationalIdToken, String phone) {
    }

    public record CustomerView(Long id, String externalRef, String name, String countryCode, RiskLevel occupationRisk,
            boolean pep) {
        static CustomerView of(Customer c) {
            return new CustomerView(c.getId(), c.getExternalRef(), c.getName(), c.getCountryCode(),
                    c.getOccupationRisk(), c.isPep());
        }
    }

    public record IngestTransaction(@NotBlank String externalId, @NotBlank String customerRef,
            @NotNull @Positive BigDecimal amount, @NotNull Channel channel, @NotNull Direction direction,
            @Pattern(regexp = "[A-Z]{2}") String counterpartyCountry, @NotNull Instant occurredAt) {
        IngestionService.Command toCommand() {
            return new IngestionService.Command(externalId, customerRef, amount, channel, direction,
                    counterpartyCountry, occurredAt);
        }
    }

    public record IngestResponse(Long transactionId, boolean replayed, List<AlertView> alerts) {
        static IngestResponse of(IngestionService.Result result) {
            return new IngestResponse(result.transaction().getId(), result.replayed(),
                    result.alerts().stream().map(AlertView::of).toList());
        }
    }

    public record AlertView(Long id, Long customerId, String ruleCode, int score, Severity severity,
            AlertStatus status, String reason, String assignee, String closingNote, Set<Long> transactionIds,
            Instant createdAt) {
        static AlertView of(Alert a) {
            return new AlertView(a.getId(), a.getCustomerId(), a.getRuleCode(), a.getScore(), a.getSeverity(),
                    a.getStatus(), a.getReason(), a.getAssignee(), a.getClosingNote(), Set.copyOf(a.getTxnIds()),
                    a.getCreatedAt());
        }
    }

    public record TransitionRequest(@NotNull AlertAction action, String note) {
    }

    public record PageView<T>(List<T> items, int page, int size, long total) {
    }

    /** Identifier values stay out of this view. They appear only in the exported report. */
    public record CtrView(Long id, Long customerId, LocalDate businessDate, Direction direction,
            BigDecimal totalAmount, int transactionCount, CtrStatus status) {
        static CtrView of(CtrReport r) {
            return new CtrView(r.getId(), r.getCustomerId(), r.getBusinessDate(), r.getDirection(),
                    r.getTotalAmount(), r.getTxnCount(), r.getStatus());
        }
    }

    public record SupplementRequest(String nationalIdToken, String phone) {
    }
}
