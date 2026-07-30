package com.medicationvideo.platform.datasetexport;

/**
 * Approved export contracts for the P0 dataset slice. Digests are SHA-256 values of the
 * corresponding files in {@code contracts/profiles} after removing the final newline.
 */
final class DatasetExportProfile {

	static final String CURRENT_KEY = "p0-dataset-export-v2";
	static final String CURRENT_DIGEST =
			"5f86b48ccd02ef8ea0a05c967b85017bb9cd5de59b8f466b7bb93dd552fdcb96";

	static final String LEGACY_V1_KEY = "p0-dataset-export-v1";
	static final String LEGACY_V1_DIGEST =
			"3fac783efda31ad2b68ec4bc18ca7b46c7f6636aeae7bb61c070f4ac1d6da888";

	private DatasetExportProfile() {
	}

	static boolean isApproved(String key, String digest) {
		return CURRENT_KEY.equals(key) && CURRENT_DIGEST.equals(digest)
				|| LEGACY_V1_KEY.equals(key) && LEGACY_V1_DIGEST.equals(digest);
	}
}
