package com.txwatch.domain;

import java.util.EnumSet;
import java.util.Set;

/** Value types shared across the domain model. */
public final class Enums {

    private Enums() {
    }

    public enum RiskLevel { LOW, MEDIUM, HIGH }

    public enum Channel { CASH, TRANSFER, CARD }

    public enum Direction { DEPOSIT, WITHDRAWAL }

    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL;

        public static Severity fromScore(int score) {
            if (score >= 80) {
                return CRITICAL;
            }
            if (score >= 60) {
                return HIGH;
            }
            return score >= 40 ? MEDIUM : LOW;
        }
    }

    public enum AlertStatus { OPEN, IN_REVIEW, ESCALATED, CLOSED_FALSE_POSITIVE, CLOSED_REPORTED }

    public enum AlertAction {
        START_REVIEW(EnumSet.of(AlertStatus.OPEN), false),
        ESCALATE(EnumSet.of(AlertStatus.IN_REVIEW), true),
        CLOSE_FALSE_POSITIVE(EnumSet.of(AlertStatus.IN_REVIEW, AlertStatus.ESCALATED), true),
        CLOSE_REPORTED(EnumSet.of(AlertStatus.ESCALATED), true);

        private final Set<AlertStatus> allowedFrom;
        private final boolean noteRequired;

        AlertAction(Set<AlertStatus> allowedFrom, boolean noteRequired) {
            this.allowedFrom = allowedFrom;
            this.noteRequired = noteRequired;
        }

        public boolean allowedFrom(AlertStatus status) {
            return allowedFrom.contains(status);
        }

        public boolean noteRequired() {
            return noteRequired;
        }
    }

    public enum CtrStatus { NEEDS_INFO, READY, SUBMITTED }
}
