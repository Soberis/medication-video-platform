package com.medicationvideo.platform.datasetexport;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectRole;

@Service
class DatasetExportDownloadService {

	private final ProjectAccessService projectAccessService;
	private final DatasetExportRepository exportRepository;
	private final ExportArtifactRepository artifactRepository;
	private final DatasetExportStoragePort storage;
	private final AuditEventRecorder auditRecorder;
	private final Duration authorizationLifetime;

	DatasetExportDownloadService(
			ProjectAccessService projectAccessService,
			DatasetExportRepository exportRepository,
			ExportArtifactRepository artifactRepository,
			DatasetExportStoragePort storage,
			AuditEventRecorder auditRecorder,
			@Value("${platform.dataset-export.download-authorization-ttl:PT5M}")
			Duration authorizationLifetime) {
		if (authorizationLifetime.isNegative() || authorizationLifetime.isZero()) {
			throw new IllegalArgumentException(
					"Dataset export download authorization lifetime must be positive.");
		}
		this.projectAccessService = projectAccessService;
		this.exportRepository = exportRepository;
		this.artifactRepository = artifactRepository;
		this.storage = storage;
		this.auditRecorder = auditRecorder;
		this.authorizationLifetime = authorizationLifetime;
	}

	@Transactional
	DatasetExportDownloadAuthorizationResponse authorize(
			UUID projectId,
			UUID datasetVersionId,
			String actorId,
			String requestId,
			String traceId) {
		if (this.projectAccessService.requireMember(projectId, actorId) != ProjectRole.ADMIN) {
			throw new DatasetVersionException(
					"DATASET_EXPORT_DOWNLOAD_ADMIN_REQUIRED",
					"Only a current project administrator can authorize an export download.",
					403);
		}
		var export = this.exportRepository.findByDatasetVersionId(datasetVersionId)
				.filter(value -> value.getProjectId().equals(projectId))
				.orElseThrow(() -> new DatasetVersionException(
						"DATASET_EXPORT_NOT_FOUND",
						"The dataset version does not have an export request.",
						404));
		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		if (export.getStatus() != DatasetExportStatus.AVAILABLE) {
			throw new DatasetVersionException(
					"DATASET_EXPORT_NOT_AVAILABLE",
					"The dataset export package is not available.",
					409);
		}
		if (!export.getExpiresAt().isAfter(now)) {
			throw new DatasetVersionException(
					"DATASET_EXPORT_AUTHORIZATION_EXPIRED",
					"The dataset export authorization has expired.",
					409);
		}
		var artifact = this.artifactRepository.findByDatasetExportId(export.getDatasetExportId())
				.orElseThrow(() -> new IllegalStateException(
						"An available dataset export must have one package artifact."));
		var remaining = Duration.between(now, export.getExpiresAt());
		var lifetime = remaining.compareTo(this.authorizationLifetime) < 0
				? remaining
				: this.authorizationLifetime;
		var expiresAt = now.plus(lifetime);
		var downloadUrl = this.storage.authorizeRead(artifact.getObjectReference(), lifetime);
		this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.USER,
				actorId,
				projectId,
				"DATASET_EXPORT_DOWNLOAD_AUTHORIZED",
				"DatasetExport",
				export.getDatasetExportId().toString(),
				Long.toString(export.getVersion()),
				AuditOutcome.SUCCEEDED,
				"SHORT_LIVED_DOWNLOAD_AUTHORIZATION_ISSUED",
				requestId,
				traceId,
				"EXPORT_ARTIFACT:" + artifact.getExportArtifactId(),
				Map.of(
						"dataset_version_id", datasetVersionId.toString(),
						"export_artifact_id", artifact.getExportArtifactId().toString(),
						"authorization_expires_at", expiresAt.toString(),
						"artifact_sha256", artifact.getSha256())));
		return new DatasetExportDownloadAuthorizationResponse(
				export.getDatasetExportId(),
				artifact.getExportArtifactId(),
				downloadUrl,
				expiresAt,
				artifact.getFileName(),
				artifact.getMediaType(),
				artifact.getSizeBytes(),
				artifact.getSha256());
	}
}
