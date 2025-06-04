package com.forrestgump.leadapi.interfaces.rest.controller;

import com.forrestgump.leadapi.application.usecase.SubmitLeadUseCase;
import com.forrestgump.leadapi.interfaces.rest.dto.LeadRequest;
import com.forrestgump.leadapi.interfaces.rest.dto.LeadResponse;
import com.forrestgump.leadapi.infrastructure.metrics.MetricsPublisher;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import jakarta.validation.Valid;
import java.util.UUID;

@RestController
@RequestMapping("/leads")
public class LeadController {

    private static final Logger logger = LoggerFactory.getLogger(LeadController.class);
    private final SubmitLeadUseCase submitLeadUseCase;
    private final MetricsPublisher metricsPublisher;
    private final RateLimiter rateLimiter;

    public LeadController(SubmitLeadUseCase submitLeadUseCase, MetricsPublisher metricsPublisher,
                          @Qualifier("leadApiRateLimiter") RateLimiter rateLimiter) {
        this.submitLeadUseCase = submitLeadUseCase;
        this.metricsPublisher = metricsPublisher;
        this.rateLimiter = rateLimiter;
    }

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
}