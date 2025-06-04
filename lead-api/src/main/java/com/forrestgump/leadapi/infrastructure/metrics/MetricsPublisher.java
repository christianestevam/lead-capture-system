package com.forrestgump.leadapi.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class MetricsPublisher {

    private final MeterRegistry meterRegistry;

    public MetricsPublisher(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void incrementSqsPublish(String status) {
        meterRegistry.counter("sqs.publish.count", "status", status).increment();
    }

    public void incrementRateLimit() {
        meterRegistry.counter("api.rate_limit.count").increment();
    }
}