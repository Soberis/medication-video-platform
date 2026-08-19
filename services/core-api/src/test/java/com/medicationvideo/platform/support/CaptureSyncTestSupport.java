package com.medicationvideo.platform.support;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

public final class CaptureSyncTestSupport {

	private CaptureSyncTestSupport() {
	}

	public static void clearCaptureSyncTables(JdbcTemplate jdbc) {
		jdbc.update("UPDATE capture_session SET current_sync_revision_id = NULL");
		jdbc.execute("SET session_replication_role = 'replica'");
		try {
			jdbc.update("DELETE FROM capture_sync_artifact");
			jdbc.update("DELETE FROM capture_sync_stream_revision");
			jdbc.update("DELETE FROM capture_sync_revision");
			jdbc.update("DELETE FROM capture_sync_job_attempt");
			jdbc.update("DELETE FROM capture_sync_job");
			jdbc.update("DELETE FROM inbox_message WHERE consumer_name = 'capture-sync-result-v1'");
		}
		finally {
			jdbc.execute("SET session_replication_role = 'origin'");
		}
	}

	public static void clearSyntheticProjectFixtures(JdbcTemplate jdbc, UUID... projectIds) {
		clearCaptureSyncTables(jdbc);
		for (var projectId : projectIds) {
			jdbc.update("DELETE FROM media_report WHERE project_id = ?", projectId);
			jdbc.update("DELETE FROM media_job WHERE project_id = ?", projectId);
			jdbc.update("""
					DELETE FROM camera_stream
					WHERE capture_session_id IN (
					    SELECT capture_session_id FROM capture_session WHERE project_id = ?
					)
					""", projectId);
			jdbc.update("""
					DELETE FROM camera_set_snapshot_member
					WHERE camera_set_snapshot_id IN (
					    SELECT snapshot.camera_set_snapshot_id
					    FROM camera_set_snapshot snapshot
					    JOIN capture_session capture
					      ON capture.capture_session_id = snapshot.capture_session_id
					    WHERE capture.project_id = ?
					)
					""", projectId);
			jdbc.update("""
					DELETE FROM camera_set_snapshot
					WHERE capture_session_id IN (
					    SELECT capture_session_id FROM capture_session WHERE project_id = ?
					)
					""", projectId);
			jdbc.update("DELETE FROM capture_session WHERE project_id = ?", projectId);
			jdbc.update("DELETE FROM clip_asset WHERE project_id = ?", projectId);
			jdbc.update("""
					DELETE FROM upload_file
					WHERE batch_id IN (SELECT batch_id FROM upload_batch WHERE project_id = ?)
					""", projectId);
			jdbc.update("DELETE FROM upload_batch WHERE project_id = ?", projectId);
			jdbc.update("""
					DELETE FROM camera_set_member
					WHERE camera_set_id IN (SELECT camera_set_id FROM camera_set WHERE project_id = ?)
					""", projectId);
			jdbc.update("DELETE FROM camera_set WHERE project_id = ?", projectId);
			jdbc.update("DELETE FROM camera_channel WHERE project_id = ?", projectId);
			jdbc.update("DELETE FROM membership WHERE project_id = ?", projectId);
			jdbc.update("DELETE FROM project WHERE project_id = ?", projectId);
		}
	}

}
