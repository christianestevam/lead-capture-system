# Lead-Capture-System
---

## 1. Introdução

O projeto foi feito a partir de um desafio técnico de desenvolver uma solução para capturar leads durante uma campanha de marketing de um banco tradicional expandindo para o público em geral. O sistema suporta o pré-cadastro de usuários via um site acessado por QR code, exibido em uma campanha nacional na TV, coletando dados sensíveis (nome, CPF, telefone, email). Meu objetivo foi desenvolver uma arquitetura robusta que garantisse **escalabilidade**, **alta disponibilidade**, **resiliência**, **performance**, **segurança** (conformidade com a LGPD), **flexibilidade** conforme os requisitos e adicionei mais um requisito **custo** que acredito ser um importante quando estamos contruindo uma solução na nuvem, atendendo aos requisitos do desafio e ao cenário de alta visibilidade.

O sistema é composto por dois microserviços: **lead-api** (recebe requisições via API, valida dados, aplica rate limiting, gera hash do CPF, e enfileira mensagens no SQS) e **lead-processor** (consome mensagens, criptografa CPF com KMS, persiste no DynamoDB). A arquitetura é serverless, baseada na AWS, utilizando **Spring WebFlux**, **Java 21**, e **DDD**, com estratégias como **batching** no SQS, **rate limiting** (global no API Gateway e por cliente no lead-api), **Dead Letter Queue (DLQ)** no lead-processor, e medidas de segurança como hash SHA-256, criptografia KMS, mascaramento de logs, e rastreabilidade via correlation ID.

Esta documentação detalha a arquitetura, escolhas técnicas, trade-offs, e análise de custos, com trechos de código para ilustrar as implementações, refletindo meu raciocínio no desafio.

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

[![Arquitetura do Sistema]()

### 3.1 Fluxo de Dados

1. **QR Code Escaneado**:
    - Usuários acessam o domínio via **Route 53**, resolvido para **CloudFront**.
    - **CloudFront** serve o React App do **S3**.

2. **Envio do Formulário**:
    - Usuário preenche nome, CPF, telefone, email e envia `POST /api/leads`.
    - **WAF** filtra ataques; **API Gateway** aplica rate limiting global (1000 req/s).

3. **Processamento no lead-api**:
    - Valida requisições com rate limiting por IP (10 req/s), gera `leadId` e `X-Correlation-Id`, enfileira **LeadSubmission** no SQS em lotes:
      ```java
      // LeadController.java
      @PostMapping
      public Mono<ResponseEntity<LeadResponse>> register(
              @Valid @RequestBody Mono<LeadRequest> requestMono,
              @RequestHeader("X-Correlation-Id") String correlationId,
              @RequestHeader("X-Forwarded-For") String clientIp) {
          return requestMono
                  .transformDeferred(RateLimiterOperator.of(rateLimiter))
                  .flatMap(request -> submitLeadUseCase.execute(request, correlationId))
                  .then(Mono.fromCallable(() -> ResponseEntity.ok(new LeadResponse(eventId, "Lead queued successfully"))))
                  .onErrorResume(e -> {
                      metricsPublisher.incrementRateLimit();
                      return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                              .body(new LeadResponse(eventId, "Too many requests")));
                  });
      }
      ```
    - Retorna **LeadResponse** com `eventId`.

4. **Consumo no lead-processor**:
    - Consome mensagens em lotes, criptografa CPF, persiste no DynamoDB.
    - Falhas vão para a **DLQ** após 3 tentativas.

5. **Segurança e Observabilidade**:
    - **KMS** criptografa; **CloudTrail** audita.
    - **CloudWatch** coleta logs/métricas; **Grafana Cloud** visualiza.

---

## 4. Escolhas Técnicas e Justificativas

Como único desenvolvedor do teste técnico, analisei cada decisão com base nos requisitos, considerando trade-offs, benefícios, e impacto no custo. Abaixo, detalho as escolhas, com trechos de código para ilustrar.

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

Como desenvolvedor, criei uma solução serverless robusta, reativa, e segura, atendendo ao desafio com **escalabilidade** (Lambda, DynamoDB), **resiliência** (DLQ no lead-processor, Resilience4j), **performance** (WebFlux, batching), **segurança** (hash, KMS, dual rate limiting), **flexibilidade** (DDD), e **custo otimizado** (~US$13,07/M leads). A adição de rate limiting por cliente no **lead-api** fortalece a proteção contra abusos, complementando o **API Gateway**, enquanto o Grafana Cloud pago garante observabilidade escalável. As escolhas refletem trade-offs para eficiência e LGPD, prontas para evoluções futuras.

---

## 7. Próximos Passos

1. Deploy com **AWS CDK**.
2. Testes reativos com **StepVerifier**.
3. Reprocessamento automático da DLQ no **lead-processor**.
4. Índices secundários no DynamoDB.
5. CI/CD com **CodePipeline**.

---