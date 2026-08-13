package com.medicationvideo.platform.assetcapture;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
class ManifestSynchronizedFrameQueryPort implements SynchronizedFrameQueryPort {

	@Override
	public FrameQueryPage query(CaptureSyncArtifact artifact, FrameQueryRequest request) {
		var manifest = artifact.getArtifactManifest();
		var rawIndex = manifest.get("query_index");
		if (!(rawIndex instanceof Map<?, ?> index) || !(index.get("frames") instanceof List<?> frames)) {
			throw new AssetCatalogQueryException("CAPTURE_SYNC_QUERY_INDEX_UNAVAILABLE",
					"The synchronized-frame query index is unavailable for this revision.");
		}
		var cameraFilter = request.cameraNumbers() == null || request.cameraNumbers().isEmpty() ? null
				: new LinkedHashSet<>(request.cameraNumbers());
		var cursor = CaptureSyncFrameCursor.decode(request.cursor(), request.syncRevisionId(), cameraFilter);
		var collected = new ArrayList<SynchronizedFrameSlotResponse>();
		for (var rawFrame : frames) {
			if (!(rawFrame instanceof Map<?, ?> frame)) {
				continue;
			}
			var slot = toSlot(frame);
			if (!request.syncRevisionId().equals(slot.syncRevisionId())) {
				continue;
			}
			if (request.fromSyncFrameIndex() != null && slot.syncFrameIndex() < request.fromSyncFrameIndex()) {
				continue;
			}
			if (cameraFilter != null && !cameraFilter.contains(slot.cameraNumber())) {
				continue;
			}
			collected.add(slot.toResponse());
		}
		collected.sort(Comparator.comparingLong(SynchronizedFrameSlotResponse::syncFrameIndex)
			.thenComparingInt(SynchronizedFrameSlotResponse::cameraNumber));
		var page = new ArrayList<SynchronizedFrameSlotResponse>();
		for (var slot : collected) {
			if (cursor != null && CaptureSyncFrameCursor.compare(slot, cursor) <= 0) {
				continue;
			}
			page.add(slot);
			if (page.size() >= request.limit()) {
				break;
			}
		}
		var nextCursor = page.size() == request.limit()
				? CaptureSyncFrameCursor.encode(page.getLast(), request.syncRevisionId(), cameraFilter) : null;
		return new FrameQueryPage(List.copyOf(page), nextCursor);
	}

	private static IndexedSlot toSlot(Map<?, ?> frame) {
		return new IndexedSlot(longValue(frame, "sync_frame_index"), longValue(frame, "capture_timestamp_ns"),
				intValue(frame, "camera_number"), uuid(frame, "camera_stream_id"), uuid(frame, "video_id"),
				longOrNull(frame, "source_frame_index"), longOrNull(frame, "source_pts_ns"),
				intOrNull(frame, "source_pts_ordinal"), string(frame, "frame_state"),
				stringOrNull(frame, "frame_reason_code"), longOrNull(frame, "sync_error_ns"),
				uuidRequired(frame, "sync_revision_id"));
	}

	private static long longValue(Map<?, ?> frame, String key) {
		var value = frame.get(key);
		if (!(value instanceof Number number)) {
			throw new AssetCatalogQueryException("CAPTURE_SYNC_QUERY_INDEX_INVALID",
					"The synchronized-frame query index is invalid.");
		}
		return number.longValue();
	}

	private static Long longOrNull(Map<?, ?> frame, String key) {
		var value = frame.get(key);
		return value == null ? null : longValue(frame, key);
	}

	private static int intValue(Map<?, ?> frame, String key) {
		return Math.toIntExact(longValue(frame, key));
	}

	private static Integer intOrNull(Map<?, ?> frame, String key) {
		var value = frame.get(key);
		return value == null ? null : intValue(frame, key);
	}

	private static String string(Map<?, ?> frame, String key) {
		var value = frame.get(key);
		if (!(value instanceof String text) || text.isBlank()) {
			throw new AssetCatalogQueryException("CAPTURE_SYNC_QUERY_INDEX_INVALID",
					"The synchronized-frame query index is invalid.");
		}
		return text;
	}

	private static String stringOrNull(Map<?, ?> frame, String key) {
		var value = frame.get(key);
		return value instanceof String text ? text : null;
	}

	private static UUID uuid(Map<?, ?> frame, String key) {
		var value = frame.get(key);
		if (value == null) {
			return null;
		}
		if (!(value instanceof String text)) {
			throw new AssetCatalogQueryException("CAPTURE_SYNC_QUERY_INDEX_INVALID",
					"The synchronized-frame query index is invalid.");
		}
		return UUID.fromString(text);
	}

	private static UUID uuidRequired(Map<?, ?> frame, String key) {
		var value = uuid(frame, key);
		if (value == null) {
			throw new AssetCatalogQueryException("CAPTURE_SYNC_QUERY_INDEX_INVALID",
					"The synchronized-frame query index is invalid.");
		}
		return value;
	}

	private record IndexedSlot(long syncFrameIndex, long captureTimestampNs, int cameraNumber, UUID cameraStreamId,
			UUID videoId, Long sourceFrameIndex, Long sourcePtsNs, Integer sourcePtsOrdinal, String frameState,
			String frameReasonCode, Long syncErrorNs, UUID syncRevisionId) {

		SynchronizedFrameSlotResponse toResponse() {
			return new SynchronizedFrameSlotResponse(this.syncFrameIndex, this.captureTimestampNs, this.cameraNumber,
					this.cameraStreamId, this.videoId, this.sourceFrameIndex, this.sourcePtsNs, this.sourcePtsOrdinal,
					this.frameState, this.frameReasonCode, this.syncErrorNs);
		}
	}

}
