package com.medicationvideo.platform.integration;

import java.net.URI;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fails closed when a non-local profile is started with local transport or credential
 * defaults. Local and test profiles intentionally keep their isolated synthetic values.
 */
@Component
@Profile("!local & !test")
final class ProductionConfigurationValidator {

	ProductionConfigurationValidator(@Value("${server.servlet.session.cookie.secure:true}") boolean sessionCookieSecure,
			@Value("${spring.rabbitmq.ssl.enabled:false}") boolean rabbitMqSslEnabled,
			@Value("${platform.object-storage.endpoint}") String objectStorageEndpoint,
			@Value("${spring.datasource.url}") String databaseUrl,
			@Value("${spring.rabbitmq.password}") String rabbitMqPassword,
			@Value("${platform.object-storage.access-key}") String objectStorageAccessKey,
			@Value("${platform.object-storage.secret-key}") String objectStorageSecretKey) {
		validate(sessionCookieSecure, rabbitMqSslEnabled, objectStorageEndpoint, databaseUrl, rabbitMqPassword,
				objectStorageAccessKey, objectStorageSecretKey);
	}

	static void validate(boolean sessionCookieSecure, boolean rabbitMqSslEnabled, String objectStorageEndpoint,
			String databaseUrl, String rabbitMqPassword, String objectStorageAccessKey, String objectStorageSecretKey) {
		if (!sessionCookieSecure) {
			throw new IllegalStateException("Production session cookies must set Secure=true.");
		}
		if (!rabbitMqSslEnabled) {
			throw new IllegalStateException("Production RabbitMQ transport must enable TLS.");
		}
		var endpoint = parseHttpsEndpoint(objectStorageEndpoint);
		if (endpoint.getHost().equalsIgnoreCase("localhost") || endpoint.getHost().equals("127.0.0.1")) {
			throw new IllegalStateException("Production object storage must not use a local endpoint.");
		}
		var normalizedDatabaseUrl = valueOrEmpty(databaseUrl).toLowerCase(Locale.ROOT);
		if (normalizedDatabaseUrl.matches(".*jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:|/).*")) {
			throw new IllegalStateException("Production database must not use a local endpoint.");
		}
		requireNonPlaceholder(rabbitMqPassword, "RabbitMQ password");
		requireNonPlaceholder(objectStorageAccessKey, "object storage access key");
		requireNonPlaceholder(objectStorageSecretKey, "object storage secret key");
	}

	private static URI parseHttpsEndpoint(String value) {
		try {
			var endpoint = URI.create(valueOrEmpty(value));
			if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null) {
				throw new IllegalStateException("Production object storage endpoint must be HTTPS.");
			}
			return endpoint;
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalStateException("Production object storage endpoint must be a valid HTTPS URI.", exception);
		}
	}

	private static void requireNonPlaceholder(String value, String label) {
		var normalized = valueOrEmpty(value);
		if (normalized.isBlank() || normalized.equals("change-me-local-only")) {
			throw new IllegalStateException("Production " + label + " must be supplied by the secret manager.");
		}
	}

	private static String valueOrEmpty(String value) {
		return value == null ? "" : value.trim();
	}

}
