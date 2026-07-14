package com.ssuai.domain.saint.connector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ScheduleResponse;

@Component
@ConditionalOnProperty(name = "ssuai.connector.saint-schedule", havingValue = "rusaint")
public class RusaintScheduleConnector implements SaintScheduleConnector {

    private final RusaintClient rusaintClient;

    public RusaintScheduleConnector(RusaintClient rusaintClient) {
        this.rusaintClient = rusaintClient;
    }

    @Override
    public ScheduleResponse fetchSchedule(String studentId, PortalCookies cookies) {
        return fetchSchedule(studentId, cookies, null, null);
    }

    @Override
    public ScheduleResponse fetchSchedule(String studentId, PortalCookies cookies, Integer year, Integer term) {
        try {
            RusaintSessionResult<ScheduleResponse> result = rusaintClient.fetchScheduleWithSession(
                    studentId, cookies.sessionJson(), year, term);
            cookies.refreshSessionJson(result.sessionJson());
            return result.value();
        } catch (RusaintClientException exception) {
            throw RusaintFailureClassifier.classify(exception, "schedule");
        }
    }
}
