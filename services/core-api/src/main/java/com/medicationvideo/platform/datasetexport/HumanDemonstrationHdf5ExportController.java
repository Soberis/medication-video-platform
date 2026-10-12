package com.medicationvideo.platform.datasetexport;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.medicationvideo.platform.datasetexport.HumanDemonstrationHdf5ExportTaskManager.ExportTaskRecord;
import com.medicationvideo.platform.datasetexport.HumanDemonstrationHdf5ExportTaskManager.TaskStatus;
import com.medicationvideo.platform.datasetexport.HumanDemonstrationHdf5ExportService.ExportOptions;
import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectActor;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/human-demonstrations/hdf5")
class HumanDemonstrationHdf5ExportController {

	private final HumanDemonstrationHdf5ExportService service;

	private final ProjectAccessService projectAccessService;

	private final HumanDemonstrationHdf5ExportTaskManager taskManager;

	HumanDemonstrationHdf5ExportController(HumanDemonstrationHdf5ExportService service,
			ProjectAccessService projectAccessService, HumanDemonstrationHdf5ExportTaskManager taskManager) {
		this.service = service;
		this.projectAccessService = projectAccessService;
		this.taskManager = taskManager;
	}

	@GetMapping(value = { "/v2/download", "/download" }, produces = HumanDemonstrationHdf5ExportService.MEDIA_TYPE)
	ResponseEntity<StreamingResponseBody> download(@PathVariable UUID projectId,
			@RequestParam(name = "capture_group_id", required = false) List<UUID> captureGroupIds,
			@RequestParam(name = "include_raw_candidates", defaultValue = "false") boolean includeRawCandidates,
			@RequestParam(name = "tensor_layout", defaultValue = "true") boolean tensorLayout,
			Authentication authentication) {
		return executeDownload(projectId, captureGroupIds, includeRawCandidates, tensorLayout, authentication);
	}

	@PostMapping(value = { "/v2/download", "/download" }, produces = HumanDemonstrationHdf5ExportService.MEDIA_TYPE)
	ResponseEntity<StreamingResponseBody> downloadPost(@PathVariable UUID projectId,
			@RequestBody(required = false) HumanDemonstrationHdf5ExportRequest request, Authentication authentication) {
		var captureGroupIds = request != null ? request.captureGroupIds() : null;
		var includeRawCandidates = request != null && Boolean.TRUE.equals(request.includeRawCandidates());
		var tensorLayout = request == null || request.tensorLayout() == null
				|| Boolean.TRUE.equals(request.tensorLayout());
		return executeDownload(projectId, captureGroupIds, includeRawCandidates, tensorLayout, authentication);
	}

