package com.forrestgump.leadprocessor.domain.model;

import java.time.Instant;
import java.util.UUID;

public record LeadSubmission(
        UUID eventId,
        String leadId,
        String cpf,
        String salt,
        String name,
        String phone,
        String email,
        Instant createdAt
) {}