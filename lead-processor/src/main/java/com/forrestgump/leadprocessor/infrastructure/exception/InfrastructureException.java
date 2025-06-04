package com.forrestgump.leadprocessor.infrastructure.exception;

public class InfrastructureException extends RuntimeException {
    public InfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}