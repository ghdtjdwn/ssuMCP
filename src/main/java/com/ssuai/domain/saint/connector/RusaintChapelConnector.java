package com.ssuai.domain.saint.connector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ChapelInfo;

@Component
@ConditionalOnProperty(name = "ssuai.connector.saint-chapel", havingValue = "rusaint")
public class RusaintChapelConnector implements SaintChapelConnector {

    private final RusaintClient rusaintClient;

    public RusaintChapelConnector(RusaintClient rusaintClient) {
        this.rusaintClient = rusaintClient;
    }

    @Override
    public ChapelInfo fetchChapelInfo(String studentId, PortalCookies cookies, Integer year, String semester) {
        try {
            RusaintSessionResult<ChapelInfo> result = rusaintClient.fetchChapelInfoWithSession(
                    studentId, cookies.sessionJson(), year, semester);
            cookies.refreshSessionJson(result.sessionJson());
            return result.value();
        } catch (RusaintClientException exception) {
            throw RusaintFailureClassifier.classify(exception, "chapel");
        }
    }

    @Override
    public int countChapelPassedSemesters(String studentId, PortalCookies cookies, int entryYear) {
        try {
            RusaintSessionResult<Integer> result =
                    rusaintClient.countChapelPassedSemestersWithSession(
                            studentId, cookies.sessionJson(), entryYear);
            cookies.refreshSessionJson(result.sessionJson());
            return result.value();
        } catch (RusaintClientException exception) {
            throw RusaintFailureClassifier.classify(exception, "chapel-history");
        }
    }
}
