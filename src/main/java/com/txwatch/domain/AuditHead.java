package com.txwatch.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** Locked row that gives concurrent audit writers a single, gapless order. */
@Entity
public class AuditHead {

    @Id
    private Integer id;
    private Long lastSeq;
    private String lastHash;

    protected AuditHead() {
    }

    public void advance(Long seq, String hash) {
        lastSeq = seq;
        lastHash = hash;
    }

    public Long getLastSeq() {
        return lastSeq;
    }

    public String getLastHash() {
        return lastHash;
    }
}
