package com.txwatch.domain;

import java.time.Instant;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** One append-only entry in the tamper-evident audit chain. */
@Entity
@Immutable
public class AuditEvent {

    @Id
    private Long seq;
    private String actor;
    private String action;
    private String entityType;
    private String entityId;
    private String detail;
    private Instant occurredAt;
    private String prevHash;
    private String hash;

    protected AuditEvent() {
    }

    public AuditEvent(Long seq, String actor, String action, String entityType, String entityId, String detail,
            Instant occurredAt, String prevHash, String hash) {
        this.seq = seq;
        this.actor = actor;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.detail = detail;
        this.occurredAt = occurredAt;
        this.prevHash = prevHash;
        this.hash = hash;
    }

    public Long getSeq() {
        return seq;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getHash() {
        return hash;
    }
}
