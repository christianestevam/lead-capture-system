package com.forrestgump.leadprocessor.domain.exception;

public class LeadValidationException extends RuntimeException {
    public LeadValidationException(String message) {
        super(message);
    }
}