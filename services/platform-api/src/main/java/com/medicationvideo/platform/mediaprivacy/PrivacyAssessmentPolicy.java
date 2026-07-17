package com.medicationvideo.platform.mediaprivacy;

/**
 * The approved human-assessment policy for the first P0 privacy slice. The digest is the SHA-256
 * of {@code contracts/profiles/privacy-assessment-policy-v1.json} after removing its final newline.
 * A semantic policy change requires a new key and digest so prior assessments remain reproducible.
 */
final class PrivacyAssessmentPolicy {

	static final String POLICY_KEY = "privacy-assessment-policy-v1";
	static final String POLICY_DIGEST =
			"d5d18c436d401a223832f1c85b6ff076de01a9dddeecb879268ff89bc5719517";

	private PrivacyAssessmentPolicy() {
	}
}
