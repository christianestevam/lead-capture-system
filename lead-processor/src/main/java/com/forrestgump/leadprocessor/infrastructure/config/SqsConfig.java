package com.forrestgump.leadprocessor.infrastructure.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;
import software.amazon.awssdk.services.sqs.batchmanager.SqsAsyncBatchManager;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class SqsConfig {

    private final AwsConfig awsConfig;

    @Value("${aws.endpoint-url:#{null}}")
    private String endpointUrl;

    @Value("${aws.access-key-id:#{null}}")
    private String accessKeyId;

    @Value("${aws.secret-access-key:#{null}}")
    private String secretAccessKey;

    public SqsConfig(AwsConfig awsConfig) {
        this.awsConfig = awsConfig;
    }

    @Bean
    public SqsAsyncClient sqsAsyncClient() {
        SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                .region(Region.of(awsConfig.region()));

        if (endpointUrl != null) {
            builder.endpointOverride(URI.create(endpointUrl));
        }

        if (accessKeyId != null && secretAccessKey != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        }

        return builder.build();
    }

    @Bean
    public SqsAsyncBatchManager sqsAsyncBatchManager(SqsAsyncClient sqsAsyncClient) {
        ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(2);
        return SqsAsyncBatchManager.builder()
                .client(sqsAsyncClient)
                .scheduledExecutor(scheduledExecutor)
                .overrideConfiguration(builder -> builder
                        .maxBatchSize(10)
                        .sendRequestFrequency(Duration.ofSeconds(1))
                        .receiveMessageMinWaitDuration(Duration.ofSeconds(10))
                        .receiveMessageVisibilityTimeout(Duration.ofSeconds(20)))
                .build();
    }

    @Bean(name = "sqsCircuitBreaker")
    public CircuitBreaker sqsCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(5)
                .build();
        return CircuitBreaker.of("sqsCircuitBreaker", config);
    }

    @Bean(name = "sqsRetry")
    public Retry sqsRetry() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(1))
                .build();
        return Retry.of("sqsRetry", config);
    }

    @Bean(name = "dynamoCircuitBreaker")
    public CircuitBreaker dynamoCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .permittedNumberOfCallsInHalfOpenState(5)
                .build();
        return CircuitBreaker.of("dynamoCircuitBreaker", config);
    }

    @Bean(name = "dynamoRetry")
    public Retry dynamoRetry() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(500))
                .build();
        return Retry.of("dynamoRetry", config);
    }
}