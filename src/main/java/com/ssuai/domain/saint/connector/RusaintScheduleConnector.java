package com.ssuai.domain.saint.connector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.global.exception.SaintSessionExpiredException;

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
            return rusaintClient.fetchSchedule(studentId, cookies.sessionJson(), year, term);
        } catch (RusaintClientException exception) {
            throw new SaintSessionExpiredException("rusaint schedule session rejected");
        }
    }
}
