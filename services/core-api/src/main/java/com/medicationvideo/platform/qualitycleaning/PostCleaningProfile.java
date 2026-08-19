package com.medicationvideo.platform.qualitycleaning;

import java.util.List;

/**
 * Approved deterministic rules for the first post-annotation cleaning slice. The digest
 * is the SHA-256 of {@code contracts/profiles/p0-post-annotation-cleaning-v1.json} after
 * removing its final newline.
 */
final class PostCleaningProfile {

	static final String RULE_SET_KEY = "p0-post-annotation";
	static final String RULE_SET_VERSION = "p0-post-annotation.v1";
	static final String RULE_SET_DIGEST = "7c1aa3606467412b84964a3455af8695bd73c85cd9b5d08ce079061ed840fa0a";
	static final List<String> EXECUTED_CHECKS = List.of("APPROVED_REVIEW_BOUND", "ASSET_INPUT_DIGEST_BOUND",
			"ANNOTATION_DOCUMENT_DIGEST_BOUND", "QUALITY_REVIEW_QUALIFIED", "LABEL_SCHEMA_BOUND",
			"PRIMARY_STEP_LABEL_ALLOWED", "SEGMENT_TIME_RANGE_VALID", "PRIMARY_STEP_NO_OVERLAP",
			"VERIFIED_MEDIA_DURATION_BOUND_IF_AVAILABLE");

	private PostCleaningProfile() {
	}

}
