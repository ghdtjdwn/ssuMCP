package com.ssuai.domain.auth.lms;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.URI;
import java.util.List;
import java.util.Map;

/** Only emits or accepts cookies for the explicit LMS origin allow-list. */
public final class LmsCookieHandler extends CookieHandler {

    private final LmsCookieJar jar;

    public LmsCookieHandler(LmsCookieJar jar) {
        this.jar = jar;
    }

    @Override
    public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) {
        String header = jar.cookieHeader(uri);
        return header.isBlank() ? Map.of() : Map.of("Cookie", List.of(header));
    }

    @Override
    public void put(URI uri, Map<String, List<String>> responseHeaders) throws IOException {
        if (!jar.permits(uri)) {
            return;
        }
        for (Map.Entry<String, List<String>> header : responseHeaders.entrySet()) {
            if ("set-cookie".equalsIgnoreCase(header.getKey())) {
                for (String value : header.getValue()) {
                    jar.applySetCookie(uri, value);
                }
            }
        }
    }

    public LmsCookieJar jar() {
        return jar;
    }
}
