package com.medicationvideo.platform.assetcapture;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

record AssetCatalogCursor(Instant recordedAt, UUID id) {

	private static final String VERSION = "v1";

	static String encode(String kind, Instant recordedAt, UUID id) {
		var value = String.join(
				"|",
				VERSION,
				kind,
				Long.toString(recordedAt.getEpochSecond()),
				Integer.toString(recordedAt.getNano()),
				id.toString());
		return Base64.getUrlEncoder()
				.withoutPadding()
				.encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	static AssetCatalogCursor decode(String kind, String cursor) {
		try {
			var decoded = new String(
					Base64.getUrlDecoder().decode(cursor),
					StandardCharsets.UTF_8);
			var parts = decoded.split("\\|", -1);
			if (parts.length != 5 || !VERSION.equals(parts[0]) || !kind.equals(parts[1])) {
				throw invalid();
			}
			var seconds = Long.parseLong(parts[2]);
			var nanos = Integer.parseInt(parts[3]);
			return new AssetCatalogCursor(Instant.ofEpochSecond(seconds, nanos), UUID.fromString(parts[4]));
		}
		catch (IllegalArgumentException | DateTimeException exception) {
			throw invalid();
		}
	}

	private static AssetCatalogQueryException invalid() {
		return new AssetCatalogQueryException(
				"INVALID_CURSOR",
				"The pagination cursor is invalid or belongs to another resource.");
	}
}
