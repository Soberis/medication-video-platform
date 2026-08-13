package com.medicationvideo.platform.support;

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

}
