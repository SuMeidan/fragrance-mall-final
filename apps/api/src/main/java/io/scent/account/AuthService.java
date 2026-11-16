package io.scent.account;

import io.scent.common.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final AuthMapper mapper;
    private final PasswordEncoder passwords;
    private final AttemptLimiter limiter;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;
    private final int sessionHours;
    private final int authPerMinute;

    public AuthService(AuthMapper mapper, PasswordEncoder passwords, AttemptLimiter limiter, AuditService audit,
                       @Value("${scent.security.session-hours:8}") int sessionHours,
                       @Value("${scent.security.auth-per-minute:60}") int authPerMinute) {
        if (sessionHours < 1 || sessionHours > 168) { throw new IllegalArgumentException("session-hours must be 1..168"); }
        this.mapper = mapper; this.passwords = passwords; this.limiter = limiter; this.audit = audit;
        this.sessionHours = sessionHours;
        if (authPerMinute < 1) { throw new IllegalArgumentException("auth-per-minute must be positive"); }
        this.authPerMinute = authPerMinute;
        dummyHash = passwords.encode(Base64.getEncoder().encodeToString(random.generateSeed(24)));
    }

    @Transactional
    public AuthMapper.View register(String phone, String password, String displayName, String ip) {
        limiter.check("auth-ip:" + ip, authPerMinute, 60);
        validatePassword(password);
        try { mapper.create(phone, passwords.encode(password), displayName.strip(), "BUYER"); }
        catch (DuplicateKeyException ex) { throw BusinessException.conflict("LOGIN_EXISTS", "手机号已经注册"); }
        AuthMapper.Account account = mapper.find(phone);
        audit.write(account.id(), "REGISTER", "ACCOUNT", account.id(), "Buyer account created");
        return account.view();
    }

    @Transactional
    public Login login(String login, String password, String role, String ip) {
        limiter.check("auth-ip:" + ip, authPerMinute, 60);
        String key = "login:" + ip + ":" + login;
        limiter.check(key, 10, 600);
        validatePassword(password);
        // Serialize session issuance with account disabling, so a late login cannot recreate a revoked session.
        AuthMapper.Account account = mapper.forLogin(login);
        boolean matches = passwords.matches(password, account == null ? dummyHash : account.passwordHash());
        if (!matches || account == null || !account.enabled() || !role.equals(account.role())) {
            throw new BusinessException(401, "AUTH_INVALID", "账号或密码不正确，或账号不可用");
        }
        limiter.clear(key);
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        LocalDateTime expiry = LocalDateTime.now(ZoneOffset.UTC).plusHours(sessionHours);
        mapper.session(hash(token), account.id(), expiry);
        return new Login(token, expiry.toInstant(ZoneOffset.UTC).toString(), account.view());
    }

    public void logout(String token) { mapper.revoke(hash(token)); }

    @Transactional
    public void setEnabled(long actor, long id, boolean enabled) {
        if (mapper.setEnabled(id, enabled) != 1) { throw BusinessException.missing(); }
        if (!enabled) { mapper.revokeAccount(id); }
        audit.write(actor, "ACCOUNT_STATUS", "ACCOUNT", id, "enabled=" + enabled);
    }

    public Page<AuthMapper.View> list(String keyword, int page, int size) {
        int offset = Page.offset(page, size);
        String escaped = Page.keyword(keyword);
        return new Page<>(mapper.list(escaped, offset, size), mapper.count(escaped), page, size);
    }

    @Scheduled(fixedDelay = 3600000)
    public void cleanup() { mapper.deleteExpired(); }

    public static void validatePassword(String value) {
        if (value == null || value.length() < 8 || value.length() > 64 || value.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw BusinessException.invalid("密码应为8..64字符，UTF-8编码最多72字节");
        }
    }

    public static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public record Login(String token, String expiresAt, AuthMapper.View user) { }
}
