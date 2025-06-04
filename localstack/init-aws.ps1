# init-aws.ps1
$env:AWS_ACCESS_KEY_ID="test"
$env:AWS_SECRET_ACCESS_KEY="test"
$env:AWS_DEFAULT_REGION="us-east-1"

function Log-Error {
    param($Message)
    Write-Host "ERROR: $Message" -ForegroundColor Red
}

try {
    Write-Host "Creating SQS queue: lead-queue"
    awslocal sqs create-queue --queue-name lead-queue
    if ($LASTEXITCODE -ne 0) { throw "Failed to create lead-queue" }

    Write-Host "Creating SQS queue: lead-queue-dlq"
    awslocal sqs create-queue --queue-name lead-queue-dlq
    if ($LASTEXITCODE -ne 0) { throw "Failed to create lead-queue-dlq" }

    Write-Host "Setting redrive policy for lead-queue"
    awslocal sqs set-queue-attributes --queue-url http://localhost:4566/000000000000/lead-queue --attributes '{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"arn:aws:sqs:us-east-1:000000000000:lead-queue-dlq\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}'
    if ($LASTEXITCODE -ne 0) { throw "Failed to set redrive policy for lead-queue" }

    Write-Host "Creating DynamoDB table: Leads"
    awslocal dynamodb create-table --table-name Leads --attribute-definitions AttributeName=leadId,AttributeType=S --key-schema AttributeName=leadId,KeyType=HASH --billing-mode PAY_PER_REQUEST
    if ($LASTEXITCODE -ne 0) { throw "Failed to create Leads table" }

    Write-Host "Creating KMS key"
    awslocal kms create-key --description "Local KMS key for lead-capture"
    if ($LASTEXITCODE -ne 0) { throw "Failed to create KMS key" }

    $keyId=$(awslocal kms list-keys --query 'Keys[0].KeyId' --output text)
    Write-Host "Creating KMS alias: alias/lead-capture-key"
    awslocal kms create-alias --alias-name alias/lead-capture-key --target-key-id $keyId
    if ($LASTEXITCODE -ne 0) { throw "Failed to create KMS alias" }

    Write-Host "LocalStack initialization completed successfully" -ForegroundColor Green
} catch {
    Log-Error $_.Exception.Message
    exit 1
}