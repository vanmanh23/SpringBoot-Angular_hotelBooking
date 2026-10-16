package com.stayhotel.auth.admin;

import com.stayhotel.auth.config.AuthProperties;
import com.stayhotel.auth.user.Role;
import com.stayhotel.auth.user.User;
import com.stayhotel.auth.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Creates the first ADMIN from ADMIN_EMAIL / ADMIN_PASSWORD when both are set and the email is unused.
 * There is no default admin and registration can never grant ADMIN.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final Pattern STRONG = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{12,72}$");

    private final AuthProperties.BootstrapAdmin config;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    AdminBootstrap(AuthProperties properties, UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.config = properties.bootstrapAdmin();
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (isBlank(config.email()) || isBlank(config.password())) {
            return;
        }
        String email = User.normalizeEmail(config.email());
        if (users.existsByEmail(email)) {
            return;
        }
        if (!STRONG.matcher(config.password()).matches()) {
            throw new IllegalStateException("ADMIN_PASSWORD must be 12-72 characters with letters and digits");
        }
        String fullName = isBlank(config.fullName()) ? "System Administrator" : config.fullName();
        users.save(User.register(email, passwordEncoder.encode(config.password()), fullName, null,
                Set.of(Role.ADMIN), clock.instant()));
        log.info("Bootstrap administrator account created for {}", email);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
