package com.ssuai.global.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the library reservation intent-status bus when it is graduated from a Redisson
 * RTopic to Kafka (Phase 2-C, ADR 0091). Gated independently of the tool-call pipeline
 * ({@code ssuai.kafka.enabled}) so the live reservation-notify path can be cut over — and rolled
 * back — on its own flag. Enabling {@code ssuai.kafka.intent-bus.enabled} requires the broker beans
 * from {@code ssuai.kafka.enabled=true} to exist (they share the reused producer template).
 */
@ConfigurationProperties("ssuai.kafka.intent-bus")
public class IntentBusKafkaProperties {

    private boolean enabled = false;
    private String topic = "library.reservation.events.v1";
    private int partitions = 6;
    private int queueCapacity = 500;
    private long maxBlockMs = 500;
    /**
     * Per-pod consumer groups all derive from this prefix plus a random suffix so every replica
     * receives every event (broadcast fan-out) instead of load-balancing within one group. Groups
     * are ephemeral: with auto-offset-reset=latest and no meaningful offset tracking, Kafka reaps
     * the empty groups after offsets.retention.minutes.
     */
    private String groupPrefix = "library-intent-sse";
    /**
     * Where a fresh per-pod group starts. "latest" (prod default) streams "from now on" — abandoned
     * events for reservations that already terminated are irrelevant to a newly started pod.
     */
    private String autoOffsetReset = "latest";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getPartitions() {
        return partitions;
    }

    public void setPartitions(int partitions) {
        this.partitions = partitions;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public long getMaxBlockMs() {
        return maxBlockMs;
    }

    public void setMaxBlockMs(long maxBlockMs) {
        this.maxBlockMs = maxBlockMs;
    }

    public String getGroupPrefix() {
        return groupPrefix;
    }

    public void setGroupPrefix(String groupPrefix) {
        this.groupPrefix = groupPrefix;
    }

    public String getAutoOffsetReset() {
        return autoOffsetReset;
    }

    public void setAutoOffsetReset(String autoOffsetReset) {
        this.autoOffsetReset = autoOffsetReset;
    }
}
