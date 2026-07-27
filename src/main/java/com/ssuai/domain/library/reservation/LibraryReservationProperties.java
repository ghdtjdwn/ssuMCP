package com.ssuai.domain.library.reservation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ssuai.library.reservation")
public class LibraryReservationProperties {

    private String baseUrl = "https://oasis.ssu.ac.kr";
    private String referer = "https://oasis.ssu.ac.kr/library-services/smuf/reading-rooms";
    private String dischargeReferer = "https://oasis.ssu.ac.kr/mylibrary/seat/reservations";
    private Duration timeout = Duration.ofSeconds(10);
    private Duration actionReconciliationDelay = Duration.ofMinutes(1);
    private int actionReconciliationBatchSize = 20;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getReferer() {
        return referer;
    }

    public void setReferer(String referer) {
        this.referer = referer;
    }

    public String getDischargeReferer() {
        return dischargeReferer;
    }

    public void setDischargeReferer(String dischargeReferer) {
        this.dischargeReferer = dischargeReferer;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getActionReconciliationDelay() {
        return actionReconciliationDelay;
    }

    public void setActionReconciliationDelay(Duration actionReconciliationDelay) {
        if (actionReconciliationDelay == null || actionReconciliationDelay.isNegative()
                || actionReconciliationDelay.isZero()) {
            throw new IllegalArgumentException("actionReconciliationDelay must be positive");
        }
        this.actionReconciliationDelay = actionReconciliationDelay;
    }

    public int getActionReconciliationBatchSize() {
        return actionReconciliationBatchSize;
    }

    public void setActionReconciliationBatchSize(int actionReconciliationBatchSize) {
        if (actionReconciliationBatchSize < 1 || actionReconciliationBatchSize > 100) {
            throw new IllegalArgumentException("actionReconciliationBatchSize must be between 1 and 100");
        }
        this.actionReconciliationBatchSize = actionReconciliationBatchSize;
    }
}
