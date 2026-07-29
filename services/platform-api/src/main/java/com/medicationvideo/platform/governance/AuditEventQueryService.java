package com.medicationvideo.platform.governance;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only, minimized audit views for approved product queries.
 */
@Service
public class AuditEventQueryService {

	private static final String CLIP_ASSET_ACTIVITY_CURSOR_KIND = "clip-asset-activity:v1";

	private final AuditEventRepository repository;

	AuditEventQueryService(AuditEventRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public ClipAssetActivityPage findClipAssetActivity(
			UUID projectId, UUID clipAssetId, String cursor, int limit) {
		var decodedCursor = cursor == null ? null : decodeCursor(clipAssetId, cursor);
		var pageable = PageRequest.of(0, limit + 1);
		var rows = decodedCursor == null
				? this.repository.findClipAssetActivity(projectId, clipAssetId.toString(), pageable)
				: this.repository.findClipAssetActivityBefore(
						projectId,
						clipAssetId.toString(),
						decodedCursor.occurredAtUtc(),
						decodedCursor.eventId(),
						pageable);
		var page = rows.stream().limit(limit).map(AuditEventSummary::from).toList();
		var nextCursor = rows.size() > limit
				? encodeCursor(clipAssetId, page.getLast().occurredAtUtc(), page.getLast().eventId())
				: null;
		return new ClipAssetActivityPage(page, nextCursor);
	}

	private static String encodeCursor(UUID clipAssetId, Instant occurredAtUtc, UUID eventId) {
		var value = String.join(
				":",
				CLIP_ASSET_ACTIVITY_CURSOR_KIND,
				clipAssetId.toString(),
				Long.toString(occurredAtUtc.getEpochSecond()),
				Integer.toString(occurredAtUtc.getNano()),
				eventId.toString());
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static ActivityCursor decodeCursor(UUID clipAssetId, String cursor) {
		try {
			var decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			var parts = decoded.split(":", 6);
			if (parts.length != 6
					|| !parts[0].equals("clip-asset-activity")
					|| !parts[1].equals("v1")
					|| !UUID.fromString(parts[2]).equals(clipAssetId)) {
				throw new IllegalArgumentException("Unexpected cursor kind.");
			}
			var epochSecond = Long.parseLong(parts[3]);
			var nanos = Integer.parseInt(parts[4]);
			if (nanos < 0 || nanos > 999_999_999) {
				throw new IllegalArgumentException("Invalid cursor nanoseconds.");
			}
			var occurredAtUtc = Instant.ofEpochSecond(epochSecond, nanos);
			return new ActivityCursor(occurredAtUtc, UUID.fromString(parts[5]));
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException(
					"The activity pagination cursor is invalid or belongs to another resource.",
					exception);
		}
	}

	private record ActivityCursor(Instant occurredAtUtc, UUID eventId) {
	}

	public record ClipAssetActivityPage(List<AuditEventSummary> items, String nextCursor) {

		public ClipAssetActivityPage {
			items = List.copyOf(items);
		}
	}

	public record AuditEventSummary(
			UUID eventId,
			Instant occurredAtUtc,
			String actorType,
			String actorId,
			String action,
			String objectType,
			String objectId,
			String objectVersion,
			String outcome,
			String reasonCode,
			String requestId) {

		private static AuditEventSummary from(AuditEvent event) {
			return new AuditEventSummary(
					event.getEventId(),
					event.getOccurredAtUtc(),
					event.getActorType().name(),
					event.getActorId(),
					event.getAction(),
					event.getObjectType(),
					event.getObjectId(),
					event.getObjectVersion(),
					event.getOutcome().name(),
					event.getReasonCode(),
					event.getRequestId());
		}
	}
}
