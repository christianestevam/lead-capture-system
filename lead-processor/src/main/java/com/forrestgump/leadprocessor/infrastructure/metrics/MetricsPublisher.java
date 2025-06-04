package com.forrestgump.leadprocessor.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class MetricsPublisher {

    private final MeterRegistry meterRegistry;

    public MetricsPublisher(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void incrementLeadProcessing(String status) {
        meterRegistry.counter("lead.processing.count", "status", status).increment();
    }

    public void incrementSqsConsume(String status) {
        meterRegistry.counter("sqs.consume.count", "status", status).increment();
    }

    public void incrementDlqCount() {
        meterRegistry.counter("sqs.dlq.count").increment();
    }
}