package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ClipAssetPlaybackContractTests.FakeStorageConfiguration.class)
class ClipAssetPlaybackContractTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final String ADMIN_TOKEN = "synthetic-test-admin-bearer-token-do-not-reuse";
	private static final String MEMBER_TOKEN = "synthetic-test-bearer-token-do-not-reuse";
	private static final String SHA256 = "a".repeat(64);
	private static final Instant BASE_TIME = Instant.parse("2026-07-21T01:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private FakeMultipartObjectStorage objectStorage;

	@BeforeEach
	void setUp() {
		clearState();
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
		this.objectStorage.reset();
	}

	@AfterEach
	void tearDown() {
		clearState();
	}

	@Test
	void activeProjectMembersGetShortLivedDataReviewGrantAndAuditEvidence()
			throws Exception {
		var clipAssetId = insertClipAsset("UPLOADED", "video/mp4", false);
		var requestId = "playback-grant-" + UUID.randomUUID();

		this.mockMvc.perform(post(
					"/api/v1/projects/{projectId}/clip-assets/{clipAssetId}/playback-grants",
					PROJECT_ID,
					clipAssetId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN)
				.header("X-Request-Id", requestId))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.purpose").value("DATA_REVIEW"))
				.andExpect(jsonPath("$.content_type").value("video/mp4"))
				.andExpect(jsonPath("$.media_url").value("https://storage.invalid/review.mp4?signature=synthetic"))
				.andExpect(jsonPath("$.expires_at").exists())
				.andExpect(jsonPath("$.grant_id").exists());

		grant(clipAssetId, ADMIN_TOKEN, "playback-admin-" + UUID.randomUUID())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.purpose").value("DATA_REVIEW"));

		assertEquals(2, this.objectStorage.authorizeReadCalls);
		assertEquals(1, count("""
				SELECT count(*) FROM audit_event
				WHERE project_id = ?
				  AND request_id = ?
				  AND action = 'CLIP_ASSET_PLAYBACK_GRANTED'
				  AND reason_code = 'DATA_REVIEW'
				  AND authorization_reference = 'PROJECT_MEMBERSHIP_MEMBER'
				  AND details_json::text NOT LIKE '%storage.invalid%'
				""", PROJECT_ID, requestId));
	}

	@Test
	void allowsUploadedAndMediaProcessingAssetsButRejectsDeletedAndUnsupportedAssets() throws Exception {
		var uploadedAssetId = insertClipAsset("UPLOADED", "video/mp4", false);
		var ingestingAssetId = insertClipAsset("INGESTING", "video/mp4", false);
		var ingestFailedAssetId = insertClipAsset("INGEST_FAILED", "video/mp4", false);
		var unsupportedAssetId = insertClipAsset("INGESTED", "video/webm", false);
		var deletedAssetId = insertClipAsset("INGESTED", "video/mp4", true);

		grant(uploadedAssetId, MEMBER_TOKEN, "playback-uploaded-0001")
				.andExpect(status().isOk());
		grant(ingestingAssetId, MEMBER_TOKEN, "playback-ingesting-0001")
				.andExpect(status().isOk());
		grant(ingestFailedAssetId, MEMBER_TOKEN, "playback-ingest-failed-0001")
				.andExpect(status().isOk());
		grant(unsupportedAssetId, MEMBER_TOKEN, "playback-unsupported-0001")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("PLAYBACK_UNSUPPORTED_MEDIA"));
		grant(deletedAssetId, MEMBER_TOKEN, "playback-deleted-0001")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("CLIP_ASSET_DELETED"));
	}

	@Test
	void reportsStorageFailureAsRetryableWithoutWritingAnAuditGrant() throws Exception {
		var clipAssetId = insertClipAsset("INGESTED", "video/mp4", false);
		var requestId = "playback-storage-failure-" + UUID.randomUUID();
		this.objectStorage.failAuthorizeRead = true;

		grant(clipAssetId, ADMIN_TOKEN, requestId)
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error_code").value("OBJECT_STORAGE_UNAVAILABLE"))
				.andExpect(jsonPath("$.retryable").value(true));
		assertEquals(0, count("SELECT count(*) FROM audit_event WHERE project_id = ? AND request_id = ?", PROJECT_ID, requestId));
	}

	private org.springframework.test.web.servlet.ResultActions grant(
			UUID clipAssetId,
			String token,
			String requestId) throws Exception {
		return this.mockMvc.perform(post(
					"/api/v1/projects/{projectId}/clip-assets/{clipAssetId}/playback-grants",
					PROJECT_ID,
					clipAssetId)
				.header("Authorization", "Bearer " + token)
				.header("X-Request-Id", requestId));
	}

	private UUID insertClipAsset(String status, String contentType, boolean deleted) {
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256,
				    status, expires_at, created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-review-device', ?, 1, 1234, ?,
				        'COMPLETED', ?, ?, ?, ?, ?)
				""",
				batchId, PROJECT_ID, "playback-batch-" + batchId, SHA256,
				ProjectMembershipTestData.ADMIN_ACTOR, SHA256,
				Timestamp.from(BASE_TIME.plusSeconds(3600)), Timestamp.from(BASE_TIME),
				Timestamp.from(BASE_TIME), SHA256, Timestamp.from(BASE_TIME));
		this.jdbcTemplate.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
				    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, 'VIDEO', ?, 1234, ?, 8388608, 1,
				        'UPLOADED', ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
				""",
				uploadFileId, batchId, clientFileId, "playback-file-" + uploadFileId, SHA256,
				contentType, SHA256, Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME),
				"objects/" + uploadFileId, "multipart-" + uploadFileId, Timestamp.from(BASE_TIME),
				Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME), SHA256);
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, deleted_at, deleted_by, delete_reason, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, ?, 1234, ?, 'synthetic-bucket', ?, 'synthetic-etag',
				        ?, ?, ?, ?, ?, ?, 0)
				""",
				clipAssetId, PROJECT_ID, batchId, uploadFileId, clientFileId, contentType, SHA256,
				"objects/" + clipAssetId, status,
				deleted ? Timestamp.from(BASE_TIME) : null,
				deleted ? ProjectMembershipTestData.ADMIN_ACTOR : null,
				deleted ? "synthetic deletion" : null,
				Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME));
		return clipAssetId;
	}

	private int count(String sql, Object... arguments) {
		return this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);
	}

	private void clearState() {
		this.jdbcTemplate.update("DELETE FROM clip_asset WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM upload_file WHERE batch_id IN (SELECT batch_id FROM upload_batch WHERE project_id = ?)", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM upload_batch WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM membership WHERE project_id = ?", PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM project WHERE project_id = ?", PROJECT_ID);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeStorageConfiguration {

		@Bean
		@Primary
		FakeMultipartObjectStorage fakeMultipartObjectStorage() {
			return new FakeMultipartObjectStorage();
		}
	}

	static class FakeMultipartObjectStorage implements MultipartObjectStorage {

		private int authorizeReadCalls;
		private boolean failAuthorizeRead;

		@Override
		public CreatedUpload create(String objectKey, String contentType) {
			throw new UnsupportedOperationException();
		}

		@Override
		public URI authorizePart(CreatedUpload upload, int partNumber, Duration lifetime) {
			throw new UnsupportedOperationException();
		}

		@Override
		public URI authorizeRead(ObjectReference object, Duration lifetime) {
			this.authorizeReadCalls++;
			if (this.failAuthorizeRead) {
				throw new MultipartObjectStorageException("Synthetic read authorization failure.", null);
			}
			return URI.create("https://storage.invalid/review.mp4?signature=synthetic");
		}

		@Override
		public Optional<StoredPart> findPart(CreatedUpload upload, int partNumber) {
			return Optional.empty();
		}

		@Override
		public List<StoredPart> listParts(CreatedUpload upload) {
			return List.of();
		}

		@Override
		public CompletedObject complete(CreatedUpload upload, List<StoredPart> parts) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Optional<CompletedObject> findCompletedObject(CreatedUpload upload) {
			return Optional.empty();
		}

		@Override
		public String calculateSha256(CreatedUpload upload) {
			throw new UnsupportedOperationException();
		}

		@Override
		public byte[] readCompletedObject(ObjectReference object, int maxBytes) {
			return new byte[0];
		}

		@Override
		public void abort(CreatedUpload upload) {
		}

		@Override
		public void delete(ObjectReference object) {
		}

		void reset() {
			this.authorizeReadCalls = 0;
			this.failAuthorizeRead = false;
		}
	}
}
