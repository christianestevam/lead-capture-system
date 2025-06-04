package com.forrestgump.leadapi.domain.service;

import com.forrestgump.leadapi.domain.model.Lead;
import com.forrestgump.leadapi.domain.model.LeadSubmission;
import com.forrestgump.leadapi.infrastructure.messaging.SqsLeadPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class LeadSubmissionService {

    private static final Logger logger = LoggerFactory.getLogger(LeadSubmissionService.class);
    private final SqsLeadPublisher leadPublisher;

    public LeadSubmissionService(SqsLeadPublisher leadPublisher) {
        this.leadPublisher = leadPublisher;
    }

    public Mono<Void> submitLead(Lead lead) {
        logger.info("Submitting lead with leadId: {}", lead.leadId());
        return Mono.just(new LeadSubmission(
                        null, // eventId will be generated if null
                        lead.leadId(),
                        "", // CPF will be provided by SubmitLeadUseCase
                        lead.salt(),
                        lead.name(),
                        lead.phone(),
                        lead.email(),
                        lead.createdAt()))
                .flatMap(leadPublisher::publish)
                .doOnSuccess(v -> logger.info("Lead submitted successfully: {}", lead.leadId()));
    }
}