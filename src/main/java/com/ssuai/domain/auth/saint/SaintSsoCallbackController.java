package com.ssuai.domain.auth.saint;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.auth.AuthExchangeCodeStore;
import com.ssuai.domain.auth.AuthProperties;
import com.ssuai.domain.auth.lms.LmsSsoService;
import com.ssuai.domain.user.entity.Student;
import com.ssuai.domain.user.service.StudentService;
import com.ssuai.global.exception.SaintAuthFailedException;
import com.ssuai.global.exception.SaintPortalUnavailableException;

/**
 * u-SAINT SSO entry + callback (Task 14). Implements the redirect-callback
 * pattern from ADR 0014: the browser is sent to SmartID with our backend's
 * own URL as {@code apiReturnUrl}, so SmartID 302s back here with
 * {@code sToken} + {@code sIdno} in the query string. Same origin, no SOP
 * dance.
 *
 * <p>This controller never sees the user's SSU password — SmartID handles
 * that on its own login page. The one-shot tokens received here are
 * consumed inside {@link SaintSsoService#authenticate(String, String)}
 * and discarded; only the resulting identity leaves the method.
 *
 * <p>Since ADR 0095 (Fix B) this controller never sets the refresh cookie
 * itself and never 302-redirects — see {@link #htmlRedirect(URI)}. Every
 * response, success or error, is a plain 200 + JS navigation to the
 * frontend's {@code /auth/return} page; the success case carries a
 * one-time exchange code in the query string instead of a cookie.
 */
@RestController
@RequestMapping("/api/auth/saint")
public class SaintSsoCallbackController {

    private static final Logger log = LoggerFactory.getLogger(SaintSsoCallbackController.class);

    private final SaintSsoService saintSsoService;
    private final LmsSsoService lmsSsoService;
    private final StudentService studentService;
    private final AuthExchangeCodeStore authExchangeCodeStore;
    private final AuthProperties authProperties;
    private final String frontendOrigin;

    public SaintSsoCallbackController(
            SaintSsoService saintSsoService,
            LmsSsoService lmsSsoService,
            StudentService studentService,
            AuthExchangeCodeStore authExchangeCodeStore,
            AuthProperties authProperties,
            @Value("${ssuai.frontend.origin:}") String frontendOrigin) {
        if (frontendOrigin == null || frontendOrigin.isBlank()) {
            throw new IllegalStateException(
                    "ssuai.frontend.origin (env: SSUAI_FRONTEND_ORIGIN) must be set; "
                            + "the SSO callback cannot 302 the user back to the frontend without it.");
        }
        String apiBaseUrl = authProperties.getApiBaseUrl();
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "ssuai.auth.api-base-url (env: SSUAI_API_BASE_URL) must be set; "
                            + "SmartID needs an absolute apiReturnUrl. A blank value yields "
                            + "the relative path /api/auth/saint/sso-callback, which SmartID "
                            + "resolves against its own host and the callback never reaches us.");
        }
        this.saintSsoService = saintSsoService;
        this.lmsSsoService = lmsSsoService;
        this.studentService = studentService;
        this.authExchangeCodeStore = authExchangeCodeStore;
        this.authProperties = authProperties;
        this.frontendOrigin = frontendOrigin;
    }

    @GetMapping("/sso-init")
    public ResponseEntity<Void> ssoInit() {
        String callback = authProperties.getApiBaseUrl() + "/api/auth/saint/sso-callback";
        String encoded = URLEncoder.encode(callback, StandardCharsets.UTF_8);
        URI redirect = URI.create(
                authProperties.getSmartidSsoUrl() + "?apiReturnUrl=" + encoded);
        return ResponseEntity.status(HttpStatus.FOUND).location(redirect).build();
    }

    @GetMapping("/sso-callback")
    public ResponseEntity<String> ssoCallback(
            @RequestParam(required = false) String sToken,
            @RequestParam(required = false) String sIdno) {
        try {
            UsaintAuthResult identity = saintSsoService.authenticate(sToken, sIdno);
            Student student = studentService.upsertOnLogin(
                    identity.studentId(),
                    identity.name(),
                    identity.major(),
                    identity.enrollmentStatus());

            // Best-effort LMS auth with the same one-shot SmartID tokens.
            // LMS uses an identical sToken/sIdno flow (lms.ssu.ac.kr/xn-sso/gw-cb.php).
            // A failure here must never block the ssuAI login — the user still gets
            // a valid exchange code; only LMS-specific cards degrade.
            try {
                lmsSsoService.authenticate(sToken, sIdno);
            } catch (Exception lmsEx) {
                log.info("saint sso-callback: LMS auth skipped ({})", lmsEx.getMessage());
            }

            String code = authExchangeCodeStore.issue(student.getStudentId());
            return htmlRedirect(frontendReturn("code", code));
        } catch (SaintAuthFailedException exception) {
            log.info("saint sso-callback auth failed: {}", exception.getMessage());
            return htmlRedirect(frontendReturn("error", "auth_failed"));
        } catch (SaintPortalUnavailableException exception) {
            log.warn("saint sso-callback portal unavailable: {}", exception.getMessage());
            return htmlRedirect(frontendReturn("error", "portal_unavailable"));
        } catch (Exception exception) {
            // Catch-all so the user is always returned to the frontend with
            // an actionable error, never left on a backend 5xx page.
            log.warn("saint sso-callback unknown failure", exception);
            return htmlRedirect(frontendReturn("error", "unknown"));
        }
    }

    private URI frontendReturn(String key, String value) {
        return URI.create(frontendOrigin + "/auth/return?" + key + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    // Returns a plain 200 that forwards the browser to the frontend via a JS
    // navigation (NOT a redirect, NOT a meta-refresh, and — since ADR 0095 —
    // NEVER carrying a Set-Cookie). This uniform 200+JS shape for every single
    // callback response (success AND error) is deliberate: the earlier design
    // (ADR 0074 login-cookie follow-up) tried to ride the refresh cookie out on
    // this same response, betting on a 200 surviving whatever proxy sat in front
    // of it — a bet that lost in the field. The frontend's own callback-relay
    // proxy misparsed the joined Set-Cookie header (it collided with Traefik's
    // appended session-affinity cookie) and its redirect branch never copied
    // cookies at all, so the refresh cookie never reliably reached the browser.
    // Fix B removes the cookie from this response entirely: on success the
    // browser is handed a one-time exchange code in the URL instead
    // (`?code=...`), which the frontend POSTs same-origin to
    // `/api/auth/exchange` — a plain non-redirect 200 response, the delivery
    // path already proven to carry Set-Cookie reliably. Because no callback
    // response carries a cookie anymore, no redirect/cache transformation
    // applied by any proxy in front of this endpoint can ever strip one again.
    private static ResponseEntity<String> htmlRedirect(URI location) {
        String raw = location.toString();
        String htmlAttr = raw.replace("&", "&amp;").replace("\"", "&quot;");
        String jsStr = raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003C");
        String html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>ssuAI</title>"
                + "<script>location.replace(\"" + jsStr + "\");</script>"
                + "</head><body><a href=\"" + htmlAttr + "\">계속하기</a></body></html>";
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8")
                .body(html);
    }
}
