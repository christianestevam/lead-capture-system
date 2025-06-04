package com.forrestgump.leadprocessor.application.usecase;

import com.forrestgump.leadprocessor.domain.exception.LeadValidationException;
import com.forrestgump.leadprocessor.domain.model.Lead;
import com.forrestgump.leadprocessor.domain.model.LeadSubmission;
import com.forrestgump.leadprocessor.domain.service.LeadProcessingService;
import com.forrestgump.leadprocessor.infrastructure.exception.InfrastructureException;
import com.forrestgump.leadprocessor.infrastructure.metrics.MetricsPublisher;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ProcessLeadUseCase {

    private static final Logger logger = LoggerFactory.getLogger(ProcessLeadUseCase.class);
    private final LeadProcessingService leadProcessingService;
    private final MetricsPublisher metricsPublisher;
    private final CircuitBreaker dynamoCircuitBreaker;

    public ProcessLeadUseCase(LeadProcessingService leadProcessingService, MetricsPublisher metricsPublisher,
                              @Qualifier("dynamoCircuitBreaker") CircuitBreaker dynamoCircuitBreaker) {
        this.leadProcessingService = leadProcessingService;
        this.metricsPublisher = metricsPublisher;
        this.dynamoCircuitBreaker = dynamoCircuitBreaker;
    }

    public Mono<Void> execute(LeadSubmission event, String correlationId) {
        return Mono.fromCallable(() -> new Lead(
                        event.leadId(),
                        event.cpf(),
                        null, // encryptedCpf is set later in DynamoLeadRepository
                        event.salt(),
                        event.name(),
                        event.phone(),
                        event.email(),
                        event.createdAt()))
                .doOnNext(lead -> logger.info("Processing lead, eventId: {}, correlationId: {}, leadId: {}",
                        event.eventId(), correlationId, lead.getLeadId()))
                .flatMap(leadProcessingService::processLead)
                .doOnSuccess(v -> {
                    metricsPublisher.incrementLeadProcessing("success");
                    logger.info("Lead processed successfully, eventId: {}, correlationId: {}", event.eventId(), correlationId);
                })
                .onErrorMap(LeadValidationException.class, e -> {
                    metricsPublisher.incrementLeadProcessing("validation_error");
                    logger.error("Validation error for lead, eventId: {}, correlationId: {}, error: {}",
                            event.eventId(), correlationId, e.getMessage());
                    return e;
                })
                .onErrorMap(e -> {
                    metricsPublisher.incrementLeadProcessing("error");
                    logger.error("Unexpected error for lead, eventId: {}, correlationId: {}, error: {}",
                            event.eventId(), correlationId, e.getMessage());
                    return new InfrastructureException("Failed to process lead", e);
                })
                .transformDeferred(CircuitBreakerOperator.of(dynamoCircuitBreaker))
                .then();
    }
}