package io.scent.account;

import io.scent.common.BusinessException;
import io.scent.common.Page;
import io.scent.IsolatedDatabase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountRulesTest {
    @Test
    void integrationGuardRejectsTheDevelopmentSchemaBeforeConnecting() {
        assertThrows(IllegalStateException.class, () -> IsolatedDatabase.require("jdbc:mysql://127.0.0.1:13310/scent_v1"));
        assertThrows(IllegalStateException.class, () -> IsolatedDatabase.require(null));
        assertEquals("jdbc:mysql://db:3306/scent_ci_it?connectionTimeZone=UTC",
            IsolatedDatabase.require("jdbc:mysql://db:3306/scent_ci_it?connectionTimeZone=UTC"));
    }

    @Test
    void passwordRejectsBcryptByteTruncation() {
        assertDoesNotThrow(() -> AuthService.validatePassword("A-normal-password123"));
        assertThrows(BusinessException.class, () -> AuthService.validatePassword("短密码"));
        assertThrows(BusinessException.class, () -> AuthService.validatePassword("香".repeat(25)));
        assertDoesNotThrow(() -> AuthService.validatePassword("香".repeat(24)));
    }

    @Test
    void tokenDigestIsDeterministicAndHasNoPlaintext() {
        String hash = AuthService.hash("random-token-value");
        assertEquals(64, hash.length());
        assertEquals(hash, AuthService.hash("random-token-value"));
        assertNotEquals(hash, AuthService.hash("other-value"));
        assertFalse(hash.contains("random"));
    }

    @Test
    void limiterBoundsAttemptsAndCanClearSuccessfulLogin() {
        var limiter = new AttemptLimiter(Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC));
        limiter.check("a", 2, 60); limiter.check("a", 2, 60);
        assertEquals(429, assertThrows(BusinessException.class, () -> limiter.check("a", 2, 60)).status());
        assertDoesNotThrow(() -> limiter.check("b", 2, 60));
        limiter.clear("a"); assertDoesNotThrow(() -> limiter.check("a", 2, 60));
    }

    @Test
    void pagesAndLikePatternsAreBounded() {
        assertEquals(12, Page.offset(2, 12));
        assertThrows(BusinessException.class, () -> Page.offset(1, 41));
        assertThrows(BusinessException.class, () -> Page.offset(Integer.MAX_VALUE, 12));
        assertEquals("50!%!_!!", Page.keyword(" 50%_! "));
    }
}
