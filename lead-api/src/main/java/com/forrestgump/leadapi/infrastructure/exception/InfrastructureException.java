package com.forrestgump.leadapi.infrastructure.exception;

public class InfrastructureException extends RuntimeException {
    public InfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}