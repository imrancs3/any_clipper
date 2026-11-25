package com.anyclipper.api;

import com.anyclipper.common.ClipperException;
import com.anyclipper.config.AccessTokenFilter;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The minimal login of the spec (Section 5): checks the access token once and stores it in a cookie.
 *
 * <p>The browser needs the cookie because its SSE connection (EventSource) cannot send the
 * X-Access-Token header. The cookie holds a fingerprint of the token, not the token itself. HttpOnly keeps scripts from reading it; SameSite=Strict keeps other sites
 * from using it.
 */
@RestController
public class AuthController {

    public record LoginRequest(String token) {
    }

    private static final Duration COOKIE_LIFETIME = Duration.ofDays(30);

    private final AccessTokenFilter accessTokenFilter;

    public AuthController(AccessTokenFilter accessTokenFilter) {
        this.accessTokenFilter = accessTokenFilter;
    }

    @PostMapping("/api/login")
    public ResponseEntity<Void> login(@RequestBody LoginRequest request) {
        if (!accessTokenFilter.isValid(request.token())) {
            throw new ClipperException(HttpStatus.UNAUTHORIZED, "Wrong access token. Clipper uses the "
                    + "APP_ACCESS_TOKEN it read when it started: restart it after editing .env. An old "
                    + "APP_ACCESS_TOKEN environment variable (set with setx or $env:) overrides .env, so remove it.");
        }
        ResponseCookie cookie = ResponseCookie.from(AccessTokenFilter.COOKIE_NAME,
                        AccessTokenFilter.cookieValueFor(request.token()))
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(COOKIE_LIFETIME)
                .build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }
}
