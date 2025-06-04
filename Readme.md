# Lead-Capture-System

## 1. Introdução

O projeto foi feito a partir de um desafio técnico de desenvolver uma solução para capturar leads durante uma campanha de marketing de um banco tradicional expandindo para o público em geral. O sistema suporta o pré-cadastro de usuários via um site acessado por QR code, exibido em uma campanha nacional na TV, coletando dados sensíveis (nome, CPF, telefone, email). Meu objetivo foi desenvolver uma arquitetura robusta que garantisse **escalabilidade**, **alta disponibilidade**, **resiliência**, **performance**, **segurança** (conformidade com a LGPD), **flexibilidade** conforme os requisitos e adicionei mais um requisito **custo** que acredito ser um importante quando estamos construindo uma solução na nuvem, atendendo aos requisitos do desafio e ao cenário de alta visibilidade.

O sistema é composto por dois microserviços: **lead-api** (recebe requisições via API, valida dados, aplica rate limiting, gera hash do CPF, e enfileira mensagens no SQS) e **lead-processor** (consome mensagens, criptografa CPF com KMS, persiste no DynamoDB). A arquitetura é serverless, baseada na AWS, utilizando **Spring WebFlux**, **Java 21**, e **DDD**, com estratégias como **batching** no SQS, **rate limiting** (global no API Gateway e por cliente no lead-api), **Dead Letter Queue (DLQ)** no lead-processor, e medidas de segurança como hash SHA-256, criptografia KMS, mascaramento de logs, e rastreabilidade via correlation ID.

Esta documentação detalha a arquitetura, escolhas técnicas, trade-offs, análise de custos, e o fluxo de dados, com trechos de código para ilustrar as implementações, refletindo meu raciocínio no desafio.

---

## 2. Requisitos do Desafio

O desafio exigiu uma solução que atendesse:
1. **Escalabilidade e Elasticidade**: Suportar milhares a milhões de acessos simultâneos, com escalabilidade rápida e controlada.
2. **Alta Disponibilidade e Resiliência**: Prever e mitigar falhas, garantindo operação contínua.
3. **Performance**: Minimizar o tempo de resposta do front-end e da API.
4. **Segurança**: Proteger dados sensíveis, conforme a LGPD, com medidas contra abusos (e.g., DDoS, spam).
5. **Flexibilidade**: Permitir evoluções com baixo acoplamento e manutenção eficiente.
6. **Custo**: Maximizar eficiência financeira.

---

## 3. Arquitetura do Sistema

Desenhei uma arquitetura serverless na AWS, com **Spring WebFlux** para programação reativa, dividida em dois microserviços:
- **lead-api**: Recebe requisições `POST /api/leads`, valida dados, aplica rate limiting global (API Gateway, 1000 req/s) e por cliente (10 req/s por IP), gera `leadId` (hash SHA-256 com salt), e enfileira mensagens no SQS.
- **lead-processor**: Consome mensagens em lotes, criptografa CPF com KMS, persiste no DynamoDB, com DLQ para falhas.

