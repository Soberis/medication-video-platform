package com.medicationvideo.platform.datasetexport;

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

import com.medicationvideo.platform.datasetexport.HumanDemonstrationHdf5ExportService.ExportOptions;
import com.medicationvideo.platform.datasetexport.HumanDemonstrationHdf5ExportService.ExportVersion;
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

	@GetMapping(value = "/v1/download", produces = HumanDemonstrationHdf5ExportService.MEDIA_TYPE)
	ResponseEntity<byte[]> downloadV1(@PathVariable UUID projectId,
			@RequestParam(name = "capture_group_id", required = false) List<UUID> captureGroupIds,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireAdministrator(projectId, actor.actorId());
		var generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.contentType(MediaType.parseMediaType(HumanDemonstrationHdf5ExportService.MEDIA_TYPE))
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
				.filename(HumanDemonstrationHdf5ExportService.fileName(captureGroupIds, generatedAt, ExportVersion.V1))
				.build()
				.toString())
			.body(this.service.download(projectId, captureGroupIds, ExportOptions.V1_DEFAULT));
	}

	@GetMapping(value = "/v2/download", produces = HumanDemonstrationHdf5ExportService.MEDIA_TYPE)
	ResponseEntity<byte[]> downloadV2(@PathVariable UUID projectId,
			@RequestParam(name = "capture_group_id", required = false) List<UUID> captureGroupIds,
			@RequestParam(name = "include_raw_candidates", defaultValue = "false") boolean includeRawCandidates,
			@RequestParam(name = "tensor_layout", defaultValue = "true") boolean tensorLayout,
			Authentication authentication) {
		var actor = requireActor(authentication);
		this.projectAccessService.requireAdministrator(projectId, actor.actorId());
		var generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		var options = ExportOptions.v2(includeRawCandidates, tensorLayout);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.contentType(MediaType.parseMediaType(HumanDemonstrationHdf5ExportService.MEDIA_TYPE))
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
				.filename(HumanDemonstrationHdf5ExportService.fileName(captureGroupIds, generatedAt, ExportVersion.V2))
				.build()
				.toString())
			.body(this.service.download(projectId, captureGroupIds, options));
	}

	private static ProjectActor requireActor(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof ProjectActor actor)) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED", "An authenticated platform account is required.",
					403);
		}
		return actor;
	}

}
