package com.ssuai.domain.saint.connector;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;

import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.SaintSessionExpiredException;

/** Maps rusaint/native failures without treating every unexpected response as expiry. */
final class RusaintFailureClassifier {

    private RusaintFailureClassifier() {
    }

    static RuntimeException classify(RusaintClientException exception, String endpoint) {
        Throwable cursor = exception;
        while (cursor != null) {
            String type = cursor.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String message = cursor.getMessage() == null
                    ? "" : cursor.getMessage().toLowerCase(Locale.ROOT);
            if (cursor instanceof HttpTimeoutException
                    || cursor instanceof IOException
                    || type.contains("timeout")
                    || type.contains("network")
                    || message.contains("timed out")
                    || message.contains("connection reset")
                    || message.contains("connection refused")) {
                return new ConnectorUnavailableException(exception);
            }
            if (type.contains("auth")
                    || type.contains("sessionexpired")
                    || message.contains("session expired")
                    || message.contains("not authenticated")
                    || message.contains("unauthorized")
                    || message.contains("forbidden")
                    || message.contains("login required")
                    || message.contains("http 401")
                    || message.contains("http 403")) {
                return new SaintSessionExpiredException(
                        "u-SAINT upstream session expired at " + endpoint);
            }
            cursor = cursor.getCause();
        }
        return new ConnectorParseException(exception);
    }
}
