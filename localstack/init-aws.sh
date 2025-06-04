#!/bin/bash

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1

log_error() {
    echo "ERROR: $1" >&2
    exit 1
}

echo "Creating SQS queue: lead-queue"
awslocal sqs create-queue --queue-name lead-queue || log_error "Failed to create lead-queue"

echo "Creating SQS queue: lead-queue-dlq"
awslocal sqs create-queue --queue-name lead-queue-dlq || log_error "Failed to create lead-queue-dlq"

echo "Setting redrive policy for lead-queue"
awslocal sqs set-queue-attributes --queue-url http://localhost:4566/000000000000/lead-queue --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:lead-queue-dlq\",\"maxReceiveCount\":\"3\"}"}' || log_error "Failed to set redrive policy for lead-queue"

echo "Creating DynamoDB table: Leads"
awslocal dynamodb create-table --table-name Leads --attribute-definitions AttributeName=leadId,AttributeType=S --key-schema AttributeName=leadId,KeyType=HASH --billing-mode PAY_PER_REQUEST || log_error "Failed to create Leads table"

echo "Creating KMS key"
awslocal kms create-key --description "Local KMS key for lead-capture" || log_error "Failed to create KMS key"

key_id=$(awslocal kms list-keys --query 'Keys[0].KeyId' --output text)
echo "Creating KMS alias: alias/lead-capture-key"
awslocal kms create-alias --alias-name alias/lead-capture-key --target-key-id "$key_id" || log_error "Failed to create KMS alias"

echo "LocalStack initialization completed successfully"