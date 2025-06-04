package com.forrestgump.leadapi.interfaces.rest;

import com.forrestgump.leadapi.domain.exception.LeadValidationException;
import com.forrestgump.leadapi.infrastructure.exception.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(LeadValidationException.class)
    public Mono<ResponseEntity<String>> handleValidationException(LeadValidationException e) {
        logger.error("Validation error: {}", e.getMessage());
        return Mono.just(ResponseEntity.badRequest().body(e.getMessage()));
    }

    @ExceptionHandler(InfrastructureException.class)
    public Mono<ResponseEntity<String>> handleInfrastructureException(InfrastructureException e) {
        logger.error("Infrastructure error: {}", e.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal server error"));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<String>> handleValidationExceptions(WebExchangeBindException ex) {
        String errorMessage = ex.getBindingResult().getAllErrors().stream()
                .map(error -> error.getDefaultMessage())
                .reduce("", (acc, msg) -> acc + msg + "; ");
        logger.error("Validation errors: {}", errorMessage);
        return Mono.just(ResponseEntity.badRequest().body(errorMessage));
    }
}