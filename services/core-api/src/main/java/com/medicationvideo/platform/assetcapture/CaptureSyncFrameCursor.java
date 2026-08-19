package com.medicationvideo.platform.assetcapture;

import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class CaptureSyncFrameCursor {

	private CaptureSyncFrameCursor() {
	}

	static Position decode(String cursor, UUID revisionId, Set<Integer> cameraFilter) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		try {
			var decoded = new String(Base64.getUrlDecoder().decode(cursor));
			var parts = decoded.split(":");
			if (parts.length < 3) {
				throw invalid();
			}
			var boundRevision = UUID.fromString(parts[0]);
			if (!boundRevision.equals(revisionId)) {
				throw invalid();
			}
			var syncFrameIndex = Long.parseLong(parts[1]);
			var cameraNumber = Integer.parseInt(parts[2]);
			var filter = parts.length > 3 ? parts[3] : "";
			var expectedFilter = cameraFilter == null ? ""
					: cameraFilter.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
			if (!filter.equals(expectedFilter)) {
				throw invalid();
			}
			return new Position(syncFrameIndex, cameraNumber);
		}
		catch (RuntimeException exception) {
			throw invalid();
		}
	}

	static String encode(SynchronizedFrameQueryPort.SynchronizedFrameSlotResponse slot, UUID revisionId,
			Set<Integer> cameraFilter) {
		var filter = cameraFilter == null ? ""
				: cameraFilter.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
		var payload = revisionId + ":" + slot.syncFrameIndex() + ":" + slot.cameraNumber() + ":" + filter;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes());
	}

	static int compare(SynchronizedFrameQueryPort.SynchronizedFrameSlotResponse slot, Position cursor) {
		var frameCompare = Long.compare(slot.syncFrameIndex(), cursor.syncFrameIndex());
		return frameCompare != 0 ? frameCompare : Integer.compare(slot.cameraNumber(), cursor.cameraNumber());
	}

	private static AssetCatalogQueryException invalid() {
		return new AssetCatalogQueryException("INVALID_CURSOR",
				"The frame cursor does not match the requested sync revision or filters.");
	}

	record Position(long syncFrameIndex, int cameraNumber) {
	}

}
