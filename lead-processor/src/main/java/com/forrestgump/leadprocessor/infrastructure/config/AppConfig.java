package com.forrestgump.leadprocessor.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.kms.KmsAsyncClient;

@Configuration
@EnableConfigurationProperties(AwsConfig.class)
@ComponentScan(basePackages = "com.forrestgump.leadprocessor.infrastructure")
public class AppConfig {

    private final AwsConfig awsConfig;

    public AppConfig(AwsConfig awsConfig) {
        this.awsConfig = awsConfig;
    }

    @Bean
    public DynamoDbAsyncClient dynamoDbClient() {
        return DynamoDbAsyncClient.builder()
                .region(Region.of(awsConfig.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public DynamoDbEnhancedAsyncClient dynamoDbEnhancedClient(DynamoDbAsyncClient dynamoDbClient) {
        return DynamoDbEnhancedAsyncClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }

    @Bean
    public KmsAsyncClient kmsClient() {
        return KmsAsyncClient.builder()
                .region(Region.of(awsConfig.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}