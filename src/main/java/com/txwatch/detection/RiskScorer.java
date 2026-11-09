package com.txwatch.detection;

import org.springframework.stereotype.Component;

import com.txwatch.config.TxwatchProperties;
import com.txwatch.domain.Customer;

/** Scores a customer from 0 to 100 and scales rule scores by that risk. */
@Component
public class RiskScorer {

    private final TxwatchProperties properties;

    public RiskScorer(TxwatchProperties properties) {
        this.properties = properties;
    }

    public int customerRisk(Customer customer, long recentAlerts) {
        int score = switch (customer.getOccupationRisk()) {
            case LOW -> 0;
            case MEDIUM -> 15;
            case HIGH -> 30;
        };
        if (properties.detection().highRiskCountries().contains(customer.getCountryCode())) {
            score += 30;
        }
        if (customer.isPep()) {
            score += 25;
        }
        score += (int) Math.min(recentAlerts, 3) * 5;
        return Math.min(score, 100);
    }

    /** A customer at maximum risk doubles the rule's score. The result is capped at 100. */
    public int alertScore(int baseScore, int customerRisk) {
        return Math.min(100, Math.round(baseScore * (1 + customerRisk / 100f)));
    }
}
