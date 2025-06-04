package com.forrestgump.leadapi.domain.exception;

public class LeadValidationException extends RuntimeException {
    public LeadValidationException(String message) {
        super(message);
    }
}