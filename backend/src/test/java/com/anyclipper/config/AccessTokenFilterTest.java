package com.anyclipper.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests how tokens from .env files and from the login form are compared. */
class AccessTokenFilterTest {

    private static AccessTokenFilter filterWithToken(String configuredToken) {
        return new AccessTokenFilter(new ClipperProperties(null, configuredToken, null, null, null, null));
    }

    @Test
    void quotesAndSpacesAroundTheConfiguredTokenAreIgnored() {
        assertThat(filterWithToken("\"my-secret\"").isValid("my-secret")).isTrue();
        assertThat(filterWithToken("'my-secret'").isValid("my-secret")).isTrue();
        assertThat(filterWithToken("  my-secret  ").isValid("my-secret")).isTrue();
    }

    @Test
    void spacesAroundTheTypedTokenAreIgnored() {
        assertThat(filterWithToken("my-secret").isValid(" my-secret\n")).isTrue();
    }

    @Test
    void wrongOrEmptyTokensAreRejected() {
        assertThat(filterWithToken("my-secret").isValid("my-secreT")).isFalse();
        assertThat(filterWithToken("my-secret").isValid(null)).isFalse();
        assertThat(filterWithToken("").isValid("")).isFalse();
    }

    @Test
    void cookieValueIsAPlainFingerprintEvenForUnusualCharacters() {
        String cookie = AccessTokenFilter.cookieValueFor("pass; with \"odd\" chars");

        assertThat(cookie).matches("[0-9a-f]{64}").doesNotContain("pass");
        assertThat(AccessTokenFilter.cookieValueFor(" \"pass; with \"odd\" chars\" ")).isEqualTo(cookie);
        assertThat(AccessTokenFilter.cookieValueFor("other")).isNotEqualTo(cookie);
    }
}
