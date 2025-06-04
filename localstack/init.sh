#!/bin/sh

set -e

awslocal sqs create-queue --queue-name lead-queue
awslocal sqs create-queue --queue-name lead-queue-dlq

awslocal sqs set-queue-attributes \
  --queue-url http://localhost:4566/000000000000/lead-queue \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:lead-queue-dlq\",\"maxReceiveCount\":\"3\"}"}'

awslocal dynamodb create-table \
  --table-name Leads \
  --attribute-definitions AttributeName=leadId,AttributeType=S \
  --key-schema AttributeName=leadId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST

key_id=$(awslocal kms create-key --description 'Local KMS key for lead-capture' \
    --query 'KeyMetadata.KeyId' --output text)

awslocal kms create-alias --alias-name alias/lead-capture-key --target-key-id "$key_id"
