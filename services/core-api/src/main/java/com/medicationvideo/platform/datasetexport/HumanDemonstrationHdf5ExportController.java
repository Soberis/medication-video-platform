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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.medicationvideo.platform.datasetexport.HumanDemonstrationHdf5ExportService.ExportOptions;
import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectActor;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/human-demonstrations/hdf5")
class HumanDemonstrationHdf5ExportController {

	private final HumanDemonstrationHdf5ExportService service;

	private final ProjectAccessService projectAccessService;

	HumanDemonstrationHdf5ExportController(HumanDemonstrationHdf5ExportService service,
			ProjectAccessService projectAccessService) {
		this.service = service;
		this.projectAccessService = projectAccessService;
	}

	@GetMapping(value = { "/v2/download", "/download" }, produces = HumanDemonstrationHdf5ExportService.MEDIA_TYPE)
	ResponseEntity<StreamingResponseBody> download(@PathVariable UUID projectId,
			@RequestParam(name = "capture_group_id", required = false) List<UUID> captureGroupIds,
			@RequestParam(name = "include_raw_candidates", defaultValue = "false") boolean includeRawCandidates,
			@RequestParam(name = "tensor_layout", defaultValue = "true") boolean tensorLayout,
			Authentication authentication) {
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

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED", "An authenticated platform account is required.",
					403);
		}
		return actor;
	}

}
