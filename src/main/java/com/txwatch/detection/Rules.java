package com.txwatch.detection;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.txwatch.config.TxwatchProperties;
import com.txwatch.domain.Enums.Channel;
import com.txwatch.domain.Txn;

/** The built-in rule set. Each factory returns a stateless rule configured from properties. */
@Configuration
public class Rules {

    private static List<Long> ids(List<Txn> txns) {
        return txns.stream().map(Txn::getId).toList();
    }

    private static BigDecimal sum(List<Txn> txns) {
        return txns.stream().map(Txn::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Cash moving in one direction on one business day reaches the reporting threshold. */
    @Bean
    public DetectionRule cashThresholdRule(TxwatchProperties properties) {
        return cashThreshold(properties.detection().ctrThreshold(), properties.zone());
    }

    public static DetectionRule cashThreshold(BigDecimal threshold, ZoneId zone) {
        return context -> {
            Txn current = context.current();
            if (!current.isCash()) {
                return Optional.empty();
            }
            LocalDate date = current.businessDate(zone);
            List<Txn> sameDay = context.history().stream()
                    .filter(Txn::isCash)
                    .filter(t -> t.getDirection() == current.getDirection())
                    .filter(t -> t.businessDate(zone).equals(date))
                    .toList();
            BigDecimal total = sum(sameDay);
            if (total.compareTo(threshold) < 0) {
                return Optional.empty();
            }
            return Optional.of(new DetectionRule.Finding("CASH_THRESHOLD", 40,
                    "Cash " + current.getDirection() + " total " + total.toPlainString() + " on " + date,
                    "CASH_THRESHOLD:%d:%s:%s".formatted(current.getCustomerId(), date, current.getDirection()),
                    ids(sameDay)));
        };
    }

    /**
     * Repeated cash amounts just under the threshold on days that stay under it, while the total across the window
     * reaches it. Days already over the threshold are reportable and are left to the cash threshold rule.
     */
    @Bean
    public DetectionRule structuringRule(TxwatchProperties properties) {
        TxwatchProperties.Detection d = properties.detection();
        return structuring(d.ctrThreshold(), d.structuringFloorRatio(), d.structuringWindowDays(),
                d.structuringMinCount(), properties.zone());
    }

    public static DetectionRule structuring(BigDecimal threshold, BigDecimal floorRatio, int windowDays, int minCount,
            ZoneId zone) {
        BigDecimal floor = threshold.multiply(floorRatio);
        return context -> {
            Txn current = context.current();
            if (!current.isCash()) {
                return Optional.empty();
            }
            LocalDate end = current.businessDate(zone);
            LocalDate start = end.minusDays(windowDays - 1L);
            Map<LocalDate, List<Txn>> byDay = context.history().stream()
                    .filter(Txn::isCash)
                    .filter(t -> t.getDirection() == current.getDirection())
                    .filter(t -> {
                        LocalDate date = t.businessDate(zone);
                        return !date.isBefore(start) && !date.isAfter(end);
                    })
                    .collect(Collectors.groupingBy(t -> t.businessDate(zone), TreeMap::new, Collectors.toList()));

            List<Txn> suspicious = new ArrayList<>();
            byDay.values().stream()
                    .filter(day -> sum(day).compareTo(threshold) < 0)
                    .flatMap(List::stream)
                    .filter(t -> t.getAmount().compareTo(floor) >= 0)
                    .forEach(suspicious::add);

            if (suspicious.size() < minCount || sum(suspicious).compareTo(threshold) < 0) {
                return Optional.empty();
            }
            LocalDate first = suspicious.stream().map(t -> t.businessDate(zone)).min(Comparator.naturalOrder())
                    .orElseThrow();
            return Optional.of(new DetectionRule.Finding("STRUCTURING", 70,
                    "%d cash transactions just under the threshold totalling %s since %s".formatted(
                            suspicious.size(), sum(suspicious).toPlainString(), first),
                    "STRUCTURING:%d:%s:%s".formatted(current.getCustomerId(), first, current.getDirection()),
                    ids(suspicious)));
        };
    }

    /** More transactions than allowed inside any sliding window that contains the current one. */
    @Bean
    public DetectionRule velocityRule(TxwatchProperties properties) {
        TxwatchProperties.Detection d = properties.detection();
        return velocity(Duration.ofMinutes(d.velocityWindowMinutes()), d.velocityMaxCount());
    }

    public static DetectionRule velocity(Duration window, int maxCount) {
        return context -> {
            Txn current = context.current();
            List<Txn> nearby = context.history().stream()
                    .filter(t -> Duration.between(t.getOccurredAt(), current.getOccurredAt()).abs().compareTo(window) <= 0)
                    .sorted(Comparator.comparing(Txn::getOccurredAt))
                    .toList();
            for (Txn windowStart : nearby) {
                if (windowStart.getOccurredAt().isAfter(current.getOccurredAt())) {
                    break;
                }
                List<Txn> inWindow = nearby.stream()
                        .filter(t -> !t.getOccurredAt().isBefore(windowStart.getOccurredAt()))
                        .filter(t -> !t.getOccurredAt().isAfter(windowStart.getOccurredAt().plus(window)))
                        .toList();
                if (inWindow.size() > maxCount) {
                    long bucket = windowStart.getOccurredAt().getEpochSecond() / window.toSeconds();
                    return Optional.of(new DetectionRule.Finding("VELOCITY", 35,
                            "%d transactions within %d minutes".formatted(inWindow.size(), window.toMinutes()),
                            "VELOCITY:%d:%d".formatted(current.getCustomerId(), bucket),
                            ids(inWindow)));
                }
            }
            return Optional.empty();
        };
    }

    /** A transfer of meaningful size involving a configured high-risk jurisdiction. */
    @Bean
    public DetectionRule highRiskCountryRule(TxwatchProperties properties) {
        TxwatchProperties.Detection d = properties.detection();
        return highRiskCountry(d.highRiskCountries(), d.highRiskCountryMinAmount());
    }

    public static DetectionRule highRiskCountry(java.util.Set<String> countries, BigDecimal minAmount) {
        return context -> {
            Txn current = context.current();
            if (current.getChannel() != Channel.TRANSFER
                    || current.getCounterpartyCountry() == null
                    || !countries.contains(current.getCounterpartyCountry())
                    || current.getAmount().compareTo(minAmount) < 0) {
                return Optional.empty();
            }
            return Optional.of(new DetectionRule.Finding("HIGH_RISK_COUNTRY", 50,
                    "Transfer of %s with counterparty in %s".formatted(current.getAmount().toPlainString(),
                            current.getCounterpartyCountry()),
                    "HIGH_RISK_COUNTRY:" + current.getId(),
                    List.of(current.getId())));
        };
    }

    /** An amount far outside the customer's own earlier behaviour. */
    @Bean
    public DetectionRule amountDeviationRule(TxwatchProperties properties) {
        TxwatchProperties.Detection d = properties.detection();
        return amountDeviation(d.deviationMinHistory(), d.deviationSigma(), d.deviationMinAmount());
    }

    public static DetectionRule amountDeviation(int minHistory, double sigma, BigDecimal minAmount) {
        return context -> {
            Txn current = context.current();
            if (current.getAmount().compareTo(minAmount) < 0) {
                return Optional.empty();
            }
            double[] earlier = context.history().stream()
                    .filter(t -> t.getOccurredAt().isBefore(current.getOccurredAt()))
                    .mapToDouble(t -> t.getAmount().doubleValue())
                    .toArray();
            if (earlier.length < minHistory) {
                return Optional.empty();
            }
            double mean = java.util.Arrays.stream(earlier).average().orElse(0);
            double variance = java.util.Arrays.stream(earlier).map(v -> (v - mean) * (v - mean)).sum() / earlier.length;
            double limit = mean + sigma * Math.sqrt(variance);
            if (current.getAmount().doubleValue() <= limit) {
                return Optional.empty();
            }
            return Optional.of(new DetectionRule.Finding("AMOUNT_DEVIATION", 45,
                    "Amount %s exceeds behavioural limit %.0f".formatted(current.getAmount().toPlainString(), limit),
                    "AMOUNT_DEVIATION:" + current.getId(),
                    List.of(current.getId())));
        };
    }
}
