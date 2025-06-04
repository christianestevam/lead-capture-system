package com.forrestgump.leadprocessor.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forrestgump.leadprocessor.application.usecase.ProcessLeadUseCase;
import com.forrestgump.leadprocessor.domain.model.LeadSubmission;
import com.forrestgump.leadprocessor.infrastructure.exception.InfrastructureException;
import com.forrestgump.leadprocessor.infrastructure.metrics.MetricsPublisher;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.batchmanager.SqsAsyncBatchManager;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.UUID;

@Component
public class SqsLeadConsumer {

    private static final Logger logger = LoggerFactory.getLogger(SqsLeadConsumer.class);
    private final SqsAsyncClient sqsAsyncClient;
    private final SqsAsyncBatchManager sqsAsyncBatchManager;
    private final ObjectMapper objectMapper;
    private final String queueName;
    private final ProcessLeadUseCase processLeadUseCase;
    private final MetricsPublisher metricsPublisher;
    private final CircuitBreaker sqsCircuitBreaker;
    private final Retry sqsRetry;

    public SqsLeadConsumer(SqsAsyncClient sqsAsyncClient, SqsAsyncBatchManager sqsAsyncBatchManager,
                           ObjectMapper objectMapper, @Value("${aws.sqs.queue-name}") String queueName,
                           ProcessLeadUseCase processLeadUseCase, MetricsPublisher metricsPublisher,
                           CircuitBreaker sqsCircuitBreaker, Retry sqsRetry) {
        this.sqsAsyncClient = sqsAsyncClient;
        this.sqsAsyncBatchManager = sqsAsyncBatchManager;
        this.objectMapper = objectMapper;
        this.queueName = queueName;
        this.processLeadUseCase = processLeadUseCase;
        this.metricsPublisher = metricsPublisher;
        this.sqsCircuitBreaker = sqsCircuitBreaker;
        this.sqsRetry = sqsRetry;
    }

    @Scheduled(fixedRate = 5000)
    public void consumeMessages() {
        getQueueUrl()
                .flatMapMany(queueUrl -> Flux.defer(() -> Mono.fromFuture(sqsAsyncBatchManager.receiveMessage(ReceiveMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .maxNumberOfMessages(10)
                                .waitTimeSeconds(20)
                                .build())))
                        .flatMapIterable(ReceiveMessageResponse::messages)
                        .flatMap(message -> processMessage(message, queueUrl)
                                .onErrorResume(e -> {
                                    metricsPublisher.incrementDlqCount();
                                    logger.error("Message sent to DLQ, correlationId: {}, error: {}",
                                            extractCorrelationId(message), e.getMessage());
                                    return Mono.empty();
                                })))
                .doOnNext(v -> metricsPublisher.incrementSqsConsume("success"))
                .doOnError(e -> {
                    metricsPublisher.incrementSqsConsume("error");
                    logger.error("Failed to consume messages from SQS: {}", e.getMessage());
                })
                .onErrorMap(e -> new InfrastructureException("Failed to consume from SQS", e))
                .transformDeferred(CircuitBreakerOperator.of(sqsCircuitBreaker))
                .transformDeferred(RetryOperator.of(sqsRetry))
                .subscribe();
    }

    private Mono<String> getQueueUrl() {
        return Mono.fromFuture(sqsAsyncClient.getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(queueName)
                        .build()))
                .map(response -> response.queueUrl())
                .doOnError(e -> logger.error("Failed to get queue URL: {}", e.getMessage()));
    }

    private Mono<Void> processMessage(Message message, String queueUrl) {
        return Mono.fromCallable(() -> objectMapper.readValue(message.body(), LeadSubmission.class))
                .flatMap(event -> {
                    String correlationId = message.messageAttributes().getOrDefault("X-Correlation-Id",
                                    software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
                                            .stringValue(UUID.randomUUID().toString())
                                            .build())
                            .stringValue();
                    return processLeadUseCase.execute(event, correlationId);
                })
                .then(Mono.fromFuture(sqsAsyncBatchManager.deleteMessage(builder -> builder
                                .queueUrl(queueUrl)
                                .receiptHandle(message.receiptHandle())))
                        .then())
                .doOnError(e -> logger.error("Failed to process message, correlationId: {}, error: {}",
                        extractCorrelationId(message), e.getMessage()));
    }

    private String extractCorrelationId(Message message) {
        return message.messageAttributes().getOrDefault("X-Correlation-Id",
                        software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
                                .stringValue(UUID.randomUUID().toString())
                                .build())
                .stringValue();
    }
}