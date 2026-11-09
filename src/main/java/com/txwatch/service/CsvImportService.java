package com.txwatch.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.txwatch.domain.Enums.Channel;
import com.txwatch.domain.Enums.Direction;

/**
 * Imports {@code externalId,customerRef,amount,channel,direction,counterpartyCountry,occurredAt} rows. Deliberately
 * not transactional: each row commits alone, so one bad row does not discard the rest of a batch.
 */
@Service
public class CsvImportService {

    private static final int COLUMNS = 7;

    public record RowError(int line, String message) {
    }

    public record Summary(int imported, int replayed, List<RowError> errors) {
    }

    private final IngestionService ingestion;

    public CsvImportService(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    public Summary importCsv(String content, String actor) {
        String[] lines = content.split("\\R");
        int imported = 0;
        int replayed = 0;
        List<RowError> errors = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || (i == 0 && line.toLowerCase(Locale.ROOT).startsWith("externalid,"))) {
                continue;
            }
            try {
                if (ingestion.ingest(parse(line), actor).replayed()) {
                    replayed++;
                } else {
                    imported++;
                }
            } catch (RuntimeException e) {
                errors.add(new RowError(i + 1, e.getMessage()));
            }
        }
        return new Summary(imported, replayed, errors);
    }

    private static IngestionService.Command parse(String line) {
        String[] cells = line.split(",", -1);
        if (cells.length != COLUMNS) {
            throw new IllegalArgumentException("Expected " + COLUMNS + " columns, found " + cells.length);
        }
        String country = cells[5].trim();
        return new IngestionService.Command(cells[0].trim(), cells[1].trim(), new BigDecimal(cells[2].trim()),
                Channel.valueOf(cells[3].trim()), Direction.valueOf(cells[4].trim()),
                country.isEmpty() ? null : country, Instant.parse(cells[6].trim()));
    }
}
