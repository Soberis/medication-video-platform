package com.medicationvideo.platform.assetcapture;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Explicit, immutable synchronization parameters. There are intentionally no production
 * defaults in this value object.
 */
public final class SyncProfile {

	public static final String ROUND_HALF_EVEN = "ROUND_HALF_EVEN";

	public static final String EARLIER_PTS_THEN_SOURCE_FRAME_INDEX_THEN_PTS_ORDINAL = "EARLIER_PTS_THEN_SOURCE_FRAME_INDEX_THEN_PTS_ORDINAL";

	private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");

	private final String profileKey;

	private final String profileVersion;

	private final String profileSha256;

	private final String algorithmKey;

	private final String algorithmVersion;

	private final Rational timelineRate;

	private final Rational timelineStepNs;

	private final String roundingRule;

	private final String nearestTieBreak;

	private final long allowedToleranceNs;

	private final String referenceClockSource;

	private final String anchorDriftRule;

	private final SyncProfileApproval approval;

	public SyncProfile(String profileKey, String profileVersion, String profileSha256, String algorithmKey,
			String algorithmVersion, Rational timelineRate, Rational timelineStepNs, String roundingRule,
			String nearestTieBreak, long allowedToleranceNs, String referenceClockSource, String anchorDriftRule,
			SyncProfileApproval approval) {
		this.profileKey = required(profileKey, "profileKey");
		this.profileVersion = required(profileVersion, "profileVersion");
		this.profileSha256 = requiredSha256(profileSha256, "profileSha256");
		this.algorithmKey = required(algorithmKey, "algorithmKey");
		this.algorithmVersion = required(algorithmVersion, "algorithmVersion");
		this.timelineRate = requirePositive(timelineRate, "timelineRate");
		this.timelineStepNs = requirePositive(timelineStepNs, "timelineStepNs");
		if (!Rational.whole(1_000_000_000L).equals(this.timelineRate.multiply(this.timelineStepNs))) {
			throw new IllegalArgumentException("timeline rate and timeline step must describe the same timebase.");
		}
		this.roundingRule = required(roundingRule, "roundingRule");
		if (!ROUND_HALF_EVEN.equals(this.roundingRule)) {
			throw new IllegalArgumentException("Only ROUND_HALF_EVEN is supported by the frozen sync contract.");
		}
		this.nearestTieBreak = required(nearestTieBreak, "nearestTieBreak");
		if (!EARLIER_PTS_THEN_SOURCE_FRAME_INDEX_THEN_PTS_ORDINAL.equals(this.nearestTieBreak)) {
			throw new IllegalArgumentException("The frozen nearest-frame tie-breaker is required.");
		}
		if (allowedToleranceNs < 0) {
			throw new IllegalArgumentException("allowedToleranceNs must not be negative.");
		}
		this.allowedToleranceNs = allowedToleranceNs;
		this.referenceClockSource = required(referenceClockSource, "referenceClockSource");
		this.anchorDriftRule = required(anchorDriftRule, "anchorDriftRule");
		this.approval = Objects.requireNonNull(approval, "approval");
	}

	public static SyncProfile testOnly(String profileKey, String profileVersion, String profileSha256,
			String algorithmKey, String algorithmVersion, Rational timelineRate, Rational timelineStepNs,
			long allowedToleranceNs, String referenceClockSource, String anchorDriftRule) {
		return new SyncProfile(profileKey, profileVersion, profileSha256, algorithmKey, algorithmVersion, timelineRate,
				timelineStepNs, ROUND_HALF_EVEN, EARLIER_PTS_THEN_SOURCE_FRAME_INDEX_THEN_PTS_ORDINAL,
				allowedToleranceNs, referenceClockSource, anchorDriftRule, SyncProfileApproval.TEST_ONLY);
	}

	public String profileKey() {
		return this.profileKey;
	}

	public String profileVersion() {
		return this.profileVersion;
	}

	public String profileSha256() {
		return this.profileSha256;
	}

	public String algorithmKey() {
		return this.algorithmKey;
	}

	public String algorithmVersion() {
		return this.algorithmVersion;
	}

	public Rational timelineRate() {
		return this.timelineRate;
	}

	public Rational timelineStepNs() {
		return this.timelineStepNs;
	}

	public String roundingRule() {
		return this.roundingRule;
	}

	public String nearestTieBreak() {
		return this.nearestTieBreak;
	}

	public long allowedToleranceNs() {
		return this.allowedToleranceNs;
	}

	public String referenceClockSource() {
		return this.referenceClockSource;
	}

	public String anchorDriftRule() {
		return this.anchorDriftRule;
	}

	public SyncProfileApproval approval() {
		return this.approval;
	}

	public boolean isProductionApproved() {
		return this.approval == SyncProfileApproval.PRODUCTION_APPROVED;
	}

	private static String required(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must be explicit.");
		}
		return value;
	}

	private static String requiredSha256(String value, String name) {
		var normalized = required(value, name);
		if (!SHA256.matcher(normalized).matches()) {
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest.");
		}
		return normalized;
	}

	private static Rational requirePositive(Rational value, String name) {
		Objects.requireNonNull(value, name);
		if (value.signum() <= 0) {
			throw new IllegalArgumentException(name + " must be positive.");
		}
		return value;
	}

}
