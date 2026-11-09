package com.txwatch.detection;

import java.util.List;
import java.util.Optional;

import com.txwatch.domain.Txn;

/**
 * A pure detection rule. Rules read only the transaction list they are given, so they can be tested without a
 * database.
 */
public interface DetectionRule {

    Optional<Finding> evaluate(Context context);

    /**
     * @param current the transaction that triggered evaluation
     * @param history the customer's transactions around it, including {@code current}. Transactions can arrive out of
     *                order, so the list may also hold rows that occurred after {@code current}.
     */
    record Context(Txn current, List<Txn> history) {
    }

    /** @param dedupeKey identifies the underlying pattern, so repeated evaluation extends one alert */
    record Finding(String ruleCode, int baseScore, String reason, String dedupeKey, List<Long> txnIds) {
    }
}
