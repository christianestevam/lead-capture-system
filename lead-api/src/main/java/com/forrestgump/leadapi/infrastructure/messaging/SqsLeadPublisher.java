package com.forrestgump.leadapi.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forrestgump.leadapi.domain.model.LeadSubmission;
import com.forrestgump.leadapi.infrastructure.exception.InfrastructureException;
import com.forrestgump.leadapi.infrastructure.metrics.MetricsPublisher;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.batchmanager.SqsAsyncBatchManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class SqsLeadPublisher {

    private static final Logger logger = LoggerFactory.getLogger(SqsLeadPublisher.class);
    private final SqsAsyncClient sqsAsyncClient;
    private final SqsAsyncBatchManager sqsAsyncBatchManager;
    private final ObjectMapper objectMapper;
    private final String queueName;
    private final MetricsPublisher metricsPublisher;
    private final CircuitBreaker sqsCircuitBreaker;
    private final Retry sqsRetry;

    public SqsLeadPublisher(SqsAsyncClient sqsAsyncClient, SqsAsyncBatchManager sqsAsyncBatchManager,
                            ObjectMapper objectMapper, @Value("${aws.sqs.queue-name}") String queueName,
                            MetricsPublisher metricsPublisher, CircuitBreaker sqsCircuitBreaker, Retry sqsRetry) {
        this.sqsAsyncClient = sqsAsyncClient;
        this.sqsAsyncBatchManager = sqsAsyncBatchManager;
        this.objectMapper = objectMapper;
        this.queueName = queueName;
        this.metricsPublisher = metricsPublisher;
        this.sqsCircuitBreaker = sqsCircuitBreaker;
        this.sqsRetry = sqsRetry;
    }

    public Mono<Void> publish(LeadSubmission event) {
        // Gerar salt e hash do CPF
        String salt = generateSalt();
        String leadId = generateLeadId(event.cpf(), salt);

        // Criar LeadSubmission atualizado com leadId e salt
        LeadSubmission updatedEvent = new LeadSubmission(
                event.eventId(),
                leadId,
                event.cpf(),
                salt,
                event.name(),
                event.phone(),
                event.email(),
                event.createdAt());

        return Mono.fromCallable(() -> objectMapper.writeValueAsString(updatedEvent))
                .flatMap(message -> Mono.fromFuture(sqsAsyncClient.getQueueUrl(GetQueueUrlRequest.builder()
                                .queueName(queueName)
                                .build()))
                        .map(GetQueueUrlResponse::queueUrl)
                        .flatMap(queueUrl -> Mono.fromFuture(sqsAsyncBatchManager.sendMessage(SendMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .messageBody(message)
                                .build()))))
                .doOnSuccess(response -> {
                    metricsPublisher.incrementSqsPublish("success");
                    logger.info("Lead published successfully in batch, eventId: {}", event.eventId());
                })
                .doOnError(e -> {
                    metricsPublisher.incrementSqsPublish("error");
                    logger.error("Failed to publish lead in batch, eventId: {}, error: {}", event.eventId(), e.getMessage());
                })
                .onErrorMap(e -> new InfrastructureException("Failed to publish to SQS", e))
                .transformDeferred(CircuitBreakerOperator.of(sqsCircuitBreaker))
                .transformDeferred(RetryOperator.of(sqsRetry))
                .then();
    }

    private String generateSalt() {
        byte[] saltBytes = new byte[16];
        new SecureRandom().nextBytes(saltBytes);
        return Base64.getEncoder().encodeToString(saltBytes);
    }

    private String generateLeadId(String cpf, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String combined = cpf + salt;
            byte[] hashBytes = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate lead ID", e);
        }
    }
}