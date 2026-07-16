package com.medicationvideo.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

	private static final Set<String> EXPECTED_MODULES = Set.of(
			"aicandidate",
			"annotationreview",
			"assetcapture",
			"datasetexport",
			"governance",
			"iamproject",
			"integration",
			"mediaprivacy",
			"qualitycleaning");

	@Test
	void detectsTheFrozenModuleSet() {
		var modules = ApplicationModules.of(PlatformApiApplication.class);
		var detectedModules = modules.stream()
				.map(module -> module.getIdentifier().toString())
				.collect(Collectors.toUnmodifiableSet());

		assertEquals(EXPECTED_MODULES, detectedModules);
	}

	@Test
	void verifiesModuleBoundaries() {
		ApplicationModules.of(PlatformApiApplication.class).verify();
	}
}
