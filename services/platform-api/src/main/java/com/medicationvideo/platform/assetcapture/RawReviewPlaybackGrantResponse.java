package com.medicationvideo.platform.assetcapture;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

record RawReviewPlaybackGrantResponse(
		UUID grantId,
		String purpose,
		URI mediaUrl,
		Instant expiresAt,
		String contentType) {
}
