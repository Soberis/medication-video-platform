package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DatasetExportProfileTests {

	@Test
	void acceptsOnlyApprovedProfilePairs() {
		assertTrue(DatasetExportProfile.isApproved(
				DatasetExportProfile.CURRENT_KEY,
				DatasetExportProfile.CURRENT_DIGEST));
		assertTrue(DatasetExportProfile.isApproved(
				DatasetExportProfile.LEGACY_V1_KEY,
				DatasetExportProfile.LEGACY_V1_DIGEST));

		assertFalse(DatasetExportProfile.isApproved(
				DatasetExportProfile.CURRENT_KEY,
				DatasetExportProfile.LEGACY_V1_DIGEST));
		assertFalse(DatasetExportProfile.isApproved(
				DatasetExportProfile.LEGACY_V1_KEY,
				DatasetExportProfile.CURRENT_DIGEST));
		assertFalse(DatasetExportProfile.isApproved("p0-dataset-export-v3", "0".repeat(64)));
	}
}
