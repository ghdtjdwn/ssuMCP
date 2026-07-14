package com.ssuai.domain.saint.connector;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ScholarshipEntry;

@Component
@ConditionalOnProperty(name = "ssuai.connector.saint-scholarship", havingValue = "rusaint")
public class RusaintScholarshipConnector implements SaintScholarshipConnector {

    private final RusaintClient rusaintClient;

    public RusaintScholarshipConnector(RusaintClient rusaintClient) {
        this.rusaintClient = rusaintClient;
    }

    @Override
    public List<ScholarshipEntry> fetchScholarships(String studentId, PortalCookies cookies) {
        try {
            RusaintSessionResult<List<ScholarshipEntry>> result =
                    rusaintClient.fetchScholarshipsWithSession(
                            studentId, cookies.sessionJson());
            cookies.refreshSessionJson(result.sessionJson());
            return result.value();
        } catch (RusaintClientException exception) {
            throw RusaintFailureClassifier.classify(exception, "scholarship");
        }
    }
}
