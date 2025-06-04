package com.forrestgump.leadprocessor.domain.service;

import com.forrestgump.leadprocessor.domain.model.Lead;
import com.forrestgump.leadprocessor.infrastructure.persistence.DynamoLeadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class LeadProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(LeadProcessingService.class);
    private final DynamoLeadRepository leadRepository;

    public LeadProcessingService(DynamoLeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }

    public Mono<Void> processLead(Lead lead) {
        logger.info("Persisting lead with leadId: {}", lead.getLeadId());
        return leadRepository.save(lead)
                .doOnSuccess(v -> logger.info("Lead persisted successfully: {}", lead.getLeadId()));
    }
}