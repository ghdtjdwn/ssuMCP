package com.ssuai.domain.auth.lms;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

class LmsCookieJarTests {

    @Test
    void sameNameCookiesRemainDistinctByDomainPathAndHostOnlyScope() {
        URI canvas = URI.create("https://canvas.ssu.ac.kr/learningx/api/items");
        LmsCookieJar jar = LmsCookieJar.empty(List.of("https://canvas.ssu.ac.kr"));
        jar.applySetCookie(canvas, "session=root; Path=/; Secure");
        jar.applySetCookie(canvas, "session=api; Path=/learningx/api; Secure");

        assertThat(jar.cookieHeader(canvas)).contains("session=api", "session=root");
        assertThat(jar.cookieHeader(URI.create("https://canvas.ssu.ac.kr/learningx/home")))
                .containsOnlyOnce("session=root")
                .doesNotContain("session=api");
    }

    @Test
    void untrustedOriginsReceiveNoCookies() {
        URI canvas = URI.create("https://canvas.ssu.ac.kr/");
        LmsCookieJar jar = LmsCookieJar.fromLegacyHeader("xn_api_token=secret", canvas);

        assertThat(jar.cookieHeader(URI.create("https://attacker.example/collect"))).isEmpty();
        assertThat(jar.valueFor(URI.create("https://attacker.example/collect"), "xn_api_token"))
                .isNull();
    }
}
