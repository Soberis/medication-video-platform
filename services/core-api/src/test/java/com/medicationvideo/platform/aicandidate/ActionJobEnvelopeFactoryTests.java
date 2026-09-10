package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService;

/**
 * Pins the {@code ai-action-job.v1} broker envelope to the frozen contract in
 * {@code contracts/events/ai-action-job-v1.schema.json}. The Worker validates the same
 * field set before it accepts a delivery, so an accidental rename here would silently
 * dead-letter every action attempt.
 */
class ActionJobEnvelopeFactoryTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000022");

	private static final UUID CLIP_ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000023");

	private static final UUID CAPTURE_GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000024");

	private static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000025");

	private static final Instant REQUESTED_AT = Instant.parse("2026-09-10T00:00:00Z");

	private static final List<String> CONTRACT_FIELDS = List.of("algorithm_key", "algorithm_version", "allowed_labels",
			"attempt", "capture_group_id", "clip_asset_id", "content_type", "input_sha256", "input_size_bytes",
			"job_id", "lease_token", "message_id", "model_sha256", "profile_sha256", "project_id", "requested_at",
			"sampling_fps", "schema_version");

	private final AssetCatalogQueryService assetCatalog = mock(AssetCatalogQueryService.class);

	private final ActionAllowedLabelsPort labelsPort = mock(ActionAllowedLabelsPort.class);

	private final ActionJobEnvelopeFactory factory = new ActionJobEnvelopeFactory(this.assetCatalog, this.labelsPort);

	@Test
	void envelopeSerialisesExactlyTheFrozenContractFields() {
		when(this.labelsPort.resolveAllowedLabels()).thenReturn(List.of("prepare_workspace", "cleanup"));

		var envelope = this.factory.create(actionJob(), CAPTURE_GROUP_ID, 2, MESSAGE_ID, "L".repeat(43), REQUESTED_AT);

		var actual = Arrays.stream(AiActionJobEnvelope.class.getRecordComponents())
			.map(RecordComponent::getName)
			.map(ActionJobEnvelopeFactoryTests::snakeCase)
			.sorted()
			.toList();
		assertEquals(CONTRACT_FIELDS, actual);
	}

	@Test
	void envelopeCarriesTheQueuedAlgorithmProfileAndResolvedLabels() {
		when(this.labelsPort.resolveAllowedLabels()).thenReturn(List.of("prepare_workspace", "cleanup"));
		var job = actionJob();

		var envelope = this.factory.create(job, CAPTURE_GROUP_ID, 2, MESSAGE_ID, "L".repeat(43), REQUESTED_AT);

		assertEquals("ai-action-job.v1", envelope.schemaVersion());
		assertEquals("QWEN3_VL_ACTION", envelope.algorithmKey());
		assertEquals("v1", envelope.algorithmVersion());
		assertEquals(1, envelope.samplingFps());
		assertEquals(AiCandidateJob.ACTION_PROFILE_SHA256, envelope.profileSha256());
		assertEquals(job.getAiJobId(), envelope.jobId());
		assertEquals(PROJECT_ID, envelope.projectId());
		assertEquals(CAPTURE_GROUP_ID, envelope.captureGroupId());
		assertEquals(CLIP_ASSET_ID, envelope.clipAssetId());
		assertEquals(2, envelope.attempt());
		assertEquals(MESSAGE_ID, envelope.messageId());
		assertEquals("L".repeat(43), envelope.leaseToken());
		assertEquals(List.of("prepare_workspace", "cleanup"), envelope.allowedLabels());
		assertEquals(REQUESTED_AT, envelope.requestedAt());
	}

	@Test
	void allowedLabelsFallBackToTheFrozenP0SchemaWhenTheQueryFails() {
		when(this.labelsPort.resolveAllowedLabels()).thenThrow(new IllegalStateException("label schema unavailable"));

		var labels = this.factory.resolveAllowedLabels();

		assertEquals(10, labels.size());
		assertEquals("prepare_workspace", labels.getFirst());
		assertEquals("cleanup", labels.get(4));
		assertEquals("label_seal", labels.getLast());
	}

	@Test
	void allowedLabelsFallBackWhenTheActiveSchemaHasNoDefinitions() {
		when(this.labelsPort.resolveAllowedLabels()).thenReturn(List.of());

		assertEquals(10, this.factory.resolveAllowedLabels().size());
	}

	@Test
	void captureGroupResolutionReturnsNullInsteadOfGuessing() {
		when(this.assetCatalog.resolveCaptureGroupId(CLIP_ASSET_ID)).thenReturn(null);

		assertNull(this.factory.resolveCaptureGroupId(CLIP_ASSET_ID));
	}

	private static AiCandidateJob actionJob() {
		return AiCandidateJob.queueAction(PROJECT_ID, CLIP_ASSET_ID, AiCandidateJob.ACTION_MODEL_SHA256, "a".repeat(64),
				2048L, "video/mp4", "queue-key-0001", "b".repeat(64), "tester", REQUESTED_AT);
	}

	private static String snakeCase(String value) {
		var builder = new StringBuilder(value.length() + 4);
		for (var index = 0; index < value.length(); index++) {
			var character = value.charAt(index);
			if (Character.isUpperCase(character)) {
				builder.append('_').append(Character.toLowerCase(character));
			}
			else {
				builder.append(character);
			}
		}
		return builder.toString();
	}

}
