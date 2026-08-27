package com.medicationvideo.platform.datasetexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class TrainingDataV3ExportServiceTests {

	private final TrainingDataV3ExportService service = new TrainingDataV3ExportService(
			org.mockito.Mockito.mock(JdbcTemplate.class),
			JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(), "synthetic");

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

}
