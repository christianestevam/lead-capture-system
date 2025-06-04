package com.forrestgump.leadprocessor.infrastructure.persistence;

import com.forrestgump.leadprocessor.domain.model.Lead;
import com.forrestgump.leadprocessor.infrastructure.config.AwsConfig;
import com.forrestgump.leadprocessor.infrastructure.exception.InfrastructureException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.kms.KmsAsyncClient;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.utils.BinaryUtils;

@Component
public class DynamoLeadRepository {

    private static final Logger logger = LoggerFactory.getLogger(DynamoLeadRepository.class);
    private final DynamoDbAsyncTable<Lead> leadTable;
    private final KmsAsyncClient kmsClient;
    private final String kmsKeyAlias;
    private final CircuitBreaker dynamoCircuitBreaker;
    private final Retry dynamoRetry;

    public DynamoLeadRepository(DynamoDbEnhancedAsyncClient enhancedClient, AwsConfig awsConfig,
                                KmsAsyncClient kmsClient, CircuitBreaker dynamoCircuitBreaker, Retry dynamoRetry) {
        this.leadTable = enhancedClient.table(awsConfig.dynamodb().tableName(), TableSchema.fromBean(Lead.class));
        this.kmsClient = kmsClient;
        this.kmsKeyAlias = awsConfig.kms().keyAlias();
        this.dynamoCircuitBreaker = dynamoCircuitBreaker;
        this.dynamoRetry = dynamoRetry;
    }

    public Mono<Void> save(Lead lead) {
        return Mono.fromFuture(kmsClient.encrypt(EncryptRequest.builder()
                        .keyId(kmsKeyAlias)
                        .plaintext(SdkBytes.fromUtf8String(lead.getCpf()))
                        .build()))
                .map(response -> BinaryUtils.toBase64(response.ciphertextBlob().asByteArray()))
                .map(encryptedCpf -> new Lead(
                        lead.getLeadId(),
                        lead.getCpf(),
                        encryptedCpf,
                        lead.getSalt(),
                        lead.getName(),
                        lead.getPhone(),
                        lead.getEmail(),
                        lead.getCreatedAt()))
                .flatMap(encryptedLead -> Mono.fromFuture(leadTable.putItem(encryptedLead)))
                .doOnSuccess(v -> logger.info("Lead saved successfully to DynamoDB, leadId: {}", lead.getLeadId()))
                .doOnError(e -> logger.error("Failed to save lead to DynamoDB, leadId: {}, error: {}", lead.getLeadId(), e.getMessage()))
                .onErrorMap(e -> new InfrastructureException("Failed to save to DynamoDB", e))
                .transformDeferred(CircuitBreakerOperator.of(dynamoCircuitBreaker))
                .transformDeferred(RetryOperator.of(dynamoRetry))
                .then();
    }
}