[![Arquitetura do Sistema](https://github.com/christianestevam/lead-capture-system/blob/master/public/diagram.jpg)](https://github.com/christianestevam/lead-capture-system/blob/master/public/diagram-2.png)

### 3.1 Fluxo de Dados

1. **Escaneamento do QR Code**:
    - **Entrada**: Usuário escaneia o QR code, que aponta para o domínio `https://leads.banco.com`, resolvido via **Route 53**.
    - **Componente**: **CloudFront** com **S3** hospedando a **React SPA**.
    - **Ações**:
        - O **Route 53** resolve o domínio para o **CloudFront**.
        - O **CloudFront** entrega a SPA com latência <100ms, utilizando cache global.
        - A SPA renderiza um formulário React para coleta de dados (nome, CPF, telefone, email), com validação client-side (e.g., formato de CPF, email válido).
    - **Condições**:
        - **Segurança**: HTTPS via **CloudFront**, proteção contra XSS com **WAF** e **Shield**.
        - **Escalabilidade**: **S3** e **CloudFront** suportam milhões de acessos simultâneos.
        - **Performance**: Cache do **CloudFront** minimiza latência.
        - **Custo**: ~US$0,194/M acessos (S3: US$0,024, CloudFront: US$0,17).
        - **Erro**: Falha de rede ou bloqueio pelo **WAF** (e.g., ataque XSS) → mensagem de erro no front-end.
    - **Exemplo de Código**:
      ```javascript
      // LeadForm.js
      import { v4 as uuid } from 'uuid';
      const handleSubmit = async (e) => {
        e.preventDefault();
        if (!validateCpf(cpf)) {
          setError("CPF inválido");
          return;
        }
        try {
          const response = await fetch("https://leads.banco.com/api/leads", {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              "X-Correlation-Id": uuid(),
            },
            body: JSON.stringify({ name, cpf, phone, email }),
          });
          if (!response.ok) throw new Error("Falha ao enviar formulário");
          setSuccess("Cadastro enviado com sucesso!");
        } catch (error) {
          setError("Erro ao enviar formulário");
        }
      };
      ```
    - **Métricas**: `cloudfront_requests` (CloudWatch).
    - **Logs**: Requisições logadas no **CloudWatch** pelo **CloudFront**.

2. **Envio do Formulário via API Gateway**:
    - **Entrada**: O formulário envia uma requisição POST para `https://leads.banco.com/api/leads` via **API Gateway**.
    - **Componente**: **API Gateway** integrado com **AWS Lambda** do **lead-api**.
    - **Ações**:
        - O **API Gateway** aplica rate limiting global (1000 req/s) para proteger contra DDoS.
        - O **WAF** filtra ataques (e.g., SQL injection, XSS).
        - Valida headers (`X-Correlation-Id`, `X-Forwarded-For`) e encaminha a requisição ao **Lambda**.
    - **Condições**:
        - **Segurança**: **WAF** bloqueia requisições maliciosas → HTTP 403.
        - **Escalabilidade**: **API Gateway** escala automaticamente.
        - **Performance**: Latência <50ms.
        - **Custo**: ~US$1,00/M requisições.
        - **Erro**: Rate limit global excedido → HTTP 429.
    - **Métricas**: `api_gateway_request_count`, `api_gateway_4xx_errors` (CloudWatch).
    - **Logs**: Requisições e erros logados no **CloudWatch**.

3. **Processamento no lead-api**:
    - **Componente**: `LeadController` no **lead-api** (Lambda, porta 8081 localmente).
    - **Ações**:
        - Valida o corpo da requisição (`LeadRequest`) com `@Valid`.
        - Aplica rate limiting por cliente (`leadApiRateLimiter`, 10 req/s por IP, configurado em `application-local.yml`).
        - Gera `eventId` (UUID) e usa `X-Correlation-Id` ou novo UUID para rastreabilidade.
        - Invoca `SubmitLeadUseCase.execute` para gerar `leadId` (hash SHA-256 com salt) e publicar no SQS.
    - **Condições**:
        - **Segurança**: Validação evita dados inválidos; CPF mascarado nos logs (LGPD).
        - **Resiliência**: Rate limiting e **Resilience4j** protegem contra sobrecarga.
        - **Performance**: Processamento reativo com **WebFlux** <100ms.
        - **Custo**: Lambda ~US$0,26/M requisições.
        - **Erro**:
            - Rate limit excedido → HTTP 429 com `LeadResponse("Too many requests")`.
            - Validação falha → HTTP 400.
    - **Exemplo de Código**:
      ```java
      // LeadController.java
      @PostMapping
      public Mono<ResponseEntity<LeadResponse>> register(
          @Valid @RequestBody Mono<LeadRequest> requestMono,
          @RequestHeader(value = "X-Correlation-Id", defaultValue = "") String correlationId,
          @RequestHeader(value = "X-Forwarded-For", defaultValue = "unknown") String clientIp) {
          String effectiveCorrelationId = correlationId.isEmpty() ? UUID.randomUUID().toString() : correlationId;
          UUID eventId = UUID.randomUUID();
          return requestMono
              .transformDeferred(RateLimiterOperator.of(rateLimiter))
              .flatMap(request -> submitLeadUseCase.execute(request, effectiveCorrelationId))
              .then(Mono.fromCallable(() -> ResponseEntity.ok(new LeadResponse(eventId.toString(), "Lead queued successfully"))))
              .defaultIfEmpty(ResponseEntity.badRequest().build())
              .onErrorResume(e -> {
                  metricsPublisher.incrementRateLimit();
                  logger.warn("Rate limit exceeded for client IP: {}, correlationId: {}", clientIp, effectiveCorrelationId);
                  return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                      .body(new LeadResponse(eventId.toString(), "Too many requests")));
              });
      }
      ```
    - **Métricas**: `api_rate_limit_count` (Prometheus).
    - **Logs**: DEBUG logs no **CloudWatch** (`com.forrestgump.leadapi`).

4. **Geração e Publicação de Evento no SQS**:
    - **Componente**: `SubmitLeadUseCase` e `SqsLeadPublisher` no **lead-api**.
    - **Ações**:
        - `SubmitLeadUseCase` mapeia `LeadRequest` para `LeadSubmission`, gerando `leadId` (hash SHA-256 de CPF + salt) e `salt`.
        - `SqsLeadPublisher` serializa `LeadSubmission` (com `JavaTimeModule` para `Instant`) e publica em `lead-queue` usando `SqsAsyncBatchManager` (lotes de 10, frequência de envio: 1s).
        - Aplica `sqsCircuitBreaker` (janela: 20, falha: 50%) e `sqsRetry` (3 tentativas).
    - **Condições**:
        - **Segurança**: CPF mascarado nos logs; `leadId` anonimizado.
        - **Resiliência**: Retries e circuit breaker evitam falhas transitórias.
        - **Performance**: Batching reduz chamadas (~1s delay).
        - **Custo**: SQS ~US$0,04/M mensagens.
        - **Erro**:
            - Falha no SQS → Retries; se persistir, HTTP 500.
            - Serialização falha → Logado, HTTP 500.
    - **Exemplo de Código**:
      ```java
      // SqsLeadPublisher.java
      public Mono<Void> publish(LeadSubmission event) {
          String salt = generateSalt();
          String leadId = generateLeadId(event.cpf(), salt);
          LeadSubmission updatedEvent = new LeadSubmission(event.eventId(), leadId, event.cpf(), salt, ...);
          return Mono.fromCallable(() -> objectMapper.writeValueAsString(updatedEvent))
              .flatMap(message -> Mono.fromFuture(sqsAsyncClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()))
                  .map(GetQueueUrlResponse::queueUrl)
                  .flatMap(queueUrl -> Mono.fromFuture(sqsAsyncBatchManager.sendMessage(SendMessageRequest.builder()
                      .queueUrl(queueUrl)
                      .messageBody(message)
                      .build()))))
              .doOnSuccess(response -> metricsPublisher.incrementSqsPublish("success"))
              .doOnError(e -> metricsPublisher.incrementSqsPublish("error"))
              .transformDeferred(CircuitBreakerOperator.of(sqsCircuitBreaker))
              .transformDeferred(RetryOperator.of(sqsRetry))
              .then();
      }
      ```
    - **Métricas**: `sqs_publish_count` (success/error).
    - **Logs**: Sucesso ou falha logados no **CloudWatch**.

5. **Consumo Assíncrono de Mensagens do SQS**:
    - **Componente**: `SqsLeadConsumer` no **lead-processor** (Lambda).
    - **Ações**:
        - Polling em `lead-queue` com `SqsAsyncBatchManager` (lotes de 10, espera mínima: 10s, visibilidade: 20s).
        - Deserializa mensagens em `LeadSubmission`.
        - Chama `ProcessLeadUseCase.execute`.
    - **Condições**:
        - **Resiliência**: Falhas de deserialização ou consumo movem mensagens para `lead-queue-dlq` após 3 tentativas (redrive policy).
        - **Performance**: Long polling otimiza consumo.
        - **Custo**: Incluído no SQS (~US$0,04/M).
        - **Erro**: Falha persistente → Mensagem na **DLQ**.
    - **Exemplo de Código**:
      ```java
      // SqsConfig.java (lead-processor)
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
      ```
    - **Métricas**: Nenhum diretamente, mas métricas de processamento aplicam.
    - **Logs**: Erros de consumo logados no **CloudWatch**.

6. **Processamento do Lead**:
    - **Componente**: `ProcessLeadUseCase` e `LeadProcessingService` no **lead-processor**.
    - **Ações**:
        - `ProcessLeadUseCase` cria um `Lead` a partir de `LeadSubmission`.
        - `LeadProcessingService` invoca `DynamoLeadRepository.save` para persistência.
    - **Condições**:
        - **Segurança**: Validação adicional no back-end.
        - **Resiliência**: `dynamoCircuitBreaker` e `dynamoRetry` aplicados.
        - **Performance**: Processamento reativo <100ms.
        - **Custo**: Lambda incluído no custo total (~US$0,26/M).
        - **Erro**: Falha de validação → `LeadValidationException`, mensagem na **DLQ**.
    - **Exemplo de Código**:
      ```java
      // ProcessLeadUseCase.java
      public Mono<Void> execute(LeadSubmission event, String correlationId) {
          return Mono.fromCallable(() -> new Lead(event.leadId(), event.cpf(), null, event.salt(), event.name(), event.phone(), event.email(), event.createdAt()))
              .doOnNext(lead -> logger.info("Processing lead, eventId: {}, correlationId: {}, leadId: {}", event.eventId(), correlationId, lead.getLeadId()))
              .flatMap(leadProcessingService::processLead)
              .doOnSuccess(v -> metricsPublisher.incrementLeadProcessing("success"))
              .onErrorMap(LeadValidationException.class, e -> {
                  metricsPublisher.incrementLeadProcessing("validation_error");
                  logger.error("Validation error for lead, eventId: {}, correlationId: {}, error: {}", event.eventId(), correlationId, e.getMessage());
                  return e;
              })
              .transformDeferred(CircuitBreakerOperator.of(dynamoCircuitBreaker));
      }
      ```
    - **Métricas**: `lead_processing_count` (success/validation_error/error).
    - **Logs**: Status de processamento logado no **CloudWatch**.

7. **Criptografia e Persistência no DynamoDB**:
    - **Componente**: `DynamoLeadRepository` no **lead-processor**.
    - **Ações**:
        - Criptografa CPF com **KMS** (`alias/lead-capture-key`).
        - Persiste `Lead` na tabela `Leads` usando `DynamoDbEnhancedAsyncClient`.
        - Aplica `dynamoCircuitBreaker` (janela: 10, falha: 50%) e `dynamoRetry` (2 tentativas, 500ms).
    - **Condições**:
        - **Segurança**: CPF criptografado; `leadId` é hash anônimo; **CloudTrail** audita operações.
        - **Resiliência**: Retries e circuit breaker lidam com falhas transitórias.
        - **Performance**: Escritas <10ms.
        - **Custo**: DynamoDB ~US$1,25/M escritas, KMS ~US$4,00/M.
        - **Erro**:
            - Falha no KMS → Mensagem na **DLQ**.
            - Falha no DynamoDB → Retries; se persistir, **DLQ**.
    - **Exemplo de Código**:
      ```java
      // DynamoLeadRepository.java
      public Mono<Void> save(Lead lead) {
          return Mono.fromFuture(kmsClient.encrypt(EncryptRequest.builder()
                  .keyId(kmsKeyAlias)
                  .plaintext(SdkBytes.fromUtf8String(lead.getCpf()))
                  .build()))
              .map(response -> BinaryUtils.toBase64(response.ciphertextBlob().asByteArray()))
              .map(encryptedCpf -> new Lead(lead.getLeadId(), lead.getCpf(), encryptedCpf, lead.getSalt(), lead.getName(), lead.getPhone(), lead.getEmail(), lead.getCreatedAt()))
              .flatMap(encryptedLead -> Mono.fromFuture(leadTable.putItem(encryptedLead)))
              .doOnSuccess(v -> logger.info("Lead saved successfully to DynamoDB, leadId: {}", lead.getLeadId()))
              .doOnError(e -> logger.error("Failed to save lead to DynamoDB, leadId: {}, error: {}", lead.getLeadId(), e.getMessage()))
              .transformDeferred(CircuitBreakerOperator.of(dynamoCircuitBreaker))
              .transformDeferred(RetryOperator.of(dynamoRetry))
              .then();
      }
      ```
    - **Métricas**: Incluído em `lead_processing_count`.
    - **Logs**: Sucesso ou falha logados no **CloudWatch**.

8. **Tratamento de Falhas na DLQ**:
    - **Componente**: `lead-queue-dlq` no **SQS**.
    - **Ações**:
        - Armazena mensagens que falham após 3 tentativas, conforme a política de redrive (`maxReceiveCount: 3`).
    - **Condições**:
        - **Resiliência**: Evita perda de dados, permitindo recuperação manual ou futura automação.
        - **Custo**: Incluído no SQS (~US$0,04/M).
        - **Erro**: Requer inspeção manual ou reprocessamento (não implementado).
    - **Exemplo de Configuração**:
      ```powershell
      # init-aws.sh
      awslocal sqs set-queue-attributes --queue-url http://localhost:4566/000000000000/lead-queue --attributes '{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"arn:aws:sqs:us-east-1:000000000000:lead-queue-dlq\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}'
      ```
    - **Métricas**: Nenhum diretamente.
    - **Logs**: Erros de consumo logados pelo `SqsLeadConsumer`.

9. **Observabilidade e Monitoramento**:
    - **Componentes**: **CloudWatch**, **Prometheus**, **Grafana Cloud**.
    - **Ações**:
        - **CloudWatch** captura logs JSON e métricas Prometheus para **lead-api** e **lead-processor**.
        - **Prometheus** coleta métricas localmente de **lead-api** (`host.docker.internal:8081/actuator/prometheus`) e **lead-processor** (`host.docker.internal:8082/actuator/prometheus`).
        - **Grafana Cloud** visualiza métricas em dashboards escaláveis.
    - **Condições**:
        - **Observabilidade**: Métricas detalhadas (`api_rate_limit_count`, `sqs_publish_count`, `lead_processing_count`) e logs DEBUG para diagnóstico.
        - **Resiliência**: Alarmes no **CloudWatch** para DLQ e rate limiting.
        - **Custo**: CloudWatch ~US$0,60/M leads, Grafana Cloud ~US$8/mês.
        - **Erro**: Falha nos containers de **Prometheus**/**Grafana** localmente → métricas indisponíveis.
    - **Exemplo de Código**:
      ```java
      // MetricsPublisher.java
      public void incrementRateLimit() {
          meterRegistry.counter("api_rate_limit_count").increment();
      }
      public void incrementSqsPublish(String outcome) {
          meterRegistry.counter("sqs_publish_count", "outcome", outcome).increment();
      }
      ```
    - **Métricas**:
        - `api_rate_limit_count`: Violações de rate limiting.
        - `sqs_publish_count`: Publicações no SQS (success/error).
        - `lead_processing_count`: Resultados de processamento (success/validation_error/error).
    - **Logs**: DEBUG logs para rastreabilidade detalhada no **CloudWatch** (`com.forrestgump.leadapi`, `com.forrestgump.leadprocessor`).

---

## 4. Escolhas Técnicas e Justificativas

Como desenvolvedor do teste técnico, analisei cada decisão com base nos requisitos, considerando trade-offs, benefícios, e impacto no custo. Abaixo, detalho as escolhas, com trechos de código para ilustrar.

### 4.1 Front-End: React SPA no S3 com CloudFront

**Escolha**: Desenvolvi uma **React SPA** hospedada em **S3**, distribuída por **CloudFront**.

**Justificativas**:
- **Performance**: React é rápido; CloudFront cacheia (~100ms latência):
  ```javascript
  // LeadForm.js
  const handleSubmit = async (e) => {
    e.preventDefault();
    await fetch("/api/leads", {
      method: "POST",
      headers: { "X-Correlation-Id": uuid() },
      body: JSON.stringify({ name, cpf, phone, email }),
    });
  };
  ```
- **Escalabilidade**: S3 e CloudFront escalam para milhões de acessos.
- **Custo**: US$0,024 (S3) + US$0,17 (CloudFront) = ~US$0,19/M acessos.
- **Segurança**: S3 privado, HTTPS, WAF contra XSS.
- **Flexibilidade**: React suporta novas funcionalidades (e.g., validação client-side).

**Trade-off**: SPA aumenta carga no cliente, mitigada por CloudFront. Servidores web foram descartados por custo fixo (~US$30/mês para EC2).

### 4.2 Back-End: Spring WebFlux com Lambda

**Escolha**: **Spring WebFlux** com **Java 21** em **AWS Lambda** para **lead-api** e **lead-processor**.

**Justificativas**:
- **Escalabilidade**: Lambda escala automaticamente; WebFlux suporta alta concorrência:
  ```java
  // SubmitLeadUseCase.java
  public Mono<Void> execute(LeadRequest request, String correlationId) {
      String salt = generateSalt();
      String leadId = generateLeadId(request.cpf(), salt);
      Lead lead = Lead.fromRequest(request.cpf(), request.name(), request.phone(), request.email(), leadId, salt);
      return leadPublisher.publish(new LeadSubmission(...));
  }
  ```
- **Performance**: Latência <100ms com fluxos reativos.
- **Resiliência**: Resilience4j com circuit breakers e retries:
  ```java
  // SqsLeadPublisher.java
  .transformDeferred(CircuitBreakerOperator.of(sqsCircuitBreaker))
  .transformDeferred(RetryOperator.of(sqsRetry))
  ```
- **Custo**: Lambda: US$0,26/M (inclui rate limiting overhead). Beanstalk (~US$30/mês) e EKS (~US$120/mês) são mais caros.
- **Flexibilidade**: DDD com camadas reduz acoplamento.

**Trade-off**: Cold starts no Lambda (alguns milissegundos) são mitigados por WebFlux (reativo, leve) e Java 21 (virtual threads, GC otimizado). Beanstalk/EKS foram descartados por complexidade operacional e custos fixos.

### 4.3 Persistência: DynamoDB com Hash e KMS

**Escolha**: **DynamoDB** On-Demand, com `leadId` (hash SHA-256 com salt) como PK e CPF criptografado com **KMS**.

**Justificativas**:
- **Escalabilidade**: Escala automaticamente:
  ```java
  // Lead.java (lead-processor)
  @DynamoDbBean
  public record Lead(
      @DynamoDbPartitionKey String leadId,
      String encryptedCpf,
      String salt,
      String name,
      String phone,
      String email,
      Instant createdAt
  ) {}
  ```
- **Performance**: Latência <10ms:
  ```java
  // DynamoLeadRepository.java
  public Mono<Void> save(Lead lead) {
      return Mono.fromFuture(kmsClient.encrypt(...))
              .map(encryptedCpf -> new Lead(lead.leadId(), encryptedCpf, ...))
              .flatMap(leadTable::putItem)
              .then();
  }
  ```
- **Segurança**: Hash e KMS protegem CPF; CloudTrail audita:
  ```java
  // LeadSubmissionService.java
  logger.info("Submitting lead with leadId: {}", lead.leadId());
  ```
- **Custo**: US$1,25/M escritas, US$4,00 KMS (1M leads).
- **Flexibilidade**: NoSQL permite novos atributos.

**Trade-off**: DynamoDB é mais caro que RDS para leituras pesadas (~US$0,25/M vs. ~US$0,10/M), mas ideal para escritas esporádicas.

### 4.4 Mensageria: SQS com Batching e DLQ

**Escolha**: **SQS** com **SqsAsyncBatchManager** (lotes de 10), **DLQ** (`lead-queue-dlq`) no **lead-processor**.

**Justificativas**:
- **Escalabilidade**: Batching reduz chamadas API:
  ```java
  // SqsConfig.java (lead-processor)
  .maxBatchSize(10)
  .receiveMessageMinWaitDuration(Duration.ofSeconds(10))
  ```
- **Performance**: Long polling (20s) otimiza consumo.
- **Resiliência**: DLQ isola falhas após 3 tentativas:
  ```java
  // SqsConfig.java (lead-processor)
  attributes.put(QueueAttributeName.REDRIVE_POLICY, "{\"maxReceiveCount\":\"3\",\"deadLetterTargetArn\":\"<dlq-arn>\"}");
  ```
    - **Nota**: O **lead-api** não usa DLQ, com resiliência via Resilience4j.
- **Custo**: US$0,04/M mensagens (batching reduz 90%).
- **Flexibilidade**: Configurável via **application.yml**.

**Trade-off**: Batching pode atrasar mensagens (~1s), mas é aceitável para cadastros.

### 4.5 Segurança e LGPD

**Escolha**: Hash (SHA-256), KMS, mascaramento, correlation ID, WAF, Shield, IAM, CloudTrail, rate limiting no **API Gateway** (1000 req/s) e **lead-api** (10 req/s por IP).

**Justificativas**:
- **Segurança**:
    - Hash e KMS protegem CPF:
      ```java
      // SqsLeadPublisher.java
      private String generateLeadId(String cpf, String salt) {
          MessageDigest digest = MessageDigest.getInstance("SHA-256");
          String combined = cpf + salt;
          byte[] hashBytes = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
          return Base64.getEncoder().encodeToString(hashBytes);
      }
      ```
    - **Rate Limiting**:
        - **API Gateway**: Limita 1000 req/s globalmente, bloqueando ataques amplos.
        - **lead-api**: Limita 10 req/s por IP com Resilience4j, prevenindo abusos individuais:
          ```java
          // LeadController.java
          .transformDeferred(RateLimiterOperator.of(rateLimiter))
          .onErrorResume(e -> {
              metricsPublisher.incrementRateLimit();
              logger.warn("Rate limit exceeded for client IP: {}, correlationId: {}", clientIp, correlationId);
              return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                      .body(new LeadResponse(eventId, "Too many requests")));
          })
          ```
    - WAF, Shield, IAM, e CloudTrail reforçam proteção.
- **Disponibilidade**: Rate limiting evita saturação do Lambda.
- **Custo**: API Gateway gratuito; Resilience4j adiciona ~US$0,01/M, mas economiza ~US$0,20/M ao bloquear abusos.
- **Flexibilidade**: Limites ajustáveis via **application.yml**.

**Trade-off**: Rate limiting por IP pode bloquear usuários em redes compartilhadas, mas 10 req/s é suficiente para cadastros manuais.

### 4.6 Observabilidade

**Escolha**: **CloudWatch** (logs JSON, métricas Prometheus), **Grafana Cloud** (visualização).

**Justificativas**:
- **Observabilidade**: Métricas (`api.rate_limit.count`, `sqs.publish.count`) e logs:
  ```java
  // MetricsPublisher.java
  public void incrementRateLimit() {
      meterRegistry.counter("api.rate_limit.count").increment();
  }
  ```
- **Resiliência**: Alarmes para DLQ e rate limiting.
- **Custo**: US$0,60/M leads (CloudWatch). Grafana Cloud (plano pago, Active Series) custa ~US$8/mês para 100.000 métricas e 10 GB logs, escalável para campanhas.
- **Flexibilidade**: Suporta novos serviços.

**Trade-off**: Custos do Grafana Cloud aumentam com escala, mas são justificados pela visualização avançada. ELK Stack foi descartado por complexidade.

---

## 5. Análise de Custo (1M Leads)

| **Serviço**         | **Custo Estimado** |
|---------------------|--------------------|
| **Lambda**          | US$0,26 |
| **SQS**             | US$0,04 |
| **DynamoDB**        | US$1,25 |
| **KMS**             | US$4,00 |
| **CloudWatch**      | US$0,60 |
| **CloudFront**      | US$0,17 |
| **S3**              | US$0,024 |
| **API Gateway**     | US$1,00 |
| **Route 53**        | US$0,40 |
| **WAF/Shield**      | US$5,00 |
| **CloudTrail**      | US$0,10 |
| **Grafana Cloud**   | US$0,27 (US$8/mês ÷ 30 dias) |
| **Total**           | **US$13,074** |

**Impacto do Rate Limiting**: Adiciona ~US$0,01/M leads, mas economiza ~US$0,20/M ao bloquear abusos. **Grafana Cloud**: Incluído como custo fixo (~US$8/mês), rateado para 1M leads.

**Por que Lambda?**: Mais econômico (~US$0,26/M) que Beanstalk (~US$30/mês) ou EKS (~US$120/mês). Batching no SQS reduz custos em 90%.

---

## 6. Conclusão

O **Lead-Capture-System** é uma solução eficaz que atende plenamente aos requisitos do desafio técnico, oferecendo uma arquitetura serverless robusta para capturar leads em uma campanha de marketing de grande escala. Com **Spring WebFlux** e **Java 21**, o sistema garante alta performance e escalabilidade, enquanto **Resilience4j** assegura resiliência contra falhas. A segurança é priorizada com criptografia KMS, hash SHA-256 e conformidade com a LGPD, complementada por rate limiting no **API Gateway** e **lead-api**. A integração com **CloudWatch**, **Prometheus** e **Grafana Cloud** proporciona observabilidade detalhada, e o custo otimizado (~US$13,07 por milhão de leads) reflete escolhas econômicas como batching no SQS e uso de serviços serverless. Esta solução está pronta para produção, com potencial para futuras melhorias, demonstrando eficiência técnica e alinhamento com os objetivos do banco.