package com.medicationvideo.platform.assetcapture;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable affine mapping from one stream's normalized PTS to Capture time. A single
 * trusted anchor is offset-only; drift is only represented when at least two trusted
 * anchors are available.
 */
public final class StreamTimeMapping {

	private final long anchorSourceNs;

	private final long anchorCaptureNs;

	private final Rational driftPpm;

	private final String method;

	private final String source;

	private final int anchorCount;

	private final boolean trusted;

	public StreamTimeMapping(long anchorSourceNs, long anchorCaptureNs, Rational driftPpm, String method, String source,
			int anchorCount, boolean trusted) {
		if (anchorCount < 0) {
			throw new IllegalArgumentException("anchorCount must not be negative.");
		}
		if (trusted && anchorCount < 1) {
			throw new IllegalArgumentException("A trusted mapping must retain at least one trusted anchor.");
		}
		if (anchorCount < 2 && driftPpm != null) {
			throw new IllegalArgumentException("driftPpm requires at least two trusted anchors.");
		}
		if (anchorCount >= 2 && driftPpm == null) {
			throw new IllegalArgumentException("At least two trusted anchors must retain their measured drift.");
		}
		if (driftPpm != null && driftPpm.add(Rational.whole(1_000_000)).signum() <= 0) {
			throw new IllegalArgumentException("The stream-to-Capture mapping scale must be positive.");
		}
		this.anchorSourceNs = anchorSourceNs;
		this.anchorCaptureNs = anchorCaptureNs;
		this.driftPpm = driftPpm;
		this.method = required(method, "method");
		this.source = required(source, "source");
		this.anchorCount = anchorCount;
		this.trusted = trusted;
	}

	public static StreamTimeMapping offsetOnly(SyncAnchor anchor, String method, String source) {
		Objects.requireNonNull(anchor, "anchor");
		if (!anchor.trusted()) {
			throw new IllegalArgumentException("An offset-only mapping requires a trusted anchor.");
		}
		return new StreamTimeMapping(anchor.sourcePtsNs(), anchor.captureTimestampNs(), null, method, source, 1, true);
	}

	public static StreamTimeMapping fromAnchors(List<SyncAnchor> anchors, String method, String source) {
		Objects.requireNonNull(anchors, "anchors");
		var trusted = anchors.stream()
			.filter(SyncAnchor::trusted)
			.sorted(Comparator.comparingLong(SyncAnchor::sourcePtsNs))
			.toList();
		if (trusted.isEmpty()) {
			throw new IllegalArgumentException("At least one trusted anchor is required.");
		}
		if (trusted.size() == 1) {
			return offsetOnly(trusted.getFirst(), method, source);
		}
		if (trusted.size() > 2) {
			throw new IllegalArgumentException(
					"More than two trusted anchors require an approved fitting method with residual and outlier evidence.");
		}
		var first = trusted.getFirst();
		var last = trusted.getLast();
		var sourceDelta = BigInteger.valueOf(last.sourcePtsNs()).subtract(BigInteger.valueOf(first.sourcePtsNs()));
		if (sourceDelta.signum() == 0) {
			throw new IllegalArgumentException("Two trusted anchors must have distinct source PTS values.");
		}
		var captureDelta = BigInteger.valueOf(last.captureTimestampNs())
			.subtract(BigInteger.valueOf(first.captureTimestampNs()));
		var scale = new Rational(captureDelta, sourceDelta);
		var driftPpm = scale.subtract(Rational.whole(1)).multiply(Rational.whole(1_000_000));
		return new StreamTimeMapping(first.sourcePtsNs(), first.captureTimestampNs(), driftPpm, method, source,
				trusted.size(), true);
	}

	public long anchorSourceNs() {
		return this.anchorSourceNs;
	}

	public long anchorCaptureNs() {
		return this.anchorCaptureNs;
	}

	public Rational driftPpm() {
		return this.driftPpm;
	}

	public String method() {
		return this.method;
	}

	public String source() {
		return this.source;
	}

	public int anchorCount() {
		return this.anchorCount;
	}

	public boolean trusted() {
		return this.trusted;
	}

	public long mapSourceToCaptureTime(long sourcePtsNs) {
		requireTrusted();
		var relative = new Rational(BigInteger.valueOf(sourcePtsNs).subtract(BigInteger.valueOf(this.anchorSourceNs)),
				BigInteger.ONE)
			.multiply(scale());
		return Math.addExact(this.anchorCaptureNs, relative.roundHalfEvenLong());
	}

	public Rational mapCaptureToSourceTime(long captureTimestampNs) {
		requireTrusted();
		return Rational.whole(this.anchorSourceNs)
			.add(new Rational(BigInteger.valueOf(captureTimestampNs).subtract(BigInteger.valueOf(this.anchorCaptureNs)),
					BigInteger.ONE)
				.divide(scale()));
	}

	Rational scale() {
		var ppm = this.driftPpm == null ? Rational.whole(0) : this.driftPpm;
		var scale = Rational.whole(1).add(ppm.divide(Rational.whole(1_000_000)));
		if (scale.signum() <= 0) {
			throw new IllegalStateException("The stream-to-Capture mapping scale must be positive.");
		}
		return scale;
	}

	private void requireTrusted() {
		if (!this.trusted || this.anchorCount < 1) {
			throw new IllegalStateException(SynchronizationUnavailableException.TRUSTED_MAPPING_REQUIRED);
		}
	}

	private static String required(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must be explicit.");
		}
		return value;
	}

}
