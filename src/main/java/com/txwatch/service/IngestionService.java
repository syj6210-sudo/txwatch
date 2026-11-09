package com.txwatch.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.txwatch.domain.Alert;
import com.txwatch.domain.Customer;
import com.txwatch.domain.Enums.Channel;
import com.txwatch.domain.Enums.Direction;
import com.txwatch.domain.Txn;
import com.txwatch.repository.Repositories.CustomerRepository;
import com.txwatch.repository.Repositories.TxnRepository;
import com.txwatch.service.DomainExceptions.ConflictException;
import com.txwatch.service.DomainExceptions.NotFoundException;

@Service
public class IngestionService {

    public record Command(String externalId, String customerRef, BigDecimal amount, Channel channel,
            Direction direction, String counterpartyCountry, Instant occurredAt) {
    }

    public record Result(Txn transaction, boolean replayed, List<Alert> alerts) {
    }

    private final CustomerRepository customers;
    private final TxnRepository transactions;
    private final DetectionService detection;
    private final AuditService audit;

    public IngestionService(CustomerRepository customers, TxnRepository transactions, DetectionService detection,
            AuditService audit) {
        this.customers = customers;
        this.transactions = transactions;
        this.detection = detection;
        this.audit = audit;
    }

    /** Stores one transaction. Replaying the same payload is a no-op, a different payload is rejected. */
    @Transactional
    public Result ingest(Command command, String actor) {
        if (command.amount().signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        Customer customer = customers.findByExternalRef(command.customerRef())
                .orElseThrow(() -> new NotFoundException("Unknown customer " + command.customerRef()));
        Txn candidate = new Txn(command.externalId(), customer.getId(), command.amount(), command.channel(),
                command.direction(), command.counterpartyCountry(), command.occurredAt());

        Optional<Txn> existing = transactions.findByExternalId(command.externalId());
        if (existing.isPresent()) {
            if (!existing.get().samePayload(candidate)) {
                throw new ConflictException("Transaction " + command.externalId() + " already exists with other data");
            }
            return new Result(existing.get(), true, List.of());
        }

        Txn saved = transactions.saveAndFlush(candidate);
        audit.record(actor, "TXN_INGESTED", "TXN", saved.getId(), saved.getExternalId());
        return new Result(saved, false, detection.evaluate(saved, customer));
    }
}
