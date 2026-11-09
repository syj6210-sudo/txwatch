package com.txwatch.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.txwatch.domain.AuditEvent;
import com.txwatch.domain.AuditHead;
import com.txwatch.repository.Repositories.AuditEventRepository;
import com.txwatch.repository.Repositories.AuditHeadRepository;

/**
 * Appends to a SHA-256 hash chain. Writers lock one head row, so the chain stays gapless and rolled-back transactions
 * leave no trace in it.
 */
@Service
public class AuditService {

    static final String GENESIS = "0".repeat(64);

    private final AuditEventRepository events;
    private final AuditHeadRepository heads;
    private final Clock clock;

    public AuditService(AuditEventRepository events, AuditHeadRepository heads, Clock clock) {
        this.events = events;
        this.heads = heads;
        this.clock = clock;
    }

    @Transactional
    public void record(String actor, String action, String entityType, Object entityId, String detail) {
        AuditHead head = heads.lockHead();
        long seq = head.getLastSeq() + 1;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String id = String.valueOf(entityId);
        String hash = hash(head.getLastHash(), seq, actor, action, entityType, id, detail, now);
        events.save(new AuditEvent(seq, actor, action, entityType, id, detail, now, head.getLastHash(), hash));
        head.advance(seq, hash);
    }

    public record Verification(boolean intact, long checked, Long firstBrokenSeq) {
    }

    @Transactional(readOnly = true)
    public Verification verify() {
        List<AuditEvent> all = events.findAllByOrderBySeqAsc();
        String previous = GENESIS;
        long expectedSeq = 1;
        for (AuditEvent event : all) {
            String recomputed = hash(previous, event.getSeq(), event.getActor(), event.getAction(),
                    event.getEntityType(), event.getEntityId(), event.getDetail(), event.getOccurredAt());
            if (event.getSeq() != expectedSeq || !event.getPrevHash().equals(previous)
                    || !event.getHash().equals(recomputed)) {
                return new Verification(false, expectedSeq - 1, expectedSeq);
            }
            previous = event.getHash();
            expectedSeq++;
        }
        AuditHead head = heads.readHead();
        if (head.getLastSeq() != all.size() || !head.getLastHash().equals(previous)) {
            // Rows were removed from the end of the chain.
            return new Verification(false, all.size(), all.size() + 1L);
        }
        return new Verification(true, all.size(), null);
    }

    private static String hash(String previous, long seq, String actor, String action, String entityType,
            String entityId, String detail, Instant at) {
        // Length-prefixed fields, so that moving text between fields changes the digest.
        StringBuilder canonical = new StringBuilder(previous).append('|').append(seq);
        for (String field : List.of(actor, action, entityType, entityId, detail, at.toString())) {
            canonical.append('|').append(field.length()).append(':').append(field);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
