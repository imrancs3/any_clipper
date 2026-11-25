package com.anyclipper.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Simple access protection for the whole API (spec, Section 5). No Spring Security.
 *
 * <p>Every /api/** request must carry the APP_ACCESS_TOKEN, either in the {@code X-Access-Token} header
 * (handy for curl) or as the {@code clipper_token} cookie that POST /api/login sets (the browser uses
 * this, because an SSE connection cannot send custom headers). Only the login call itself is open.
 * The frontend's static files are not protected; they contain no data.
 *
 * <p>The cookie holds a SHA-256 fingerprint of the token, not the token itself. That keeps the real
 * token out of the browser, and any character the user picks for the token (spaces, quotes, ';')
 * still makes a valid cookie value.
 */
@Component
public class AccessTokenFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Access-Token";
    public static final String COOKIE_NAME = "clipper_token";

    private final byte[] expectedToken;
    private final byte[] expectedCookie;

    public AccessTokenFilter(ClipperProperties properties) {
        String token = normalize(properties.accessToken());
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
        this.expectedCookie = cookieValueFor(token).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Cleans a token the same way for the configured value and for what the user types: surrounding
     * spaces and one pair of surrounding quotes are removed. A .env file is read as-is, so
     * {@code APP_ACCESS_TOKEN="abc"} would otherwise make the quotes part of the token.
     */
    public static String normalize(String token) {
        if (token == null) {
            return "";
        }
        String trimmed = token.strip();
        if (trimmed.length() >= 2 && (trimmed.startsWith("\"") && trimmed.endsWith("\"")
                || trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).strip();
        }
        return trimmed;
    }

    /** True if the typed or sent token matches. Constant-time, so timing reveals nothing. */
    public boolean isValid(String token) {
        return token != null && expectedToken.length > 0
                && MessageDigest.isEqual(expectedToken, normalize(token).getBytes(StandardCharsets.UTF_8));
    }

    /** The value stored in the login cookie for a (valid) token. */
    public static String cookieValueFor(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(("clipper:" + normalize(token)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in Java", e);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || path.equals("/api/login");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isValid(request.getHeader(HEADER_NAME)) || isValidCookie(cookieFrom(request))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"Missing or wrong access token\"}");
    }

    private boolean isValidCookie(String value) {
        return value != null && expectedToken.length > 0
                && MessageDigest.isEqual(expectedCookie, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String cookieFrom(HttpServletRequest request) {
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
