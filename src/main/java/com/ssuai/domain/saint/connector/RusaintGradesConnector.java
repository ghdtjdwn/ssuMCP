package com.ssuai.domain.saint.connector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.GradesResponse;

@Component
@ConditionalOnProperty(name = "ssuai.connector.saint-grades", havingValue = "rusaint")
public class RusaintGradesConnector implements SaintGradesConnector {

    private final RusaintClient rusaintClient;

    public RusaintGradesConnector(RusaintClient rusaintClient) {
        this.rusaintClient = rusaintClient;
    }

    @Override
    public GradesResponse fetchGrades(String studentId, PortalCookies cookies) {
        try {
            RusaintSessionResult<GradesResponse> result = rusaintClient.fetchGradesWithSession(
                    studentId, cookies.sessionJson());
            cookies.refreshSessionJson(result.sessionJson());
            return result.value();
        } catch (RusaintClientException exception) {
            throw RusaintFailureClassifier.classify(exception, "grades");
        }
    }
}
