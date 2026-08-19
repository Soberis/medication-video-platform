package com.medicationvideo.platform.assetcapture;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetCatalogQueryService {

	private static final String EVENT_LOG_CURSOR_KIND = "capture-event-log";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private final CameraSetSnapshotMemberRepository snapshotMemberRepository;

	private final CameraStreamRepository streamRepository;

	private final ClipAssetRepository clipAssetRepository;

	private final CaptureEventLogRepository captureEventLogRepository;

	public AssetCatalogQueryService(NamedParameterJdbcTemplate jdbcTemplate,
			CameraSetSnapshotMemberRepository snapshotMemberRepository, CameraStreamRepository streamRepository,
			ClipAssetRepository clipAssetRepository, CaptureEventLogRepository captureEventLogRepository) {
		this.jdbcTemplate = jdbcTemplate;
		this.snapshotMemberRepository = snapshotMemberRepository;
		this.streamRepository = streamRepository;
		this.clipAssetRepository = clipAssetRepository;
		this.captureEventLogRepository = captureEventLogRepository;
	}

	@Transactional(readOnly = true)
	ClipAssetReadResponse getClipAsset(UUID projectId, UUID clipAssetId) {
		var asset = this.clipAssetRepository.findByClipAssetIdAndProjectId(clipAssetId, projectId)
			.filter(existing -> !existing.isDeleted())
			.orElseThrow(() -> new AssetCatalogQueryException("CLIP_ASSET_NOT_FOUND",
					"The clip asset was not found in the current project."));
		var binding = this.streamRepository.findByClipAssetId(clipAssetId).map(stream -> {
			var member = this.snapshotMemberRepository.findById(stream.getCameraSetSnapshotMemberId())
				.orElseThrow(() -> new IllegalStateException("A camera stream has no matching snapshot member."));
			return binding(stream, member);
		}).orElse(null);
		return new ClipAssetReadResponse(asset.getClipAssetId(), asset.getBatchId(), asset.getSourceUploadFileId(),
				asset.getClientFileId(), asset.getContentType(), asset.getSizeBytes(), asset.getSha256(),
				asset.getStatus(), asset.getFolderId(), binding, asset.getCreatedAt(), asset.getUpdatedAt());
	}

	@Transactional(readOnly = true)
	CaptureEventLogListResponse listCaptureEventLogs(UUID projectId, UUID batchId, String deviceId, Instant indexedFrom,
			Instant indexedBefore, String cursor, int limit) {
		validateTimeRange(indexedFrom, indexedBefore, "indexed_from", "indexed_before");
		var decodedCursor = decodeCursor(EVENT_LOG_CURSOR_KIND, cursor);
		var sql = new StringBuilder("""
				SELECT capture_event_log_id,
				       batch_id,
				       source_upload_file_id,
				       client_file_id,
				       device_id,
				       content_type,
				       size_bytes,
				       sha256,
				       indexed_at
				FROM capture_event_log
				WHERE project_id = :projectId
				""");
		var parameters = new MapSqlParameterSource("projectId", projectId);
		if (batchId != null) {
			sql.append(" AND batch_id = :batchId\n");
			parameters.addValue("batchId", batchId);
		}
		if (deviceId != null) {
			sql.append(" AND device_id = :deviceId\n");
			parameters.addValue("deviceId", deviceId);
		}
		if (indexedFrom != null) {
			sql.append(" AND indexed_at >= :indexedFrom\n");
			parameters.addValue("indexedFrom", Timestamp.from(indexedFrom));
		}
		if (indexedBefore != null) {
			sql.append(" AND indexed_at < :indexedBefore\n");
			parameters.addValue("indexedBefore", Timestamp.from(indexedBefore));
		}
		if (decodedCursor != null) {
			sql.append("""
					 AND (indexed_at < :cursorIndexedAt
					      OR (indexed_at = :cursorIndexedAt
					          AND capture_event_log_id < :cursorId))
					""");
			parameters.addValue("cursorIndexedAt", Timestamp.from(decodedCursor.recordedAt()));
			parameters.addValue("cursorId", decodedCursor.id());
		}
		sql.append(" ORDER BY indexed_at DESC, capture_event_log_id DESC LIMIT :fetchLimit");
		parameters.addValue("fetchLimit", limit + 1);

		var rows = this.jdbcTemplate.query(sql.toString(), parameters, this::mapCaptureEventLog);
		var page = boundedPage(rows, limit);
		var nextCursor = rows.size() > limit ? AssetCatalogCursor.encode(EVENT_LOG_CURSOR_KIND,
				page.getLast().indexedAt(), page.getLast().captureEventLogId()) : null;
		return new CaptureEventLogListResponse(page, nextCursor);
	}

	@Transactional(readOnly = true)
	CaptureEventLogReadResponse getCaptureEventLog(UUID projectId, UUID captureEventLogId) {
		return this.captureEventLogRepository.findByCaptureEventLogIdAndProjectId(captureEventLogId, projectId)
			.map(CaptureEventLogReadResponse::from)
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_EVENT_LOG_NOT_FOUND",
					"The capture event log was not found in the current project."));
	}

	/**
	 * Cross-module read-only entry point. Returns a frozen snapshot of the fields a
	 * downstream module (e.g. mediaprivacy) needs to schedule work against a clip asset.
	 * Does not leak storage locations, ETag or batch identifiers.
	 */
	@Transactional(readOnly = true)
	public ClipAssetInputSummary getClipAssetInputSummary(UUID projectId, UUID clipAssetId) {
		var asset = this.clipAssetRepository.findByClipAssetIdAndProjectId(clipAssetId, projectId)
			.filter(existing -> !existing.isDeleted())
			.orElseThrow(() -> new AssetCatalogQueryException("CLIP_ASSET_NOT_FOUND",
					"The clip asset was not found in the current project."));
		var binding = this.streamRepository.findByClipAssetId(clipAssetId)
			.map(stream -> this.snapshotMemberRepository.findById(stream.getCameraSetSnapshotMemberId())
				.orElseThrow(() -> new IllegalStateException("A camera stream has no matching snapshot member.")));
		var cameraRole = binding.map(CameraSetSnapshotMember::getCameraRole).orElse(null);
		var enabledForHandDetection = binding.map(CameraSetSnapshotMember::isEnabledForHandDetection).orElse(false);
		return new ClipAssetInputSummary(asset.getClipAssetId(), asset.getProjectId(), asset.getContentType(),
				asset.getSizeBytes(), asset.getSha256(), asset.getStatus(), cameraRole, enabledForHandDetection,
				asset.getCreatedAt(), asset.getUpdatedAt());
	}

	private CaptureEventLogReadResponse mapCaptureEventLog(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CaptureEventLogReadResponse(resultSet.getObject("capture_event_log_id", UUID.class),
				resultSet.getObject("batch_id", UUID.class), resultSet.getObject("source_upload_file_id", UUID.class),
				resultSet.getObject("client_file_id", UUID.class), resultSet.getString("device_id"),
				resultSet.getString("content_type"), resultSet.getLong("size_bytes"), resultSet.getString("sha256"),
				instant(resultSet, "indexed_at"));
	}

	private static ClipAssetSessionBindingResponse binding(CameraStream stream, CameraSetSnapshotMember member) {
		return new ClipAssetSessionBindingResponse(stream.getCaptureSessionId(), stream.getCameraStreamId(),
				stream.getCameraChannelId(), stream.getCameraIndex(), member.getCameraRole(), member.getMountPosition(),
				stream.getRecordStartUtc(), stream.getTimeOffsetMs(), stream.getDeclaredSyncStatus(),
				stream.getStreamStatus(), stream.getQualityStatus(), member.isEnabledForHandDetection());
	}

	private static Instant instant(ResultSet resultSet, String column) throws SQLException {
		var value = resultSet.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static AssetCatalogCursor decodeCursor(String kind, String cursor) {
		return cursor == null ? null : AssetCatalogCursor.decode(kind, cursor);
	}

	private static void validateTimeRange(Instant from, Instant before, String fromParameter, String beforeParameter) {
		if (from != null && before != null && !from.isBefore(before)) {
			throw new AssetCatalogQueryException("INVALID_TIME_RANGE",
					fromParameter + " must be earlier than " + beforeParameter + ".");
		}
	}

	private static <T> List<T> boundedPage(List<T> rows, int limit) {
		if (rows.size() <= limit) {
			return List.copyOf(rows);
		}
		return List.copyOf(new ArrayList<>(rows.subList(0, limit)));
	}

}
