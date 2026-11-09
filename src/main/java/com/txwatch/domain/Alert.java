package com.txwatch.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import com.txwatch.domain.Enums.AlertAction;
import com.txwatch.domain.Enums.AlertStatus;
import com.txwatch.domain.Enums.Severity;
import com.txwatch.service.DomainExceptions.InvalidStateException;
import com.txwatch.service.DomainExceptions.SeparationOfDutiesException;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Version;

@Entity
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    private Long version;
    private Long customerId;
    private String ruleCode;
    private String dedupeKey;
    private int score;
    @Enumerated(EnumType.STRING)
    private Severity severity;
    @Enumerated(EnumType.STRING)
    private AlertStatus status;
    private String reason;
    private String assignee;
    private String escalatedBy;
    private String closingNote;
    private Instant createdAt;
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "alert_txn", joinColumns = @JoinColumn(name = "alert_id"))
    @Column(name = "txn_id")
    private Set<Long> txnIds = new HashSet<>();

    protected Alert() {
    }

    public Alert(Long customerId, String ruleCode, String dedupeKey, int score, String reason,
            Collection<Long> txnIds, Instant now) {
        this.customerId = customerId;
        this.ruleCode = ruleCode;
        this.dedupeKey = dedupeKey;
        this.score = score;
        this.severity = Severity.fromScore(score);
        this.status = AlertStatus.OPEN;
        this.reason = reason;
        this.txnIds.addAll(txnIds);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void attachTransactions(Collection<Long> ids, Instant now) {
        if (txnIds.addAll(ids)) {
            updatedAt = now;
        }
    }

    /** Applies one workflow step, enforcing the state machine and separation of duties. */
    public void apply(AlertAction action, String actor, String note, Instant now) {
        if (!action.allowedFrom(status)) {
            throw new InvalidStateException("Cannot " + action + " an alert in status " + status);
        }
        if (action.noteRequired() && (note == null || note.isBlank())) {
            throw new IllegalArgumentException(action + " requires a note");
        }
        if (status == AlertStatus.ESCALATED && actor.equals(escalatedBy)) {
            throw new SeparationOfDutiesException("An escalated alert must be closed by a different reviewer");
        }
        switch (action) {
            case START_REVIEW -> {
                status = AlertStatus.IN_REVIEW;
                assignee = actor;
            }
            case ESCALATE -> {
                status = AlertStatus.ESCALATED;
                escalatedBy = actor;
            }
            case CLOSE_FALSE_POSITIVE -> close(AlertStatus.CLOSED_FALSE_POSITIVE, note);
            case CLOSE_REPORTED -> close(AlertStatus.CLOSED_REPORTED, note);
        }
        updatedAt = now;
    }

    private void close(AlertStatus closed, String note) {
        status = closed;
        closingNote = note;
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public int getScore() {
        return score;
    }

    public Severity getSeverity() {
        return severity;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getAssignee() {
        return assignee;
    }

    public String getClosingNote() {
        return closingNote;
    }

    public Set<Long> getTxnIds() {
        return txnIds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
