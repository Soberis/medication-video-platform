package com.medicationvideo.platform.assetcapture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class SyncProfileRegistry {

	private static final String TEST_PROFILE_KEY = "TEST/G0";

	private static final String TEST_PROFILE_VERSION = "v1";

	private final SyncProfile testProfile;

	SyncProfileRegistry(
			@Value("${platform.capture-sync.test-profile-sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}") String sha256) {
		this.testProfile = SyncProfile.testOnly(TEST_PROFILE_KEY, TEST_PROFILE_VERSION, sha256, "CAPTURE_SYNC", "v1",
				Rational.of(30, 1), Rational.of(1_000_000_000L, 30), 20_000_000L, "TEST_CLOCK", "TEST_ANCHOR_DRIFT");
	}

	SyncProfile require(String profileKey) {
		if (!TEST_PROFILE_KEY.equals(profileKey)) {
			throw new AssetCatalogQueryException("CAPTURE_SYNC_PROFILE_NOT_APPROVED",
					"Only the approved TEST/G0 synchronization profile is available.");
		}
		return this.testProfile;
	}

	SyncProfile testProfile() {
		return this.testProfile;
	}

}
