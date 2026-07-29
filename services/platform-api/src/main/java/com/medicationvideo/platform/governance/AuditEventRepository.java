package com.medicationvideo.platform.governance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

	@Query(value = """
			SELECT event.*
			FROM audit_event event
			WHERE event.project_id = :projectId
			  AND (
			    (event.object_type = 'ClipAsset' AND event.object_id = :clipAssetId)
			    OR event.details_json ->> 'clip_asset_id' = :clipAssetId
			  )
			ORDER BY event.occurred_at_utc DESC, event.event_id DESC
			""", nativeQuery = true)
	List<AuditEvent> findClipAssetActivity(
			@Param("projectId") UUID projectId,
			@Param("clipAssetId") String clipAssetId,
			Pageable pageable);

	@Query(value = """
			SELECT event.*
			FROM audit_event event
			WHERE event.project_id = :projectId
			  AND (
			    (event.object_type = 'ClipAsset' AND event.object_id = :clipAssetId)
			    OR event.details_json ->> 'clip_asset_id' = :clipAssetId
			  )
			  AND (
			    event.occurred_at_utc < :cursorOccurredAt
			    OR (
			      event.occurred_at_utc = :cursorOccurredAt
			      AND event.event_id < :cursorEventId
			    )
			  )
			ORDER BY event.occurred_at_utc DESC, event.event_id DESC
			""", nativeQuery = true)
	List<AuditEvent> findClipAssetActivityBefore(
			@Param("projectId") UUID projectId,
			@Param("clipAssetId") String clipAssetId,
			@Param("cursorOccurredAt") Instant cursorOccurredAt,
			@Param("cursorEventId") UUID cursorEventId,
			Pageable pageable);
}
