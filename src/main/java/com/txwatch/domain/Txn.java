package com.txwatch.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import com.txwatch.domain.Enums.Channel;
import com.txwatch.domain.Enums.Direction;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Txn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String externalId;
    private Long customerId;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    @Enumerated(EnumType.STRING)
    private Direction direction;
    private String counterpartyCountry;
    private Instant occurredAt;

    protected Txn() {
    }

    public Txn(String externalId, Long customerId, BigDecimal amount, Channel channel, Direction direction,
            String counterpartyCountry, Instant occurredAt) {
        this.externalId = externalId;
        this.customerId = customerId;
        this.amount = amount;
        this.channel = channel;
        this.direction = direction;
        this.counterpartyCountry = counterpartyCountry;
        // Truncated to the column precision so a replayed payload compares equal to the stored row.
        this.occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** True when both rows describe the same business event, ignoring database identity. */
    public boolean samePayload(Txn other) {
        return customerId.equals(other.customerId)
                && amount.compareTo(other.amount) == 0
                && channel == other.channel
                && direction == other.direction
                && Objects.equals(counterpartyCountry, other.counterpartyCountry)
                && occurredAt.equals(other.occurredAt);
    }

    public LocalDate businessDate(ZoneId zone) {
        return occurredAt.atZone(zone).toLocalDate();
    }

    public boolean isCash() {
        return channel == Channel.CASH;
    }

    public Long getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Channel getChannel() {
        return channel;
    }

    public Direction getDirection() {
        return direction;
    }

    public String getCounterpartyCountry() {
        return counterpartyCountry;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