	private ResponseEntity<StreamingResponseBody> executeDownload(UUID projectId, List<UUID> captureGroupIds,
			boolean includeRawCandidates, boolean tensorLayout, Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireAdministrator(projectId, actor.actorId());
		var generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		var options = ExportOptions.v2(includeRawCandidates, tensorLayout);
		var exportPath = this.service.download(projectId, captureGroupIds, options);
		long fileSize;
		try {
			fileSize = Files.size(exportPath);
		}
		catch (IOException e) {
			try {
				Files.deleteIfExists(exportPath);
			}
			catch (IOException ignored) {
			}
			throw new TrainingDataExportException("HDF5_EXPORT_FAILED", "Failed to read export file size.", 500);
		}
		StreamingResponseBody body = outputStream -> {
			try {
				Files.copy(exportPath, outputStream);
				outputStream.flush();
			}
			finally {
				try {
					Files.deleteIfExists(exportPath);
				}
				catch (IOException ignored) {
				}
			}
		};
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.contentType(MediaType.parseMediaType(HumanDemonstrationHdf5ExportService.MEDIA_TYPE))
			.contentLength(fileSize)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment()
						.filename(HumanDemonstrationHdf5ExportService.fileName(captureGroupIds, generatedAt))
						.build()
						.toString())
			.body(body);
	}

	@PostMapping(value = { "/v2/jobs", "/jobs" }, produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<ExportTaskResponse> createJob(@PathVariable UUID projectId,
			@RequestBody(required = false) HumanDemonstrationHdf5ExportRequest request, Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireAdministrator(projectId, actor.actorId());
		var captureGroupIds = request != null ? request.captureGroupIds() : null;
		var includeRawCandidates = request != null && Boolean.TRUE.equals(request.includeRawCandidates());
		var tensorLayout = request == null || request.tensorLayout() == null
				|| Boolean.TRUE.equals(request.tensorLayout());
		var options = ExportOptions.v2(includeRawCandidates, tensorLayout);
		var record = this.taskManager.submit(projectId, captureGroupIds, options);
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(projectId, record));
	}

	@GetMapping(value = { "/v2/jobs/{taskId}", "/jobs/{taskId}" }, produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<ExportTaskResponse> getJob(@PathVariable UUID projectId, @PathVariable UUID taskId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireAdministrator(projectId, actor.actorId());
		var record = this.taskManager.get(taskId);
		if (record == null || !record.projectId().equals(projectId)) {
			throw new TrainingDataExportException("EXPORT_TASK_NOT_FOUND", "The export task does not exist.", 404);
		}
		return ResponseEntity.ok(toResponse(projectId, record));
	}

	@GetMapping(value = { "/v2/jobs/{taskId}/download", "/jobs/{taskId}/download" },
			produces = HumanDemonstrationHdf5ExportService.MEDIA_TYPE)
	ResponseEntity<StreamingResponseBody> downloadJobResult(@PathVariable UUID projectId, @PathVariable UUID taskId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireAdministrator(projectId, actor.actorId());
		var record = this.taskManager.get(taskId);
		if (record == null || !record.projectId().equals(projectId)) {
			throw new TrainingDataExportException("EXPORT_TASK_NOT_FOUND", "The export task does not exist.", 404);
		}
		if (record.status() != TaskStatus.COMPLETED || record.outputFile() == null
				|| !Files.exists(record.outputFile())) {
			throw new TrainingDataExportException("EXPORT_TASK_NOT_READY", "The export task is not ready for download.",
					409);
		}
		StreamingResponseBody body = outputStream -> {
			try (var inputStream = Files.newInputStream(record.outputFile())) {
				inputStream.transferTo(outputStream);
				outputStream.flush();
			}
		};
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.contentType(MediaType.parseMediaType(HumanDemonstrationHdf5ExportService.MEDIA_TYPE))
			.contentLength(record.fileSizeBytes())
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(record.fileName()).build().toString())
			.body(body);
	}

	@DeleteMapping(value = { "/v2/jobs/{taskId}", "/jobs/{taskId}" })
	ResponseEntity<Void> deleteJob(@PathVariable UUID projectId, @PathVariable UUID taskId,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireAdministrator(projectId, actor.actorId());
		this.taskManager.cancel(taskId);
		return ResponseEntity.noContent().build();
	}

	private static ExportTaskResponse toResponse(UUID projectId, ExportTaskRecord record) {
		var downloadUrl = record.status() == TaskStatus.COMPLETED
				? "/api/v1/projects/%s/human-demonstrations/hdf5/v2/jobs/%s/download".formatted(projectId,
						record.taskId())
				: null;
		return new ExportTaskResponse(record.taskId(), record.projectId(), record.status().name(), record.current(),
				record.total(), record.percent(), record.currentLabel(), record.fileSizeBytes(), record.fileName(),
				downloadUrl, record.errorMessage(), record.createdAt(), record.completedAt());
	}

	record ExportTaskResponse(@JsonProperty("task_id") UUID taskId, @JsonProperty("project_id") UUID projectId,
			@JsonProperty("status") String status, @JsonProperty("current") int current,
			@JsonProperty("total") int total, @JsonProperty("percent") int percent,
			@JsonProperty("current_label") String currentLabel, @JsonProperty("file_size_bytes") long fileSizeBytes,
			@JsonProperty("file_name") String fileName, @JsonProperty("download_url") String downloadUrl,
			@JsonProperty("error_message") String errorMessage, @JsonProperty("created_at") Instant createdAt,
			@JsonProperty("completed_at") Instant completedAt) {
	}

	record HumanDemonstrationHdf5ExportRequest(@JsonProperty("capture_group_ids") @JsonAlias( {
			"capture_group_id", "captureGroupIds" }) List<UUID> captureGroupIds,
			@JsonProperty("include_raw_candidates") @JsonAlias("includeRawCandidates") Boolean includeRawCandidates,
			@JsonProperty("tensor_layout") @JsonAlias("tensorLayout") Boolean tensorLayout){
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED", "An authenticated platform account is required.",
					403);
		}
		return actor;
	}

}
