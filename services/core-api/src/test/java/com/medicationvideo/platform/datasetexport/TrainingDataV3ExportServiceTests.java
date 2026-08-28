package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class TrainingDataV3ExportServiceTests {

	private final ObjectMapper objectMapper = JsonMapper.builder()
		.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
		.build();

	private final TrainingDataV3ExportService service = new TrainingDataV3ExportService(
			org.mockito.Mockito.mock(JdbcTemplate.class), this.objectMapper, "synthetic");

	@Test
	void readbackPreservesTheV3DocumentIdentityAndSplitUnit() {
		var document = this.service
			.readback("{\"schema_version\":\"training-data.v3\",\"split_unit\":\"capture_group\",\"events\":[]}"
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));

		assertEquals("training-data.v3", document.schemaVersion());
		assertEquals("capture_group", document.splitUnit());
		assertEquals(List.of(), document.events());
	}

	@Test
	void splitGuardRejectsOneCaptureGroupAcrossDifferentSplits() {
		var groupId = UUID.fromString("d0000000-0000-4000-8000-000000000001");

		assertThrows(TrainingDataExportException.class,
				() -> TrainingDataV3ExportService
					.validateSplitAssignments(List.of(new TrainingDataV3ExportService.SplitAssignment(groupId, "train"),
							new TrainingDataV3ExportService.SplitAssignment(groupId, "validation"))));
	}

	@Test
	void splitGuardAllowsRepeatedSameSplitAssignments() {
		var groupId = UUID.fromString("d0000000-0000-4000-8000-000000000002");

		TrainingDataV3ExportService
			.validateSplitAssignments(List.of(new TrainingDataV3ExportService.SplitAssignment(groupId, "train"),
					new TrainingDataV3ExportService.SplitAssignment(groupId, "TRAIN")));
	}

	@Test
	void v3SanitizesLegacySupportingIntervalsWithoutDroppingViewQualityFacts() throws Exception {
		var primaryClipAssetId = UUID.fromString("d0000000-0000-4000-8000-000000000010");
		var primaryInterval = this.objectMapper
			.readTree("[{\"start_ms\":0,\"end_ms\":400,\"label\":\"prepare_dose\"}]");
		var views = this.objectMapper.readTree("""
				[
				  {"clip_asset_id":"%s","camera_number":1,"quality_decision":"QUALIFIED",
				   "interval":[{"start_ms":0,"end_ms":400}],"interval_source":"PRIMARY_AUTHORITATIVE",
				   "action_aligned_interval":true},
				  {"clip_asset_id":"d0000000-0000-4000-8000-000000000011","camera_number":2,
				   "quality_decision":"QUALIFIED","quality_review_version":3,
				   "interval":[{"start_ms":120,"end_ms":420}],
				   "interval_source":"VALIDATED_VERSIONED_MAPPING","action_aligned_interval":true,
				   "mapping_provenance":{"mapping_version":"capture-map.v1","validated":true}}
				]
				""".formatted(primaryClipAssetId));

		var sanitized = TrainingDataV3ExportService.sanitizeViewEvidence(this.objectMapper, views, primaryClipAssetId,
				primaryInterval);

		assertEquals(0, sanitized.get(0).get("interval").get(0).get("start_ms").asInt());
		assertEquals("PRIMARY_AUTHORITATIVE", sanitized.get(0).get("interval_source").asText());
		assertEquals(false, sanitized.get(1).get("action_aligned_interval").asBoolean());
		assertEquals("EVENT_LEVEL_EVIDENCE", sanitized.get(1).get("interval_source").asText());
		assertNull(sanitized.get(1).get("interval"));
		assertNull(sanitized.get(1).get("mapping_provenance"));
		assertEquals("QUALIFIED", sanitized.get(1).get("quality_decision").asText());
		assertEquals(3, sanitized.get(1).get("quality_review_version").asInt());
	}

}
