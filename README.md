# txwatch

A transaction monitoring service for anti-money-laundering work, built with Java 21 and Spring Boot 3.

It takes in financial transactions, runs detection rules, moves alerts through a reviewer workflow, produces daily
currency transaction reports (CTR), and keeps a tamper-evident audit trail.

This is a personal project using synthetic data. It is not a certified compliance product. Thresholds are configuration
values and should be checked against the rules that apply to you.

## Features

| Area | What it does |
| --- | --- |
| Ingestion | JSON and CSV. Idempotent by external ID, and a reused ID with different data returns 409. CSV rows commit independently. |
| Detection | Five rules, described below. Late-arriving transactions are grouped with their real business day. |
| Risk scoring | Customer risk from occupation, country, PEP status and recent alerts scales each rule's score. |
| Alert workflow | A state machine with mandatory notes. An escalated alert must be closed by a different reviewer. |
| CTR reports | Idempotent daily generation, a supplement step for fields missing from the feed, and XML export. Submitted reports are frozen. |
| Audit | A SHA-256 hash chain with an endpoint that finds the first altered entry. |

## Detection rules

| Rule | Fires when |
| --- | --- |
| `CASH_THRESHOLD` | Cash in one direction on one business day reaches the threshold. |
| `STRUCTURING` | Repeated cash amounts just under the threshold add up to it within a window, on days that stay under it. |
| `VELOCITY` | Any sliding window containing the transaction holds more than the allowed count. |
| `HIGH_RISK_COUNTRY` | A transfer of at least the minimum amount involves a configured country. |
| `AMOUNT_DEVIATION` | An amount exceeds the customer's own mean plus N standard deviations. |

Rules are pure functions over a list of transactions, so they are tested without a database. Each finding has a dedupe
key that identifies the underlying pattern, so repeated evaluation extends one alert instead of creating many.

All thresholds are in `src/main/resources/application.yml`.

## Run

Requires JDK 21 and Maven.

```
mvn verify
mvn spring-boot:run
```

The default database is in-memory H2, with the schema managed by Flyway.

## API

Write endpoints that record an actor need an `X-Actor` header.

| Method and path | Purpose |
| --- | --- |
| `POST /api/customers` | Create a customer |
| `POST /api/transactions` | Ingest one transaction and return any new alerts |
| `POST /api/transactions/import` | Import CSV |
| `GET /api/alerts?status=&page=&size=` | List alerts |
| `GET /api/alerts/{id}` | Read one alert |
| `POST /api/alerts/{id}/transitions` | Apply a workflow action |
| `POST /api/ctr-reports/generate?date=` | Generate reports for a business date |
| `PATCH /api/ctr-reports/{id}/supplement` | Add fields the feed could not provide |
| `POST /api/ctr-reports/{id}/submit` | Mark a READY report as submitted |
| `GET /api/ctr-reports/{id}/xml` | Export a report |
| `GET /api/audit/verify` | Verify the audit chain |

```
curl -X POST localhost:8080/api/customers -H "Content-Type: application/json" \
  -d '{"externalRef":"C-1","name":"Sample Kim","countryCode":"KR","occupationRisk":"LOW","pep":false}'

curl -X POST localhost:8080/api/transactions -H "X-Actor: feed" -H "Content-Type: application/json" \
  -d '{"externalId":"T-1","customerRef":"C-1","amount":12000000,"channel":"CASH","direction":"DEPOSIT","occurredAt":"2026-03-02T01:00:00Z"}'
```

## Design notes

- **Audit ordering.** Writers lock a single head row, so the chain has no gaps and a rolled-back transaction leaves no
  entry. The cost is that audited writes are serialized, which is acceptable at this scale and would be the first thing
  to revisit for high throughput.
- **Business day.** Aggregation uses the configured zone, not UTC, because a reporting day follows local time.
- **Separate CTR values.** Supplemented identifiers are stored on the report and never written to the audit log.

## Known limitations

- There is no authentication. `X-Actor` is trusted and is expected to come from a gateway.
- Only H2 has been tested.
- Alert dedupe relies on a unique constraint, so two concurrent first detections can return 409 to one caller.
- CSV parsing does not support quoted fields.
