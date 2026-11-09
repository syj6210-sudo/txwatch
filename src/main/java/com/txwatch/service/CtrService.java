package com.txwatch.service;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.txwatch.config.TxwatchProperties;
import com.txwatch.domain.CtrReport;
import com.txwatch.domain.Customer;
import com.txwatch.domain.Enums.Channel;
import com.txwatch.domain.Enums.CtrStatus;
import com.txwatch.domain.Enums.Direction;
import com.txwatch.domain.Txn;
import com.txwatch.repository.Repositories.CtrReportRepository;
import com.txwatch.repository.Repositories.CustomerRepository;
import com.txwatch.repository.Repositories.TxnRepository;
import com.txwatch.service.DomainExceptions.InvalidStateException;
import com.txwatch.service.DomainExceptions.NotFoundException;

/** Builds daily currency transaction reports from stored cash transactions. */
@Service
public class CtrService {

    private static final Logger log = LoggerFactory.getLogger(CtrService.class);

    private record Key(Long customerId, Direction direction) {
    }

    private final TxnRepository transactions;
    private final CtrReportRepository reports;
    private final CustomerRepository customers;
    private final AuditService audit;
    private final TxwatchProperties properties;
    private final Clock clock;

    public CtrService(TxnRepository transactions, CtrReportRepository reports, CustomerRepository customers,
            AuditService audit, TxwatchProperties properties, Clock clock) {
        this.transactions = transactions;
        this.reports = reports;
        this.customers = customers;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** Safe to re-run: draft reports are refreshed, submitted reports are left untouched. */
    @Transactional
    public List<CtrReport> generate(LocalDate date) {
        ZoneId zone = properties.zone();
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        Map<Key, List<Txn>> grouped = transactions
                .findByChannelAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(Channel.CASH, from, to).stream()
                .collect(Collectors.groupingBy(t -> new Key(t.getCustomerId(), t.getDirection())));

        Instant now = clock.instant();
        for (Map.Entry<Key, List<Txn>> entry : grouped.entrySet()) {
            BigDecimal total = entry.getValue().stream().map(Txn::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(properties.detection().ctrThreshold()) < 0) {
                continue;
            }
            Key key = entry.getKey();
            CtrReport report = reports.findByCustomerIdAndBusinessDateAndDirection(key.customerId(), date,
                    key.direction()).orElseGet(() -> newReport(key, date));
            if (report.getStatus() == CtrStatus.SUBMITTED) {
                if (report.getTotalAmount().compareTo(total) != 0) {
                    log.warn("Submitted CTR {} differs from source total {}", report.getId(), total);
                }
                continue;
            }
            report.refresh(total, entry.getValue().size(), now);
            reports.save(report);
        }
        return reports.findByBusinessDateOrderById(date);
    }

    private CtrReport newReport(Key key, LocalDate date) {
        Customer customer = customers.findById(key.customerId()).orElseThrow();
        return new CtrReport(key.customerId(), date, key.direction(), customer.getNationalIdToken(),
                customer.getPhone());
    }

    @Transactional
    public CtrReport supplement(Long id, String nationalIdToken, String phone, String actor) {
        CtrReport report = find(id);
        report.supplement(nationalIdToken, phone, clock.instant());
        // The values are identifiers, so the audit trail records only that they changed.
        audit.record(actor, "CTR_SUPPLEMENTED", "CTR", id, "status=" + report.getStatus());
        return report;
    }

    @Transactional
    public CtrReport submit(Long id, String actor) {
        CtrReport report = find(id);
        report.submit(actor, clock.instant());
        audit.record(actor, "CTR_SUBMITTED", "CTR", id, report.getTotalAmount().toPlainString());
        return report;
    }

    @Transactional(readOnly = true)
    public String toXml(Long id) {
        CtrReport report = find(id);
        if (report.getStatus() == CtrStatus.NEEDS_INFO) {
            throw new InvalidStateException("Report " + id + " is missing required customer information");
        }
        Customer customer = customers.findById(report.getCustomerId()).orElseThrow();
        StringWriter out = new StringWriter();
        try {
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("ctrReport");
            xml.writeAttribute("id", String.valueOf(report.getId()));
            element(xml, "businessDate", report.getBusinessDate().toString());
            element(xml, "direction", report.getDirection().name());
            element(xml, "totalAmount", report.getTotalAmount().toPlainString());
            element(xml, "transactionCount", String.valueOf(report.getTxnCount()));
            xml.writeStartElement("customer");
            element(xml, "reference", customer.getExternalRef());
            element(xml, "name", customer.getName());
            element(xml, "nationalIdToken", report.getNationalIdToken());
            element(xml, "phone", report.getPhone());
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Could not render report " + id, e);
        }
        return out.toString();
    }

    private static void element(XMLStreamWriter xml, String name, String value) throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeCharacters(value);
        xml.writeEndElement();
    }

    private CtrReport find(Long id) {
        return reports.findById(id).orElseThrow(() -> new NotFoundException("CTR report " + id + " not found"));
    }
}
