package com.medicationvideo.platform.assetcapture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CaptureSyncExecutionService {

	private final CaptureSyncJobRepository jobRepository;

	private final CaptureSyncJobAttemptRepository attemptRepository;

	private final CaptureSyncJobService jobService;

	private final CaptureSyncResultInboxPort inbox;

	private final CaptureSyncStoragePort storage;

	private final CaptureSyncArtifactRepository artifactRepository;

	private final CaptureSyncRevisionRepository revisionRepository;

	private final CaptureSyncStreamRevisionRepository streamRevisionRepository;

	private final CaptureSessionRepository sessionRepository;

	private final SyncProfileRegistry profileRegistry;

	private final Duration resultClockSkew;

	private final Set<String> approvedSoftwareDigests;

	CaptureSyncExecutionService(CaptureSyncJobRepository jobRepository,
			CaptureSyncJobAttemptRepository attemptRepository, CaptureSyncJobService jobService,
			CaptureSyncResultInboxPort inbox, CaptureSyncStoragePort storage,
			CaptureSyncArtifactRepository artifactRepository, CaptureSyncRevisionRepository revisionRepository,
			CaptureSyncStreamRevisionRepository streamRevisionRepository, CaptureSessionRepository sessionRepository,
			SyncProfileRegistry profileRegistry,
			@Value("${platform.capture-sync.result-clock-skew:PT5M}") Duration resultClockSkew,
			@Value("${platform.capture-sync.approved-software-digests:}") String approvedSoftwareDigests) {
		this.jobRepository = jobRepository;
		this.attemptRepository = attemptRepository;
		this.jobService = jobService;
		this.inbox = inbox;
		this.storage = storage;
		this.artifactRepository = artifactRepository;
		this.revisionRepository = revisionRepository;
		this.streamRevisionRepository = streamRevisionRepository;
		this.sessionRepository = sessionRepository;
		this.profileRegistry = profileRegistry;
		this.resultClockSkew = requirePositive(resultClockSkew, "Capture sync result clock skew");
		this.approvedSoftwareDigests = parseApprovedDigests(approvedSoftwareDigests);
	}

	@Transactional
	String acquire(UUID jobId, int attemptNumber, String leaseToken, CaptureSyncLeaseRequest request) {
		var job = lockJob(jobId);
		var attempt = lockAttempt(jobId, attemptNumber);
		requireLease(attempt, leaseToken);
		return this.jobService.acquire(job.getProjectId(), jobId, attemptNumber, request.workerId(),
				Duration.ofMinutes(30));
	}

	@Transactional
	CaptureSyncResultAcceptedResponse acceptResult(UUID jobId, int attemptNumber, String leaseToken,
			CaptureSyncResultRequest request) {
		var now = now();
		var job = lockJob(jobId);
		var attempt = lockAttempt(jobId, attemptNumber);
		requireResultIdentity(job, attempt, jobId, attemptNumber, leaseToken, request, now);

		var fingerprint = request.fingerprint();
		var existing = this.inbox.find(request.messageId());
		if (existing.isPresent()) {
			return requireSameReceipt(existing.get(), request, fingerprint);
		}
		if (!this.approvedSoftwareDigests.contains(request.softwareDigest())) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_WORKER_SOFTWARE_UNAPPROVED",
					"The Worker software digest is not approved for capture synchronization.", false);
		}
		attempt.requireActiveLease(now);
		if (job.getStatus() != CaptureSyncJobStatus.RUNNING) {
			throw new CaptureSyncExecutionException("STALE_ATTEMPT",
					"The capture synchronization job is no longer running.", false);
		}

		var receipt = this.inbox.record(request.messageId(), job.getCaptureSyncJobId(), attempt.getAttempt(),
				fingerprint, request.status().name(), now);
		if (!receipt.resultFingerprint().equals(fingerprint)) {
			throw new CaptureSyncExecutionException("INBOX_MESSAGE_REUSED",
					"The result message identifier was reused with different content.", false);
		}

		if (request.status() == CaptureSyncResultStatus.SUCCEEDED) {
			var profile = this.profileRegistry.require(job.getSyncProfileKey());
			validateFrozenInput(job, request, profile);
			var revision = acceptSucceededResult(job, attempt, profile, request, now);
			return new CaptureSyncResultAcceptedResponse(receipt.messageId(), receipt.jobId(), receipt.attempt(),
					CaptureSyncResultStatus.SUCCEEDED, receipt.processedAt(), revision.getCaptureSyncRevisionId());
		}

		var error = request.error();
		attempt.fail(error.errorClass(), error.errorCode(), error.safeErrorMessage(), error.retryable(), now);
		job.fail(now);
		return new CaptureSyncResultAcceptedResponse(receipt.messageId(), receipt.jobId(), receipt.attempt(),
				CaptureSyncResultStatus.FAILED, receipt.processedAt(), null);
	}

	private CaptureSyncRevision acceptSucceededResult(CaptureSyncJob job, CaptureSyncJobAttempt attempt,
			SyncProfile profile, CaptureSyncResultRequest request, Instant now) {
		var success = request.success();
		var streams = toStreamRevisionInputs(job, success.streams());
		var revision = this.jobService.acceptRevision(job.getProjectId(), job.getCaptureSyncJobId(),
				attempt.getAttempt(), request.leaseToken(), profile, request.supersedesSyncRevisionId(),
				success.summary(), streams, "synthetic-worker");
		registerArtifact(revision, success.artifact(), now);
		updateCaptureReadModel(job, revision, now);
		return revision;
	}

	private void registerArtifact(CaptureSyncRevision revision, CaptureSyncArtifactPayload artifact, Instant now) {
		if (!revision.getLogicalContentSha256().equals(artifact.logicalContentSha256())) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_LOGICAL_DIGEST_MISMATCH",
					"The artifact logical content digest does not match the accepted revision.", false);
		}
		var object = new CaptureSyncStoragePort.ObjectReference(artifact.storageBucket(), artifact.objectKey());
		this.storage.verify(object, artifact.sizeBytes(), artifact.sha256(), CaptureSyncArtifact.MEDIA_TYPE);
		var manifest = new LinkedHashMap<>(artifact.artifactManifest());
		this.artifactRepository.saveAndFlush(CaptureSyncArtifact.register(revision.getCaptureSyncRevisionId(),
				artifact.sizeBytes(), artifact.sha256(), artifact.logicalContentSha256(), object, manifest,
				artifact.artifactManifestSha256(), now));
	}

	private void updateCaptureReadModel(CaptureSyncJob job, CaptureSyncRevision revision, Instant now) {
		var session = this.sessionRepository.findForMediaProjection(job.getCaptureSessionId(), job.getProjectId())
			.orElseThrow(() -> new IllegalStateException("The capture session disappeared during synchronization."));
		session.acceptSyncRevision(revision.getCaptureSyncRevisionId(), now);
		this.sessionRepository.saveAndFlush(session);
	}

	private static void validateManifestDigest(Map<String, Object> manifest, String expectedDigest) {
		// Manifest digest canonicalization is owned by the Worker writer; Core API stores
		// the
		// verified digest supplied with the immutable artifact registration request.
		if (expectedDigest == null || !expectedDigest.matches("[a-f0-9]{64}")) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_MANIFEST_DIGEST_MISMATCH",
					"The artifact manifest digest is invalid.", false);
		}
		if (!manifest.containsKey("schema_version")) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_MANIFEST_INVALID",
					"The artifact manifest is missing required metadata.", false);
		}
	}

	private static List<CaptureSyncStreamRevisionInput> toStreamRevisionInputs(CaptureSyncJob job,
			List<CaptureSyncStreamRevisionPayload> payloads) {
		var expectedByNumber = job.getExpectedCameras()
			.stream()
			.collect(Collectors.toUnmodifiableMap(ExpectedCamera::cameraNumber, camera -> camera));
		var frozenStreams = job.getFrozenStreams();
		return payloads.stream().map(payload -> {
			var expectedCamera = expectedByNumber.get(payload.cameraNumber());
			if (expectedCamera == null
					|| !expectedCamera.cameraSetSnapshotMemberId().equals(payload.cameraSetSnapshotMemberId())) {
				throw new CaptureSyncExecutionException("CAPTURE_SYNC_STREAM_IDENTITY_MISMATCH",
						"Stream revision camera identity does not match frozen input.", false);
			}
			SyncStream stream = null;
			if (payload.streamPresenceState() != CaptureSyncStreamPresenceState.MISSING) {
				var frozen = frozenStreams.get(payload.cameraNumber());
				if (frozen == null) {
					throw new CaptureSyncExecutionException("CAPTURE_SYNC_STREAM_PROVENANCE_MISMATCH",
							"Stream revision provenance does not match frozen input.", false);
				}
				stream = job.frozenSyncStream(payload.cameraNumber());
			}
			return new CaptureSyncStreamRevisionInput(expectedCamera, stream, payload.streamPresenceState(),
					payload.presentFrameCount(), payload.missingFrameCount(), payload.invalidFrameCount(),
					payload.maxSyncErrorNs(), payload.p95SyncErrorNs());
		}).toList();
	}

	private static void validateFrozenInput(CaptureSyncJob job, CaptureSyncResultRequest request, SyncProfile profile) {
		if (!MessageDigest.isEqual(job.getInputManifestSha256().getBytes(StandardCharsets.US_ASCII),
				request.inputManifestSha256().getBytes(StandardCharsets.US_ASCII))
				|| !MessageDigest.isEqual(job.getSyncProfileSha256().getBytes(StandardCharsets.US_ASCII),
						request.syncProfileSha256().getBytes(StandardCharsets.US_ASCII))
				|| !profile.profileSha256().equals(request.syncProfileSha256())) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_RESULT_DIGEST_MISMATCH",
					"The Worker result digests do not match the frozen job input.", false);
		}
	}

	private void requireResultIdentity(CaptureSyncJob job, CaptureSyncJobAttempt attempt, UUID jobId, int attemptNumber,
			String leaseToken, CaptureSyncResultRequest request, Instant now) {
		if (!jobId.equals(request.jobId()) || attemptNumber != request.attempt()
				|| !attempt.getMessageId().equals(request.messageId())) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_RESULT_IDENTITY_MISMATCH",
					"The result does not identify the current capture synchronization attempt.", false);
		}
		if (!constantTimeEquals(leaseToken, request.leaseToken())) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_LEASE_INVALID",
					"The capture synchronization lease token is invalid.", false);
		}
		requireLease(attempt, leaseToken);
		if (request.startedAt().isBefore(job.getCreatedAt().minus(this.resultClockSkew))
				|| request.completedAt().isAfter(now.plus(this.resultClockSkew))) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_RESULT_TIME_INVALID",
					"The Worker result timestamps exceed the approved clock-skew window.", false);
		}
	}

	private CaptureSyncJob lockJob(UUID jobId) {
		return this.jobRepository.findForUpdate(jobId)
			.orElseThrow(() -> new CaptureSyncExecutionException("CAPTURE_SYNC_JOB_NOT_FOUND",
					"The capture synchronization job was not found.", false));
	}

	private CaptureSyncJobAttempt lockAttempt(UUID jobId, int attemptNumber) {
		return this.attemptRepository.findForUpdate(jobId, attemptNumber)
			.orElseThrow(() -> new CaptureSyncExecutionException("STALE_ATTEMPT",
					"The capture synchronization attempt was not found.", false));
	}

	private static void requireLease(CaptureSyncJobAttempt attempt, String leaseToken) {
		if (!attempt.matchesLeaseHash(CaptureSyncJobAttempt.hashLeaseToken(leaseToken))) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_LEASE_INVALID",
					"The capture synchronization lease token is invalid.", false);
		}
	}

	private CaptureSyncResultAcceptedResponse requireSameReceipt(CaptureSyncInboxReceipt receipt,
			CaptureSyncResultRequest request, String fingerprint) {
		if (!receipt.jobId().equals(request.jobId()) || receipt.attempt() != request.attempt()
				|| !receipt.resultFingerprint().equals(fingerprint)
				|| !receipt.resultStatus().equals(request.status().name())) {
			throw new CaptureSyncExecutionException("INBOX_MESSAGE_REUSED",
					"The result message identifier was reused with different content.", false);
		}
		UUID revisionId = null;
		if (request.status() == CaptureSyncResultStatus.SUCCEEDED) {
			revisionId = this.revisionRepository.findByCaptureSyncJobId(receipt.jobId())
				.map(CaptureSyncRevision::getCaptureSyncRevisionId)
				.orElse(null);
		}
		return new CaptureSyncResultAcceptedResponse(receipt.messageId(), receipt.jobId(), receipt.attempt(),
				request.status(), receipt.processedAt(), revisionId);
	}

	private static Set<String> parseApprovedDigests(String configured) {
		return Arrays.stream(configured.split(",")).map(String::trim).filter(value -> !value.isEmpty()).peek(value -> {
			if (!value.matches("^sha256:[a-f0-9]{64}$")) {
				throw new IllegalArgumentException("Approved Worker software digests must use sha256:<64 hex>.");
			}
		}).collect(Collectors.toUnmodifiableSet());
	}

	private static Duration requirePositive(Duration value, String label) {
		if (value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(label + " must be positive.");
		}
		return value;
	}

	private static boolean constantTimeEquals(String first, String second) {
		return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII),
				second.getBytes(StandardCharsets.US_ASCII));
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

}
