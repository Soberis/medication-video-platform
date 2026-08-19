package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditEventQueryService;

@Service
class ClipAssetActivityQueryService {

	private final AssetCatalogQueryService catalogQueryService;

	private final AuditEventQueryService auditEventQueryService;

	ClipAssetActivityQueryService(AssetCatalogQueryService catalogQueryService,
			AuditEventQueryService auditEventQueryService) {
		this.catalogQueryService = catalogQueryService;
		this.auditEventQueryService = auditEventQueryService;
	}

	@Transactional(readOnly = true)
	ClipAssetActivityResponse get(UUID projectId, UUID clipAssetId, String cursor, int limit) {
		this.catalogQueryService.getClipAsset(projectId, clipAssetId);
		try {
			var page = this.auditEventQueryService.findClipAssetActivity(projectId, clipAssetId, cursor, limit);
			return new ClipAssetActivityResponse(
					page.items().stream().map(ClipAssetActivityItemResponse::from).toList(), page.nextCursor());
		}
		catch (IllegalArgumentException exception) {
			throw new AssetCatalogQueryException("INVALID_CURSOR", exception.getMessage());
		}
	}

}

record ClipAssetActivityResponse(List<ClipAssetActivityItemResponse> items, String nextCursor) {

	ClipAssetActivityResponse {
		items = List.copyOf(items);
	}
}

record ClipAssetActivityItemResponse(UUID eventId, Instant occurredAtUtc, String actorType, String actorId,
		String action, String objectType, String objectId, String objectVersion, String outcome, String reasonCode,
		String requestId) {

	static ClipAssetActivityItemResponse from(AuditEventQueryService.AuditEventSummary event) {
		return new ClipAssetActivityItemResponse(event.eventId(), event.occurredAtUtc(), event.actorType(),
				event.actorId(), event.action(), event.objectType(), event.objectId(), event.objectVersion(),
				event.outcome(), event.reasonCode(), event.requestId());
	}
}
