package com.txwatch.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.txwatch.domain.Enums.CtrStatus;
import com.txwatch.domain.Enums.Direction;
import com.txwatch.service.DomainExceptions.InvalidStateException;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

@Entity
public class CtrReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version
    private Long version;
    private Long customerId;
    private LocalDate businessDate;
    @Enumerated(EnumType.STRING)
    private Direction direction;
    private BigDecimal totalAmount;
    private int txnCount;
    @Enumerated(EnumType.STRING)
    private CtrStatus status;
    private String nationalIdToken;
    private String phone;
    private String submittedBy;
    private Instant updatedAt;

    protected CtrReport() {
    }

    public CtrReport(Long customerId, LocalDate businessDate, Direction direction, String nationalIdToken,
            String phone) {
        this.customerId = customerId;
        this.businessDate = businessDate;
        this.direction = direction;
        this.nationalIdToken = nationalIdToken;
        this.phone = phone;
    }

    /** Refreshes totals from source transactions. Fields an operator supplemented are kept. */
    public void refresh(BigDecimal total, int count, Instant now) {
        if (status == CtrStatus.SUBMITTED) {
            throw new InvalidStateException("A submitted report cannot be changed");
        }
        totalAmount = total;
        txnCount = count;
        updatedAt = now;
        recomputeStatus();
    }

    /**
     * Records fields the source system could not provide, typically because security policy keeps them out of the
     * data feed. Only the fields passed in are overwritten.
     */
    public void supplement(String idToken, String phoneNumber, Instant now) {
        if (status == CtrStatus.SUBMITTED) {
            throw new InvalidStateException("A submitted report cannot be changed");
        }
        if (idToken != null && !idToken.isBlank()) {
            nationalIdToken = idToken;
        }
        if (phoneNumber != null && !phoneNumber.isBlank()) {
            phone = phoneNumber;
        }
        updatedAt = now;
        recomputeStatus();
    }

    public void submit(String actor, Instant now) {
        if (status != CtrStatus.READY) {
            throw new InvalidStateException("Only a READY report can be submitted, status is " + status);
        }
        status = CtrStatus.SUBMITTED;
        submittedBy = actor;
        updatedAt = now;
    }

    private void recomputeStatus() {
        status = isBlank(nationalIdToken) || isBlank(phone) ? CtrStatus.NEEDS_INFO : CtrStatus.READY;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public Direction getDirection() {
        return direction;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public int getTxnCount() {
        return txnCount;
    }

    public CtrStatus getStatus() {
        return status;
    }

    public String getNationalIdToken() {
        return nationalIdToken;
    }

    public String getPhone() {
        return phone;
    }
}
