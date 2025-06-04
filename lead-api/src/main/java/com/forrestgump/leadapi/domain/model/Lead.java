package com.forrestgump.leadapi.domain.model;

import com.forrestgump.leadapi.domain.exception.LeadValidationException;
import com.forrestgump.leadapi.domain.util.CpfValidator;
import java.time.Instant;

public record Lead(
        String leadId,
        String salt,
        String name,
        String phone,
        String email,
        Instant createdAt
) {
    public Lead {
        if (leadId == null || leadId.isBlank()) {
            throw new LeadValidationException("Lead ID (hash) is required");
        }
        if (salt == null || salt.isBlank()) {
            throw new LeadValidationException("Salt is required");
        }
        if (name == null || name.isBlank()) {
            throw new LeadValidationException("Name is required");
        }
        if (phone == null || phone.isBlank()) {
            throw new LeadValidationException("Phone is required");
        }
        if (email == null || !email.matches("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$")) {
            throw new LeadValidationException("Invalid email format");
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public static Lead fromRequest(String cpf, String name, String phone, String email, String leadId, String salt) {
        if (cpf == null || !CpfValidator.isValidCpf(cpf)) {
            throw new LeadValidationException("CPF must be a valid 11-digit number");
        }
        return new Lead(leadId, salt, name, phone, email, Instant.now());
    }
}