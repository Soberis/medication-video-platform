package com.medicationvideo.platform.iamproject;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class PlatformAccountBootstrap {

	@Bean
	UserDetailsService platformAccountUserDetailsService(PlatformAccountRepository repository) {
		return username -> repository.findByUsername(username)
				.map(account -> User.withUsername(account.getUsername())
						.password(account.getPasswordHash())
						.authorities("ROLE_" + account.getRole().name())
						.disabled(!account.isEnabled())
						.build())
				.orElseThrow(() -> new UsernameNotFoundException("Platform account not found."));
	}

	@Bean
	ApplicationRunner bootstrapPlatformAdministrator(
			PlatformAccountRepository repository,
			PasswordEncoder passwordEncoder,
			@Value("${platform.security.bootstrap-admin-username:}") String username,
			@Value("${platform.security.bootstrap-admin-password:}") String password) {
		return arguments -> {
			if (username.isBlank() && password.isBlank()) {
				return;
			}
			if (username.isBlank() || password.isBlank()) {
				throw new IllegalStateException(
						"Both PLATFORM_BOOTSTRAP_ADMIN_USERNAME and PLATFORM_BOOTSTRAP_ADMIN_PASSWORD are required.");
			}
			if (!username.matches("^[A-Za-z0-9][A-Za-z0-9._:@-]{2,127}$") || password.length() < 12) {
				throw new IllegalStateException(
						"The bootstrap administrator username or password does not meet the minimum policy.");
			}
			repository.findByUsername(username).orElseGet(() -> repository.save(PlatformAccount.create(
					username,
					passwordEncoder.encode(password),
					PlatformAccountRole.ADMIN,
					Instant.now())));
		};
	}
}
