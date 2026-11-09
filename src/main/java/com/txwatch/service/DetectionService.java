package com.txwatch.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.txwatch.config.TxwatchProperties;
import com.txwatch.detection.DetectionRule;
import com.txwatch.detection.DetectionRule.Finding;
import com.txwatch.detection.RiskScorer;
import com.txwatch.domain.Alert;
import com.txwatch.domain.Customer;
import com.txwatch.domain.Txn;
import com.txwatch.repository.Repositories.AlertRepository;
import com.txwatch.repository.Repositories.TxnRepository;

@Service
public class DetectionService {

    private static final String SYSTEM_ACTOR = "system";
    private static final Duration RECENT_ALERTS = Duration.ofDays(90);

    private final List<DetectionRule> rules;
    private final TxnRepository transactions;
    private final AlertRepository alerts;
    private final RiskScorer riskScorer;
    private final AuditService audit;
    private final TxwatchProperties properties;
    private final Clock clock;

    public DetectionService(List<DetectionRule> rules, TxnRepository transactions, AlertRepository alerts,
            RiskScorer riskScorer, AuditService audit, TxwatchProperties properties, Clock clock) {
        this.rules = rules;
        this.transactions = transactions;
        this.alerts = alerts;
        this.riskScorer = riskScorer;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** Runs every rule for a stored transaction and returns the alerts it created. */
    @Transactional
    public List<Alert> evaluate(Txn txn, Customer customer) {
        Instant at = txn.getOccurredAt();
        // Extends past the transaction so that late arrivals are grouped with rows already stored for that day.
        List<Txn> history = transactions.findByCustomerIdAndOccurredAtBetweenOrderByOccurredAt(
                customer.getId(), at.minus(Duration.ofDays(properties.detection().lookbackDays())),
                at.plus(Duration.ofDays(1)));
        DetectionRule.Context context = new DetectionRule.Context(txn, history);

        List<Alert> created = new ArrayList<>();
        for (DetectionRule rule : rules) {
            rule.evaluate(context).flatMap(finding -> apply(finding, customer)).ifPresent(created::add);
        }
        return created;
    }

    private Optional<Alert> apply(Finding finding, Customer customer) {
        Instant now = clock.instant();
        Optional<Alert> existing = alerts.findByDedupeKey(finding.dedupeKey());
        if (existing.isPresent()) {
            existing.get().attachTransactions(finding.txnIds(), now);
            return Optional.empty();
        }
        long recent = alerts.countByCustomerIdAndCreatedAtAfter(customer.getId(), now.minus(RECENT_ALERTS));
        int risk = riskScorer.customerRisk(customer, recent);
        int score = riskScorer.alertScore(finding.baseScore(), risk);
        Alert alert = alerts.save(new Alert(customer.getId(), finding.ruleCode(), finding.dedupeKey(), score,
                finding.reason(), finding.txnIds(), now));
        audit.record(SYSTEM_ACTOR, "ALERT_CREATED", "ALERT", alert.getId(),
                finding.ruleCode() + " score=" + score);
        return Optional.of(alert);
    }
}
