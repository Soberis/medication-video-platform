package com.medicationvideo.platform.assetcapture;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CaptureSyncCommandService {

	private final CaptureSyncInputAssemblyService inputAssembly;

	private final CaptureSyncJobService jobService;

	private final CaptureSyncJobRepository jobRepository;

	private final CaptureSyncJobAttemptRepository attemptRepository;

	private final SyncProfileRegistry profileRegistry;

	CaptureSyncCommandService(CaptureSyncInputAssemblyService inputAssembly, CaptureSyncJobService jobService,
			CaptureSyncJobRepository jobRepository, CaptureSyncJobAttemptRepository attemptRepository,
			SyncProfileRegistry profileRegistry) {
		this.inputAssembly = inputAssembly;
		this.jobService = jobService;
		this.jobRepository = jobRepository;
		this.attemptRepository = attemptRepository;
		this.profileRegistry = profileRegistry;
	}

	@Transactional
	CaptureSyncJobResponse startSynchronization(UUID projectId, UUID captureSessionId, String syncProfileKey,
			String idempotencyKey, String requestedBy) {
		var profile = this.profileRegistry.require(syncProfileKey);
		var input = this.inputAssembly.assemble(projectId, captureSessionId);
		var job = this.jobService.create(input, profile, idempotencyKey, requestedBy);
		var attempt = this.attemptRepository.findTopByCaptureSyncJobIdOrderByAttemptDesc(job.getCaptureSyncJobId())
			.orElseThrow(() -> new IllegalStateException("A synchronization job must have an attempt."));
		return CaptureSyncJobResponse.from(job, attempt, projectId);
	}

	@Transactional(readOnly = true)
	CaptureSyncJobResponse getJob(UUID projectId, UUID captureSessionId, UUID jobId) {
		var job = this.jobRepository.findById(jobId)
			.filter(candidate -> candidate.getProjectId().equals(projectId)
					&& candidate.getCaptureSessionId().equals(captureSessionId))
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_SYNC_JOB_NOT_FOUND",
					"The capture synchronization job was not found."));
		var attempt = this.attemptRepository.findTopByCaptureSyncJobIdOrderByAttemptDesc(jobId)
			.orElseThrow(() -> new IllegalStateException("A synchronization job must have an attempt."));
		return CaptureSyncJobResponse.from(job, attempt, projectId);
	}

}
