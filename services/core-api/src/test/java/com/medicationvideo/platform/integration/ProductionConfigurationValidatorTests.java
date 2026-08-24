package com.medicationvideo.platform.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProductionConfigurationValidatorTests {

	@Test
	void acceptsExplicitTlsAndNonLocalEndpoints() {
		assertDoesNotThrow(() -> ProductionConfigurationValidator.validate(true, true, "https://objects.example",
				"jdbc:postgresql://postgres.internal:5432/platform", "rabbit-secret", "access-key", "secret-key"));
	}

	@Test
	void rejectsInsecureOrLocalProductionDefaults() {
		assertThrows(IllegalStateException.class,
				() -> ProductionConfigurationValidator.validate(false, true, "https://objects.example",
						"jdbc:postgresql://postgres.internal:5432/platform", "rabbit-secret", "access-key",
						"secret-key"));
		assertThrows(IllegalStateException.class,
				() -> ProductionConfigurationValidator.validate(true, false, "https://objects.example",
						"jdbc:postgresql://postgres.internal:5432/platform", "rabbit-secret", "access-key",
						"secret-key"));
		assertThrows(IllegalStateException.class,
				() -> ProductionConfigurationValidator.validate(true, true, "http://objects.internal:9000",
						"jdbc:postgresql://postgres.internal:5432/platform", "rabbit-secret", "access-key",
						"secret-key"));
		assertThrows(IllegalStateException.class,
				() -> ProductionConfigurationValidator.validate(true, true, "https://objects.example",
						"jdbc:postgresql://localhost:5432/platform", "rabbit-secret", "access-key", "secret-key"));
	}

}
