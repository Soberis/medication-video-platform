package com.medicationvideo.platform.assetcapture;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CaptureGroupQueryService {

	private static final String CURSOR_KIND = "capture-group-summary";

	private final CaptureSessionRepository sessionRepository;

	private final CameraSetSnapshotRepository snapshotRepository;

	private final CameraSetSnapshotMemberRepository snapshotMemberRepository;

	private final CameraStreamRepository streamRepository;

	private final NamedParameterJdbcTemplate jdbcTemplate;

	CaptureGroupQueryService(CaptureSessionRepository sessionRepository, CameraSetSnapshotRepository snapshotRepository,
			CameraSetSnapshotMemberRepository snapshotMemberRepository, CameraStreamRepository streamRepository,
			NamedParameterJdbcTemplate jdbcTemplate) {
		this.sessionRepository = sessionRepository;
		this.snapshotRepository = snapshotRepository;
		this.snapshotMemberRepository = snapshotMemberRepository;
		this.streamRepository = streamRepository;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(readOnly = true)
	CaptureSessionResponse get(UUID projectId, UUID captureGroupId) {
		var session = this.sessionRepository
			.findByCaptureSessionIdAndProjectIdAndCaptureGroupWorkflowTrue(captureGroupId, projectId)
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_GROUP_NOT_FOUND",
					"The capture group was not found in the current project."));
		return response(session);
	}

	@Transactional(readOnly = true)
	CaptureGroupListResponse list(UUID projectId, String cursor, int limit) {
		var decodedCursor = cursor == null ? null : AssetCatalogCursor.decode(CURSOR_KIND, cursor);
		var sql = new StringBuilder("""
				SELECT session.capture_session_id AS capture_group_id,
				       session.capture_group_key,
				       session.device_time_utc,
				       session.receipt_state,
				       session.expected_camera_count,
				       session.received_camera_count,
				       ARRAY(
				           SELECT member.camera_index + 1
				           FROM camera_set_snapshot snapshot
				           JOIN camera_set_snapshot_member member
				             ON member.camera_set_snapshot_id = snapshot.camera_set_snapshot_id
				           WHERE snapshot.capture_session_id = session.capture_session_id
				             AND member.enabled = TRUE
				             AND NOT EXISTS (
				                 SELECT 1
				                 FROM camera_stream stream
				                 WHERE stream.capture_session_id = session.capture_session_id
				                   AND stream.camera_set_snapshot_member_id = member.camera_set_snapshot_member_id
				             )
				           ORDER BY member.camera_index
				       ) AS missing_camera_numbers,
				       session.created_at
				FROM capture_session session
				WHERE session.project_id = :projectId
				  AND session.capture_group_workflow = TRUE
				""");
		var parameters = new MapSqlParameterSource("projectId", projectId);
		if (decodedCursor != null) {
			sql.append(" AND (session.created_at < :cursorCreatedAt OR (session.created_at = :cursorCreatedAt"
					+ " AND session.capture_session_id < :cursorId))\n");
			parameters.addValue("cursorCreatedAt", java.sql.Timestamp.from(decodedCursor.recordedAt()));
			parameters.addValue("cursorId", decodedCursor.id());
		}
		sql.append(" ORDER BY session.created_at DESC, session.capture_session_id DESC LIMIT :pageSize");
		parameters.addValue("pageSize", limit + 1);

		var rows = this.jdbcTemplate.query(sql.toString(), parameters, CaptureGroupQueryService::summary);
		var hasNext = rows.size() > limit;
		var items = hasNext ? new ArrayList<>(rows.subList(0, limit)) : new ArrayList<>(rows);
		var nextCursor = hasNext
				? AssetCatalogCursor.encode(CURSOR_KIND, items.getLast().createdAt(), items.getLast().captureGroupId())
				: null;
		return new CaptureGroupListResponse(List.copyOf(items), nextCursor);
	}

	private static CaptureGroupSummary summary(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CaptureGroupSummary(resultSet.getObject("capture_group_id", UUID.class),
				resultSet.getString("capture_group_key"), instant(resultSet, "device_time_utc"),
				CaptureGroupReceiptState.valueOf(resultSet.getString("receipt_state")),
				resultSet.getInt("expected_camera_count"), resultSet.getInt("received_camera_count"),
				integerArray(resultSet, "missing_camera_numbers"), instant(resultSet, "created_at"));
	}

	private static List<Integer> integerArray(ResultSet resultSet, String column) throws SQLException {
		var array = resultSet.getArray(column);
		if (array == null) {
			return List.of();
		}
		try {
			return Arrays.stream((Object[]) array.getArray()).map(value -> ((Number) value).intValue()).toList();
		}
		finally {
			array.free();
		}
	}

	private static Instant instant(ResultSet resultSet, String column) throws SQLException {
		var value = resultSet.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private CaptureSessionResponse response(CaptureSession session) {
		var snapshot = this.snapshotRepository.findByCaptureSessionId(session.getCaptureSessionId())
			.orElseThrow(() -> new IllegalStateException("Capture session has no frozen camera set snapshot."));
		var members = this.snapshotMemberRepository
			.findAllByCameraSetSnapshotIdOrderByCameraIndexAsc(snapshot.getCameraSetSnapshotId());
		var streams = this.streamRepository
			.findAllByCaptureSessionIdOrderByCameraIndexAsc(session.getCaptureSessionId());
		return CaptureSessionResponse.from(session, snapshot, members, streams);
	}

}
