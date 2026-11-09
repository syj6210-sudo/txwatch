package com.txwatch.service;

import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.txwatch.config.TxwatchProperties;

/** A separate bean so that the call to {@link CtrService#generate} goes through the transactional proxy. */
@Component
public class CtrScheduler {

    private static final Logger log = LoggerFactory.getLogger(CtrScheduler.class);

    private final CtrService ctrService;
    private final TxwatchProperties properties;
    private final Clock clock;

    public CtrScheduler(CtrService ctrService, TxwatchProperties properties, Clock clock) {
        this.ctrService = ctrService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${txwatch.ctr-generation-cron}", zone = "${txwatch.zone}")
    public void generateForYesterday() {
        LocalDate yesterday = LocalDate.now(clock.withZone(properties.zone())).minusDays(1);
        log.info("CTR reports for {}: {}", yesterday, ctrService.generate(yesterday).size());
    }
}
