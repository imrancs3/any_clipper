package com.anyclipper.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Simple access protection for the whole API (spec, Section 5). No Spring Security.
 *
 * <p>Every /api/** request must carry the APP_ACCESS_TOKEN, either in the {@code X-Access-Token} header
 * (handy for curl) or in the {@code clipper_token} cookie that POST /api/login sets (the browser uses
 * this, because an SSE connection cannot send custom headers). Only the login call itself is open.
 * The frontend's static files are not protected; they contain no data.
 */
@Component
public class AccessTokenFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Access-Token";
    public static final String COOKIE_NAME = "clipper_token";

    private final byte[] expectedToken;

    public AccessTokenFilter(ClipperProperties properties) {
        String token = properties.accessToken() == null ? "" : properties.accessToken();
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    /** Constant-time comparison, so response timing does not reveal how much of a guess was right. */
    public boolean isValid(String token) {
        return token != null && expectedToken.length > 0
                && MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || path.equals("/api/login");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isValid(request.getHeader(HEADER_NAME)) || isValid(tokenFromCookie(request))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"Missing or wrong access token\"}");
    }

    private static String tokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
