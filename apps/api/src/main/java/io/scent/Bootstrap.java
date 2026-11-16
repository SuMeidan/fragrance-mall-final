package io.scent;

import io.scent.account.AuthMapper;
import io.scent.account.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Explicit provisioning only; does not reset existing passwords on each startup. */
@Component
public class Bootstrap implements ApplicationRunner {
    private final AuthMapper accounts;
    private final PasswordEncoder passwords;
    private final String login;
    private final String password;
    public Bootstrap(AuthMapper accounts, PasswordEncoder passwords,
            @Value("${scent.bootstrap.admin-login:}") String login,
            @Value("${scent.bootstrap.admin-password:}") String password) {
        this.accounts = accounts; this.passwords = passwords; this.login = login; this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (login.isBlank() && password.isBlank()) { return; }
        if (!login.matches("[A-Za-z][A-Za-z0-9_-]{2,31}")) { throw new IllegalArgumentException("Invalid explicit admin login"); }
        AuthService.validatePassword(password);
        AuthMapper.Account existing = accounts.find(login);
        if (existing == null) {
            if (accounts.adminCount() != 0) { throw new IllegalStateException("An administrator already exists; refusing to create another"); }
            accounts.create(login, passwords.encode(password), "SCENT管理员", "ADMIN");
        }
        else if (!existing.role().equals("ADMIN")) { throw new IllegalStateException("Admin login is already a buyer account"); }
    }
}
