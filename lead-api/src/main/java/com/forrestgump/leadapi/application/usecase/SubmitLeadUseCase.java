package com.forrestgump.leadapi.application.usecase;

import com.forrestgump.leadapi.domain.model.Lead;
import com.forrestgump.leadapi.domain.model.LeadSubmission;
import com.forrestgump.leadapi.infrastructure.messaging.SqsLeadPublisher;
import com.forrestgump.leadapi.interfaces.rest.dto.LeadRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
public class SubmitLeadUseCase {

    private static final Logger logger = LoggerFactory.getLogger(SubmitLeadUseCase.class);
    private final SqsLeadPublisher leadPublisher;

    public SubmitLeadUseCase(SqsLeadPublisher leadPublisher) {
        this.leadPublisher = leadPublisher;
    }

    public Mono<Void> execute(LeadRequest request, String correlationId) {
        // Gerar salt e hash do CPF
        String salt = generateSalt();
        String leadId = generateLeadId(request.cpf(), salt);

        Lead lead = Lead.fromRequest(request.cpf(), request.name(), request.phone(), request.email(), leadId, salt);
        LeadSubmission submission = new LeadSubmission(
                UUID.randomUUID(),
                lead.leadId(),
                request.cpf(),
                lead.salt(),
                lead.name(),
                lead.phone(),
                lead.email(),
                lead.createdAt());

        logger.info("Submitting lead, correlationId: {}, leadId: {}", correlationId, lead.leadId());
        return leadPublisher.publish(submission);
    }

    private String generateSalt() {
        byte[] saltBytes = new byte[16];
        new java.security.SecureRandom().nextBytes(saltBytes);
        return java.util.Base64.getEncoder().encodeToString(saltBytes);
    }

    private String generateLeadId(String cpf, String salt) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            String combined = cpf + salt;
            byte[] hashBytes = digest.digest(combined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(hashBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate lead ID", e);
        }
    }
}