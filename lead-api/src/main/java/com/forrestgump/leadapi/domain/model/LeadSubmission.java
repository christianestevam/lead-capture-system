package com.forrestgump.leadapi.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record LeadSubmission(
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("lead_id") String leadId,
        String cpf,
        String salt,
        String name,
        String phone,
        String email,
        @JsonProperty("created_at") Instant createdAt
) {
    public LeadSubmission {
        if (eventId == null) {
            eventId = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}