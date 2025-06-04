package com.forrestgump.leadapi.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record LeadResponse(
        @JsonProperty("eventId") String eventId,
        @JsonProperty("message") String message,
        @JsonProperty("timestamp") Instant timestamp
) {
    public LeadResponse(String eventId, String message) {
        this(eventId, message, Instant.now());
    }
}