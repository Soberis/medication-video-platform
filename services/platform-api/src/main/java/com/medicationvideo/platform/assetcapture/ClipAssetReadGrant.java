package com.medicationvideo.platform.assetcapture;

import java.net.URI;
import java.time.Instant;

public record ClipAssetReadGrant(URI inputUrl, Instant expiresAt) {
}
