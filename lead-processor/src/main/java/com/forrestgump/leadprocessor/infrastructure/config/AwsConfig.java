package com.forrestgump.leadprocessor.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "aws")
@Validated
public record AwsConfig(
         String region,
        Sqs sqs,
        Dynamodb dynamodb,
        Kms kms
) {
    public record Sqs(
             String queueName,
             String dlqName
    ) {}

    public record Dynamodb(
             String tableName
    ) {}

    public record Kms(
             String keyAlias
    ) {}
}