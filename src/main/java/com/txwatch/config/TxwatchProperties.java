package com.txwatch.config;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("txwatch")
public record TxwatchProperties(ZoneId zone, Detection detection) {

    public record Detection(
            int lookbackDays,
            BigDecimal ctrThreshold,
            int structuringWindowDays,
            int structuringMinCount,
            BigDecimal structuringFloorRatio,
            int velocityWindowMinutes,
            int velocityMaxCount,
            Set<String> highRiskCountries,
            BigDecimal highRiskCountryMinAmount,
            int deviationMinHistory,
            double deviationSigma,
            BigDecimal deviationMinAmount) {
    }
}
