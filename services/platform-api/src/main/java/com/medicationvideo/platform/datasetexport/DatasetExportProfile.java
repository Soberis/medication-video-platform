package com.medicationvideo.platform.datasetexport;

/**
 * Approved minimum export contract for the P0 dataset slice. The digest is the SHA-256 of
 * {@code contracts/profiles/p0-dataset-export-v1.json} after removing its final newline.
 */
final class DatasetExportProfile {

	static final String PROFILE_KEY = "p0-dataset-export-v1";
	static final String PROFILE_DIGEST =
			"3fac783efda31ad2b68ec4bc18ca7b46c7f6636aeae7bb61c070f4ac1d6da888";

	private DatasetExportProfile() {
	}
}
