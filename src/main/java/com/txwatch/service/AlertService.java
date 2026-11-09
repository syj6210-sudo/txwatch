package com.txwatch.service;

import java.time.Clock;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.txwatch.domain.Alert;
import com.txwatch.domain.Enums.AlertAction;
import com.txwatch.domain.Enums.AlertStatus;
import com.txwatch.repository.Repositories.AlertRepository;
import com.txwatch.service.DomainExceptions.NotFoundException;

@Service
public class AlertService {

    private final AlertRepository alerts;
    private final AuditService audit;
    private final Clock clock;

    public AlertService(AlertRepository alerts, AuditService audit, Clock clock) {
        this.alerts = alerts;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<Alert> list(AlertStatus status, Pageable pageable) {
        return status == null ? alerts.findAll(pageable) : alerts.findByStatus(status, pageable);
    }

    @Transactional(readOnly = true)
    public Alert get(Long id) {
        return alerts.findById(id).orElseThrow(() -> new NotFoundException("Alert " + id + " not found"));
    }

    @Transactional
    public Alert transition(Long id, AlertAction action, String actor, String note) {
        Alert alert = alerts.findById(id).orElseThrow(() -> new NotFoundException("Alert " + id + " not found"));
        alert.apply(action, actor, note, clock.instant());
        audit.record(actor, "ALERT_" + action, "ALERT", id, note == null ? "" : note);
        return alert;
    }
}
