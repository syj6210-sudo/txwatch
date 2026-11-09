package com.txwatch.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.txwatch.detection.DetectionRule.Context;
import com.txwatch.detection.DetectionRule.Finding;
import com.txwatch.domain.Enums.Channel;
import com.txwatch.domain.Enums.Direction;
import com.txwatch.domain.Txn;

class RulesTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final BigDecimal THRESHOLD = new BigDecimal("10000000");
    private long nextId = 1;

    private Txn txn(String amount, Channel channel, Direction direction, String at) {
        Txn t = new Txn("x" + nextId, 1L, new BigDecimal(amount), channel, direction, null, Instant.parse(at));
        ReflectionTestUtils.setField(t, "id", nextId++);
        return t;
    }

    private Txn transferTo(String country, String amount) {
        Txn t = new Txn("x" + nextId, 1L, new BigDecimal(amount), Channel.TRANSFER, Direction.WITHDRAWAL, country,
                Instant.parse("2026-03-02T01:00:00Z"));
        ReflectionTestUtils.setField(t, "id", nextId++);
        return t;
    }

    private Txn cash(String amount, String at) {
        return txn(amount, Channel.CASH, Direction.DEPOSIT, at);
    }

    private final DetectionRule threshold = Rules.cashThreshold(THRESHOLD, SEOUL);
    private final DetectionRule structuring = Rules.structuring(THRESHOLD, new BigDecimal("0.7"), 5, 3, SEOUL);

    @Test
    void cashThresholdAggregatesOneBusinessDay() {
        Txn a = cash("6000000", "2026-03-02T01:00:00Z");
        Txn b = cash("4000000", "2026-03-02T05:00:00Z");

        Optional<Finding> finding = threshold.evaluate(new Context(b, List.of(a, b)));

        assertThat(finding).isPresent();
        assertThat(finding.get().txnIds()).containsExactly(a.getId(), b.getId());
    }

    @Test
    void cashThresholdUsesTheLocalBusinessDayNotUtc() {
        // 15:30 UTC on March 1 is already March 2 in Seoul, 14:30 UTC is still March 1.
        Txn before = cash("6000000", "2026-03-01T14:30:00Z");
        Txn after = cash("6000000", "2026-03-01T15:30:00Z");

        assertThat(threshold.evaluate(new Context(after, List.of(before, after)))).isEmpty();
    }

    @Test
    void cashThresholdKeepsDirectionsSeparate() {
        Txn in = cash("6000000", "2026-03-02T01:00:00Z");
        Txn out = txn("6000000", Channel.CASH, Direction.WITHDRAWAL, "2026-03-02T02:00:00Z");

        assertThat(threshold.evaluate(new Context(out, List.of(in, out)))).isEmpty();
    }

    @Test
    void cashThresholdIgnoresTransfers() {
        Txn transfer = txn("20000000", Channel.TRANSFER, Direction.DEPOSIT, "2026-03-02T01:00:00Z");

        assertThat(threshold.evaluate(new Context(transfer, List.of(transfer)))).isEmpty();
    }

    @Test
    void cashThresholdSeesRowsThatArrivedBeforeAnEarlierTransaction() {
        Txn later = cash("6000000", "2026-03-02T05:00:00Z");
        Txn lateArrival = cash("5000000", "2026-03-02T01:00:00Z");

        assertThat(threshold.evaluate(new Context(lateArrival, List.of(lateArrival, later)))).isPresent();
    }

    @Test
    void structuringFlagsRepeatedAmountsJustBelowThreshold() {
        Txn a = cash("9500000", "2026-03-02T01:00:00Z");
        Txn b = cash("9000000", "2026-03-03T01:00:00Z");
        Txn c = cash("9800000", "2026-03-04T01:00:00Z");

        Optional<Finding> finding = structuring.evaluate(new Context(c, List.of(a, b, c)));

        assertThat(finding).isPresent();
        assertThat(finding.get().dedupeKey()).isEqualTo("STRUCTURING:1:2026-03-02:DEPOSIT");
    }

    @Test
    void structuringKeepsOneDedupeKeyAsThePatternGrows() {
        Txn a = cash("9500000", "2026-03-02T01:00:00Z");
        Txn b = cash("9000000", "2026-03-03T01:00:00Z");
        Txn c = cash("9800000", "2026-03-04T01:00:00Z");
        Txn d = cash("9700000", "2026-03-05T01:00:00Z");

        String first = structuring.evaluate(new Context(c, List.of(a, b, c))).orElseThrow().dedupeKey();
        String second = structuring.evaluate(new Context(d, List.of(a, b, c, d))).orElseThrow().dedupeKey();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void structuringIgnoresDaysAlreadyReportable() {
        Txn a = cash("9500000", "2026-03-02T01:00:00Z");
        Txn sameDay = cash("9500000", "2026-03-02T02:00:00Z");
        Txn b = cash("9000000", "2026-03-03T01:00:00Z");

        assertThat(structuring.evaluate(new Context(b, List.of(a, sameDay, b)))).isEmpty();
    }

    @Test
    void structuringIgnoresSmallRoutineCash() {
        Txn a = cash("3000000", "2026-03-02T01:00:00Z");
        Txn b = cash("3000000", "2026-03-03T01:00:00Z");
        Txn c = cash("5000000", "2026-03-04T01:00:00Z");

        assertThat(structuring.evaluate(new Context(c, List.of(a, b, c)))).isEmpty();
    }

    @Test
    void velocityFiresOnlyAboveTheLimit() {
        DetectionRule velocity = Rules.velocity(Duration.ofMinutes(60), 3);
        List<Txn> history = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            history.add(cash("1000", "2026-03-02T01:%02d:00Z".formatted(i * 10)));
        }
        assertThat(velocity.evaluate(new Context(history.get(2), history))).isEmpty();

        Txn fourth = cash("1000", "2026-03-02T01:30:00Z");
        history.add(fourth);
        assertThat(velocity.evaluate(new Context(fourth, history))).isPresent();
    }

    @Test
    void velocityDetectsABurstCompletedByALateArrival() {
        DetectionRule velocity = Rules.velocity(Duration.ofMinutes(60), 2);
        Txn lateArrival = cash("1000", "2026-03-02T01:00:00Z");
        Txn b = cash("1000", "2026-03-02T01:10:00Z");
        Txn c = cash("1000", "2026-03-02T01:20:00Z");

        assertThat(velocity.evaluate(new Context(lateArrival, List.of(lateArrival, b, c)))).isPresent();
    }

    @Test
    void highRiskCountryRequiresTransferAndMinimumAmount() {
        DetectionRule rule = Rules.highRiskCountry(Set.of("KP"), new BigDecimal("1000000"));
        Txn match = transferTo("KP", "1000000");
        Txn small = transferTo("KP", "999999");
        Txn elsewhere = transferTo("JP", "5000000");

        assertThat(rule.evaluate(new Context(match, List.of(match)))).isPresent();
        assertThat(rule.evaluate(new Context(small, List.of(small)))).isEmpty();
        assertThat(rule.evaluate(new Context(elsewhere, List.of(elsewhere)))).isEmpty();
    }

    private List<Txn> routineHistory(int count) {
        List<Txn> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(txn(i % 2 == 0 ? "100000" : "120000", Channel.TRANSFER, Direction.DEPOSIT,
                    "2026-01-%02dT01:00:00Z".formatted(i + 1)));
        }
        return history;
    }

    @Test
    void deviationFlagsAnOutlierAgainstOwnHistory() {
        DetectionRule rule = Rules.amountDeviation(10, 3.0, new BigDecimal("5000000"));
        List<Txn> history = routineHistory(12);
        Txn outlier = txn("6000000", Channel.TRANSFER, Direction.DEPOSIT, "2026-02-01T01:00:00Z");
        history.add(outlier);

        assertThat(rule.evaluate(new Context(outlier, history))).isPresent();
    }

    @Test
    void deviationNeedsEnoughHistoryAndIgnoresLaterRows() {
        DetectionRule rule = Rules.amountDeviation(10, 3.0, new BigDecimal("5000000"));
        List<Txn> history = routineHistory(9);
        Txn outlier = txn("6000000", Channel.TRANSFER, Direction.DEPOSIT, "2026-01-15T01:00:00Z");
        history.add(outlier);
        history.add(txn("100000", Channel.TRANSFER, Direction.DEPOSIT, "2026-02-01T01:00:00Z"));

        assertThat(rule.evaluate(new Context(outlier, history))).isEmpty();
    }
}
