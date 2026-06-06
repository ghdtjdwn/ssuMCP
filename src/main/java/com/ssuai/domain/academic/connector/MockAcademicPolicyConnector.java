package com.ssuai.domain.academic.connector;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.academic.dto.AcademicPolicyCorpusSnapshot;

@Component
@ConditionalOnProperty(name = "ssuai.connector.academic-policy", havingValue = "mock", matchIfMissing = true)
class MockAcademicPolicyConnector implements AcademicPolicyConnector {

    @Override
    public AcademicPolicyCorpusSnapshot loadCorpus(boolean live) {
        return AcademicPolicySeedCorpus.fallbackSnapshot(live, Instant.now());
    }
}
