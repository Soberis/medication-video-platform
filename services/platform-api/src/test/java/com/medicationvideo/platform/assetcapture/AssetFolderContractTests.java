package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssetFolderContractTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID OTHER_PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	private static final String ADMIN_TOKEN = "synthetic-test-admin-bearer-token-do-not-reuse";

	private static final String MEMBER_TOKEN = "synthetic-test-bearer-token-do-not-reuse";

	private static final String ADMIN_ACTOR = "local-synthetic-admin";

	private static final String MEMBER_ACTOR = "local-synthetic-uploader";

	private static final String SHA256 = "a".repeat(64);

	private static final Instant BASE_TIME = Instant.parse("2026-07-17T12:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoSpyBean
	private AuditEventRecorder auditRecorder;

	@BeforeEach
	void seedProjectMemberships() {
		clearSyntheticData();
		insertProject(PROJECT_ID, "Synthetic Folder Project", "folder-project-main-0001");
		insertProject(OTHER_PROJECT_ID, "Synthetic Other Project", "folder-project-other-0001");
		insertMembership(PROJECT_ID, ADMIN_ACTOR, "ADMIN");
		insertMembership(PROJECT_ID, MEMBER_ACTOR, "MEMBER");
		insertMembership(OTHER_PROJECT_ID, ADMIN_ACTOR, "ADMIN");
	}

	@AfterEach
	void clearProjects() {
		clearSyntheticData();
	}

	@Test
	void administratorCreatesRenamesAndListsFoldersWhileMemberIsReadOnly() throws Exception {
		var firstFolderId = createFolder(PROJECT_ID, ADMIN_TOKEN, "folder-create-idem-0001", "Reviewed Clips")
			.andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.display_name").value("Reviewed Clips"))
			.andExpect(jsonPath("$.version").value(0))
			.andReturn()
			.getResponse()
			.getContentAsString();
		var firstId = UUID.fromString(JsonPath.read(firstFolderId, "$.folder_id"));

		createFolder(PROJECT_ID, ADMIN_TOKEN, "folder-create-idem-0001", "Reviewed Clips")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.folder_id").value(firstId.toString()));
		createFolder(PROJECT_ID, ADMIN_TOKEN, "folder-create-idem-0001", "Different Name")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("ASSET_CLASSIFICATION_IDEMPOTENCY_KEY_REUSED"));

		var archiveId = UUID
			.fromString(JsonPath.read(createFolder(PROJECT_ID, ADMIN_TOKEN, "folder-create-idem-0002", "Archive")
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString(), "$.folder_id"));
		renameFolder(PROJECT_ID, firstId, "folder-rename-idem-0001", "Ready for Review").andExpect(status().isOk())
			.andExpect(jsonPath("$.display_name").value("Ready for Review"))
			.andExpect(jsonPath("$.version").value(1));
		renameFolder(PROJECT_ID, archiveId, "folder-rename-idem-0002", "ready for review")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("ASSET_FOLDER_NAME_CONFLICT"));

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/asset-folders", PROJECT_ID).header("Authorization",
					"Bearer " + MEMBER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].display_name").value("Archive"))
			.andExpect(jsonPath("$.items[1].display_name").value("Ready for Review"));

		createFolder(PROJECT_ID, MEMBER_TOKEN, "folder-member-denied-0001", "Denied").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));
		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/asset-folders", OTHER_PROJECT_ID).header("Authorization",
					"Bearer " + MEMBER_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error_code").value("PROJECT_NOT_FOUND"));

		assertEquals(1, countAudit("folder-create-idem-0001-request", "ASSET_FOLDER_CREATED"));
		assertEquals(1, countAudit("folder-rename-idem-0001-request", "ASSET_FOLDER_RENAMED"));
	}

	@Test
	void administratorMovesAndUnclassifiesClipAssetWithinItsProject() throws Exception {
		var folderId = createFolderId(PROJECT_ID, "folder-move-create-0001", "Classified");
		var otherFolderId = createFolderId(OTHER_PROJECT_ID, "folder-other-create-0001", "Other Project Folder");
		var clipAssetId = insertClipAsset(PROJECT_ID);

		assignFolder(clipAssetId, folderId, "folder-assign-idem-0001", ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.folder_id").value(folderId.toString()))
			.andExpect(jsonPath("$.version").value(1));
		assignFolder(clipAssetId, folderId, "folder-assign-idem-0001", ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(1));

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/clip-assets/{clipAssetId}", PROJECT_ID, clipAssetId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.folder_id").value(folderId.toString()));

		assignFolder(clipAssetId, otherFolderId, "folder-cross-project-0001", ADMIN_TOKEN)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error_code").value("ASSET_FOLDER_NOT_FOUND"));
		assignFolder(clipAssetId, folderId, "folder-member-move-0001", MEMBER_TOKEN).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));

		unclassify(clipAssetId, "folder-unclassify-0001").andExpect(status().isOk())
			.andExpect(jsonPath("$.folder_id").doesNotExist())
			.andExpect(jsonPath("$.version").value(2));
		unclassify(clipAssetId, "folder-unclassify-0001").andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(2));

		assertEquals(1, countAudit("folder-assign-idem-0001-request", "CLIP_ASSET_FOLDER_CHANGED"));
		assertEquals(1, countAudit("folder-unclassify-0001-request", "CLIP_ASSET_FOLDER_CHANGED"));
	}

	@Test
	void deletingFolderOnlyUnclassifiesItsClipAssetsAndIsIdempotent() throws Exception {
		var folderId = createFolderId(PROJECT_ID, "folder-delete-create-0001", "Temporary");
		var firstAssetId = insertClipAsset(PROJECT_ID);
		var secondAssetId = insertClipAsset(PROJECT_ID);
		assignFolder(firstAssetId, folderId, "folder-delete-assign-0001", ADMIN_TOKEN).andExpect(status().isOk());
		assignFolder(secondAssetId, folderId, "folder-delete-assign-0002", ADMIN_TOKEN).andExpect(status().isOk());

		deleteFolder(PROJECT_ID, folderId, "folder-delete-idem-0001").andExpect(status().isNoContent())
			.andExpect(header().string("Cache-Control", "no-store"));
		deleteFolder(PROJECT_ID, folderId, "folder-delete-idem-0001").andExpect(status().isNoContent());

		assertEquals(0, count("SELECT count(*) FROM asset_folder WHERE folder_id = ?", folderId));
		assertEquals(2, count("SELECT count(*) FROM clip_asset WHERE project_id = ?", PROJECT_ID));
		assertEquals(2, count("""
				SELECT count(*) FROM clip_asset
				WHERE project_id = ? AND folder_id IS NULL AND version = 2
				""", PROJECT_ID));
		assertEquals(1, countAudit("folder-delete-idem-0001-request", "ASSET_FOLDER_DELETED"));
	}

	@Test
	void administratorSoftDeletesAnAssetIdempotentlyAndHidesItFromDetail() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID);

		deleteClipAsset(clipAssetId, ADMIN_TOKEN, "clip-delete-idem-0001", "Uploaded by mistake")
			.andExpect(status().isNoContent())
			.andExpect(header().string("Cache-Control", "no-store"));

		assertEquals(1, count("""
				SELECT count(*) FROM clip_asset
				WHERE clip_asset_id = ?
				  AND deleted_at IS NOT NULL
				  AND deleted_by = ?
				  AND delete_reason = ?
				""", clipAssetId, ADMIN_ACTOR, "Uploaded by mistake"));
		assertEquals(1, countAudit("clip-delete-idem-0001-request", "CLIP_ASSET_DELETED"));

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/clip-assets/{clipAssetId}", PROJECT_ID, clipAssetId)
				.header("Authorization", "Bearer " + MEMBER_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error_code").value("CLIP_ASSET_NOT_FOUND"));
		deleteClipAsset(clipAssetId, ADMIN_TOKEN, "clip-delete-idem-0002", "A different retry reason")
			.andExpect(status().isNoContent());
		assertEquals(1, count("SELECT count(*) FROM audit_event WHERE project_id = ? AND action = ?", PROJECT_ID,
				"CLIP_ASSET_DELETED"));
	}

	@Test
	void memberCannotSoftDeleteAnAsset() throws Exception {
		var clipAssetId = insertClipAsset(PROJECT_ID);

		deleteClipAsset(clipAssetId, MEMBER_TOKEN, "clip-delete-member-0001", "No longer needed")
			.andExpect(status().isForbidden());

		assertEquals(1,
				count("SELECT count(*) FROM clip_asset WHERE clip_asset_id = ? AND deleted_at IS NULL", clipAssetId));
	}

	@Test
	void databaseRejectsCrossProjectClassificationAndModelHasNoParentFolder() throws Exception {
		var otherFolderId = createFolderId(OTHER_PROJECT_ID, "folder-db-other-0001", "Other Database Folder");
		var clipAssetId = insertClipAsset(PROJECT_ID);

		var crossProject = assertThrows(DataIntegrityViolationException.class, () -> this.jdbcTemplate
			.update("UPDATE clip_asset SET folder_id = ? WHERE clip_asset_id = ?", otherFolderId, clipAssetId));
		assertTrue(crossProject.getMostSpecificCause().getMessage().contains("fk_clip_asset_project_folder"));
		assertEquals(0, this.jdbcTemplate.queryForObject("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'asset_folder'
				  AND column_name IN ('parent_id', 'parent_folder_id')
				""", Integer.class));
	}

	@Test
	void auditFailureRollsBackFolderDeletionAndAssetUnclassification() throws Exception {
		var folderId = createFolderId(PROJECT_ID, "folder-rollback-create-0001", "Rollback");
		var clipAssetId = insertClipAsset(PROJECT_ID);
		assignFolder(clipAssetId, folderId, "folder-rollback-assign-0001", ADMIN_TOKEN).andExpect(status().isOk());

		doAnswer(invocation -> {
			var command = invocation.getArgument(0, AuditEventCommand.class);
			if ("ASSET_FOLDER_DELETED".equals(command.action())) {
				throw new IllegalStateException("Synthetic folder deletion audit failure.");
			}
			return invocation.callRealMethod();
		}).when(this.auditRecorder).record(any(AuditEventCommand.class));

		deleteFolder(PROJECT_ID, folderId, "folder-rollback-delete-0001").andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.error_code").value("INTERNAL_ERROR"));

		assertEquals(1, count("SELECT count(*) FROM asset_folder WHERE folder_id = ?", folderId));
		assertEquals(folderId, this.jdbcTemplate
			.queryForObject("SELECT folder_id FROM clip_asset WHERE clip_asset_id = ?", UUID.class, clipAssetId));
		assertEquals(0, count("""
				SELECT count(*) FROM asset_classification_command
				WHERE project_id = ? AND idempotency_key = 'folder-rollback-delete-0001'
				""", PROJECT_ID));
	}

	@Test
	void concurrentCreateReplayProducesOneFolderAndOneAudit() throws Exception {
		var concurrency = 4;
		var ready = new CountDownLatch(concurrency);
		var start = new CountDownLatch(1);
		var responses = new ConcurrentLinkedQueue<String>();
		try (var executor = Executors.newFixedThreadPool(concurrency)) {
			var futures = new ArrayList<java.util.concurrent.Future<Void>>();
			for (var index = 0; index < concurrency; index++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					if (!start.await(10, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Concurrent folder requests did not start.");
					}
					responses
						.add(createFolder(PROJECT_ID, ADMIN_TOKEN, "folder-concurrent-idem-0001", "Concurrent Folder")
							.andExpect(status().isCreated())
							.andReturn()
							.getResponse()
							.getContentAsString());
					return null;
				}));
			}
			assertTrue(ready.await(10, TimeUnit.SECONDS));
			start.countDown();
			for (var future : futures) {
				future.get(20, TimeUnit.SECONDS);
			}
		}

		assertEquals(concurrency, responses.size());
		assertEquals(1,
				responses.stream().map(response -> JsonPath.<String>read(response, "$.folder_id")).distinct().count());
		assertEquals(1, count("SELECT count(*) FROM asset_folder WHERE project_id = ?", PROJECT_ID));
		assertEquals(1, countAudit("folder-concurrent-idem-0001-request", "ASSET_FOLDER_CREATED"));
	}

	private ResultActions createFolder(UUID projectId, String token, String idempotencyKey, String displayName)
			throws Exception {
		return this.mockMvc.perform(
				post("/api/v1/projects/{projectId}/asset-folders", projectId).header("Authorization", "Bearer " + token)
					.header("Idempotency-Key", idempotencyKey)
					.header("X-Request-Id", idempotencyKey + "-request")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"display_name":"%s"}
							""".formatted(displayName)));
	}

	private UUID createFolderId(UUID projectId, String idempotencyKey, String displayName) throws Exception {
		var response = createFolder(projectId, ADMIN_TOKEN, idempotencyKey, displayName).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(response, "$.folder_id"));
	}

	private ResultActions renameFolder(UUID projectId, UUID folderId, String idempotencyKey, String displayName)
			throws Exception {
		return this.mockMvc.perform(put("/api/v1/projects/{projectId}/asset-folders/{folderId}", projectId, folderId)
			.header("Authorization", "Bearer " + ADMIN_TOKEN)
			.header("Idempotency-Key", idempotencyKey)
			.header("X-Request-Id", idempotencyKey + "-request")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"display_name":"%s"}
					""".formatted(displayName)));
	}

	private ResultActions deleteFolder(UUID projectId, UUID folderId, String idempotencyKey) throws Exception {
		return this.mockMvc.perform(delete("/api/v1/projects/{projectId}/asset-folders/{folderId}", projectId, folderId)
			.header("Authorization", "Bearer " + ADMIN_TOKEN)
			.header("Idempotency-Key", idempotencyKey)
			.header("X-Request-Id", idempotencyKey + "-request"));
	}

	private ResultActions assignFolder(UUID clipAssetId, UUID folderId, String idempotencyKey, String token)
			throws Exception {
		return this.mockMvc
			.perform(put("/api/v1/projects/{projectId}/clip-assets/{clipAssetId}/folder", PROJECT_ID, clipAssetId)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", idempotencyKey + "-request")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"folder_id":"%s"}
						""".formatted(folderId)));
	}

	private ResultActions unclassify(UUID clipAssetId, String idempotencyKey) throws Exception {
		return this.mockMvc
			.perform(delete("/api/v1/projects/{projectId}/clip-assets/{clipAssetId}/folder", PROJECT_ID, clipAssetId)
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", idempotencyKey + "-request"));
	}

	private ResultActions deleteClipAsset(UUID clipAssetId, String token, String idempotencyKey, String reason)
			throws Exception {
		return this.mockMvc
			.perform(delete("/api/v1/projects/{projectId}/clip-assets/{clipAssetId}", PROJECT_ID, clipAssetId)
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", idempotencyKey + "-request")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"reason":"%s"}
						""".formatted(reason)));
	}

	private void insertProject(UUID projectId, String displayName, String idempotencyKey) {
		this.jdbcTemplate.update("""
				INSERT INTO project (
				    project_id, display_name, status, created_by, idempotency_key,
				    request_fingerprint, created_at, row_version)
				VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, 0)
				""", projectId, displayName, ADMIN_ACTOR, idempotencyKey, SHA256, Timestamp.from(BASE_TIME));
	}

	private void insertMembership(UUID projectId, String actorId, String role) {
		this.jdbcTemplate.update("""
				INSERT INTO membership (
				    membership_id, project_id, actor_id, role, status,
				    created_by, created_at, updated_by, updated_at, row_version)
				VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 0)
				""", UUID.randomUUID(), projectId, actorId, role, ADMIN_ACTOR, Timestamp.from(BASE_TIME), ADMIN_ACTOR,
				Timestamp.from(BASE_TIME));
	}

	private UUID insertClipAsset(UUID projectId) {
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id, file_count, total_size_bytes, manifest_sha256,
				    status, expires_at, created_at, updated_at,
				    verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-folder-device', ?, 1, 1234, ?,
				        'COMPLETED', ?, ?, ?, ?, ?)
				""", batchId, projectId, "folder-batch-" + batchId, SHA256, ADMIN_ACTOR, SHA256,
				Timestamp.from(BASE_TIME.plusSeconds(3600)), Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME),
				SHA256, Timestamp.from(BASE_TIME));
		this.jdbcTemplate.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key,
				    request_fingerprint, file_kind, content_type, size_bytes, sha256,
				    part_size_bytes, total_parts, status, created_at, updated_at,
				    storage_bucket, object_key, storage_upload_id, multipart_created_at,
				    completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 1234, ?, 8388608, 1,
				        'UPLOADED', ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
				""", uploadFileId, batchId, clientFileId, "folder-file-" + uploadFileId, SHA256, SHA256,
				Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME), "objects/" + uploadFileId,
				"multipart-" + uploadFileId, Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME),
				Timestamp.from(BASE_TIME), SHA256);
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id,
				    client_file_id, content_type, size_bytes, sha256, storage_bucket,
				    object_key, storage_etag, asset_status, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?,
				        'synthetic-etag', 'INGESTED', ?, ?, 0)
				""", clipAssetId, projectId, batchId, uploadFileId, clientFileId, SHA256, "objects/" + clipAssetId,
				Timestamp.from(BASE_TIME), Timestamp.from(BASE_TIME));
		return clipAssetId;
	}

	private int count(String sql, Object... arguments) {
		return this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);
	}

	private int countAudit(String requestId, String action) {
		return this.jdbcTemplate.queryForObject("""
				SELECT count(*) FROM audit_event
				WHERE project_id = ? AND request_id = ? AND action = ?
				""", Integer.class, PROJECT_ID, requestId, action);
	}

	private void clearSyntheticData() {
		this.jdbcTemplate.update("""
				UPDATE clip_asset SET folder_id = NULL
				WHERE project_id IN (?, ?)
				""", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbcTemplate.update("""
				DELETE FROM asset_classification_command
				WHERE project_id IN (?, ?)
				""", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbcTemplate.update("""
				DELETE FROM asset_folder WHERE project_id IN (?, ?)
				""", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbcTemplate.update("""
				DELETE FROM clip_asset WHERE project_id IN (?, ?)
				""", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbcTemplate.update("""
				DELETE FROM upload_file WHERE batch_id IN (
				    SELECT batch_id FROM upload_batch WHERE project_id IN (?, ?))
				""", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbcTemplate.update("""
				DELETE FROM upload_batch WHERE project_id IN (?, ?)
				""", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbcTemplate.update("""
				DELETE FROM membership WHERE project_id IN (?, ?)
				""", PROJECT_ID, OTHER_PROJECT_ID);
		this.jdbcTemplate.update("""
				DELETE FROM project WHERE project_id IN (?, ?)
				""", PROJECT_ID, OTHER_PROJECT_ID);
	}

}
