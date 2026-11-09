package com.txwatch.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.txwatch.domain.Alert;
import com.txwatch.domain.AuditEvent;
import com.txwatch.domain.AuditHead;
import com.txwatch.domain.CtrReport;
import com.txwatch.domain.Customer;
import com.txwatch.domain.Enums.AlertStatus;
import com.txwatch.domain.Enums.Channel;
import com.txwatch.domain.Enums.Direction;
import com.txwatch.domain.Txn;

import jakarta.persistence.LockModeType;

public final class Repositories {

    private Repositories() {
    }

    public interface CustomerRepository extends JpaRepository<Customer, Long> {
        Optional<Customer> findByExternalRef(String externalRef);
    }

    public interface TxnRepository extends JpaRepository<Txn, Long> {
        Optional<Txn> findByExternalId(String externalId);

        List<Txn> findByCustomerIdAndOccurredAtBetweenOrderByOccurredAt(Long customerId, Instant from, Instant to);

        List<Txn> findByChannelAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(Channel channel, Instant from,
                Instant to);
    }

    public interface AlertRepository extends JpaRepository<Alert, Long> {
        Optional<Alert> findByDedupeKey(String dedupeKey);

        long countByCustomerIdAndCreatedAtAfter(Long customerId, Instant after);

        Page<Alert> findByStatus(AlertStatus status, Pageable pageable);
    }

    public interface CtrReportRepository extends JpaRepository<CtrReport, Long> {
        Optional<CtrReport> findByCustomerIdAndBusinessDateAndDirection(Long customerId, LocalDate date,
                Direction direction);

        List<CtrReport> findByBusinessDateOrderById(LocalDate date);
    }

    public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
        List<AuditEvent> findAllByOrderBySeqAsc();

        List<AuditEvent> findByEntityTypeAndEntityIdOrderBySeqAsc(String entityType, String entityId);
    }

    public interface AuditHeadRepository extends JpaRepository<AuditHead, Integer> {
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select h from AuditHead h where h.id = 1")
        AuditHead lockHead();

        @Query("select h from AuditHead h where h.id = 1")
        AuditHead readHead();
    }
}
