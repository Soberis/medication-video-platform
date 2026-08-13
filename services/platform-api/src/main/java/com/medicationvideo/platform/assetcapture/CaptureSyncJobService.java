package com.medicationvideo.platform.assetcapture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minimal assetcapture application service for synchronization job identity, attempts and
 * immutable revision acceptance. It deliberately has no broker, Worker or artifact
 * responsibilities.
 */
@Service
class CaptureSyncJobService {

	private final CaptureSyncJobRepository jobRepository;

	private final CaptureSyncJobWriter jobWriter;

	private final CaptureSyncJobAttemptRepository attemptRepository;

	private final CaptureSyncRevisionRepository revisionRepository;

	private final CaptureSyncStreamRevisionRepository streamRevisionRepository;

	CaptureSyncJobService(CaptureSyncJobRepository jobRepository, CaptureSyncJobWriter jobWriter,
			CaptureSyncJobAttemptRepository attemptRepository, CaptureSyncRevisionRepository revisionRepository,
			CaptureSyncStreamRevisionRepository streamRevisionRepository) {
		this.jobRepository = jobRepository;
		this.jobWriter = jobWriter;
		this.attemptRepository = attemptRepository;
		this.revisionRepository = revisionRepository;
		this.streamRevisionRepository = streamRevisionRepository;
	}

	@Transactional
	CaptureSyncJob create(CaptureSyncInput input, SyncProfile profile, String idempotencyKey, String requestedBy) {
		return create(input, profile, profile.algorithmKey(), profile.algorithmVersion(), idempotencyKey, requestedBy);
	}

	@Transactional
	CaptureSyncJob create(CaptureSyncInput input, SyncProfile profile, String algorithmKey, String algorithmVersion,
			String idempotencyKey, String requestedBy) {
		if (profile.approval() != SyncProfileApproval.TEST_ONLY) {
			throw new IllegalStateException(
					"Production synchronization profiles remain pending approval; only TEST_ONLY is allowed.");
		}
		if (!profile.algorithmKey().equals(algorithmKey) || !profile.algorithmVersion().equals(algorithmVersion)) {
			throw new IllegalArgumentException("The selected SyncProfile algorithm must match the job algorithm.");
		}
		var inputDigest = input.manifestSha256(profile, algorithmKey, algorithmVersion);
		var fingerprint = fingerprint(input.projectId(), input.captureSessionId(), inputDigest, profile, algorithmKey,
				algorithmVersion);
		var byIdempotency = this.jobRepository.findByProjectIdAndIdempotencyKey(input.projectId(), idempotencyKey);
		if (byIdempotency.isPresent()) {
			return requireSameRequest(byIdempotency.get(), fingerprint);
		}
		var natural = this.jobRepository
			.findByProjectIdAndCaptureSessionIdAndInputManifestSha256AndSyncProfileSha256AndAlgorithmKeyAndAlgorithmVersion(
					input.projectId(), input.captureSessionId(), inputDigest, profile.profileSha256(), algorithmKey,
					algorithmVersion);
		if (natural.isPresent()) {
			return natural.get();
		}
		var now = now();
		var job = CaptureSyncJob.queue(input, profile, algorithmKey, algorithmVersion, idempotencyKey, fingerprint,
				requestedBy, now);
		if (this.jobWriter.insertIfAbsent(job)) {
			this.attemptRepository.saveAndFlush(newAttempt(job, 1, now));
			return job;
		}
		var concurrentByIdempotency = this.jobRepository.findByProjectIdAndIdempotencyKey(input.projectId(),
				idempotencyKey);
		if (concurrentByIdempotency.isPresent()) {
			return requireSameRequest(concurrentByIdempotency.get(), fingerprint);
		}
		var concurrentNatural = this.jobRepository
			.findByProjectIdAndCaptureSessionIdAndInputManifestSha256AndSyncProfileSha256AndAlgorithmKeyAndAlgorithmVersion(
					input.projectId(), input.captureSessionId(), inputDigest, profile.profileSha256(), algorithmKey,
					algorithmVersion);
		if (concurrentNatural.isPresent()) {
			return concurrentNatural.get();
		}
		throw new IllegalStateException("A synchronization job insert conflicted without a resolvable existing job.");
	}

	@Transactional
	CaptureSyncJobAttempt retry(UUID projectId, UUID jobId, String requestedBy) {
		var job = this.jobRepository.findForUpdate(jobId)
			.filter(candidate -> candidate.getProjectId().equals(projectId))
			.orElseThrow(() -> new IllegalStateException("The capture synchronization job was not found."));
		var current = this.attemptRepository.findCurrentForUpdate(jobId)
			.orElseThrow(() -> new IllegalStateException("The capture synchronization job has no attempt."));
		if (!Boolean.TRUE.equals(current.getErrorRetryable())
				|| (current.getStatus() != CaptureSyncJobAttemptStatus.FAILED
						&& current.getStatus() != CaptureSyncJobAttemptStatus.LOST)) {
			throw new IllegalStateException("Only a retryable failed synchronization attempt can be retried.");
		}
		job.requeueForRetry(now());
		var attempt = newAttempt(job, current.getAttempt() + 1, now());
		return this.attemptRepository.save(attempt);
	}

	@Transactional
	String acquire(UUID projectId, UUID jobId, int attemptNumber, String workerId, Duration leaseDuration) {
		var now = now();
		var job = this.jobRepository.findForUpdate(jobId)
			.filter(candidate -> candidate.getProjectId().equals(projectId))
			.orElseThrow(() -> new IllegalStateException("The capture synchronization job was not found."));
		var attempt = this.attemptRepository.findForUpdate(jobId, attemptNumber)
			.orElseThrow(() -> new IllegalStateException("The capture synchronization attempt was not found."));
		var leaseToken = UUID.randomUUID() + UUID.randomUUID().toString();
		attempt.issueLeaseToken(leaseToken);
		attempt.acquire(workerId, now, leaseDuration);
		job.start(now);
		this.attemptRepository.saveAndFlush(attempt);
		return leaseToken;
	}

	@Transactional
	CaptureSyncRevision acceptRevision(UUID projectId, UUID jobId, int attemptNumber, String leaseToken,
			SyncProfile profile, UUID supersedesRevisionId, CaptureSyncRevisionSummary summary,
			List<CaptureSyncStreamRevisionInput> streams, String createdBy) {
		var now = now();
		var job = this.jobRepository.findForUpdate(jobId)
			.filter(candidate -> candidate.getProjectId().equals(projectId))
			.orElseThrow(() -> new IllegalStateException("The capture synchronization job was not found."));
		var attempt = this.attemptRepository.findForUpdate(jobId, attemptNumber)
			.orElseThrow(() -> new IllegalStateException("The capture synchronization attempt was not found."));
		if (!attempt.matchesLeaseHash(CaptureSyncJobAttempt.hashLeaseToken(leaseToken))) {
			throw new IllegalStateException("The capture synchronization lease token is invalid.");
		}
		if (!job.getSyncProfileSha256().equals(profile.profileSha256())
				|| !job.getSyncProfileKey().equals(profile.profileKey())
				|| !job.getSyncProfileVersion().equals(profile.profileVersion())) {
			throw new IllegalStateException("The synchronization result profile does not match frozen input.");
		}
		if (!job.getFrozenReferenceClockSource().equals(profile.referenceClockSource())) {
			throw new IllegalStateException("The selected profile reference clock does not match frozen input.");
		}
		attempt.requireActiveLease(now);
		validateRevisionPayload(job, profile, summary, streams);
		if (supersedesRevisionId != null) {
			this.revisionRepository.findById(supersedesRevisionId)
				.filter(revision -> revision.getProjectId().equals(projectId)
						&& revision.getCaptureSessionId().equals(job.getCaptureSessionId()))
				.orElseThrow(() -> new IllegalStateException("A superseded revision must belong to the same Capture."));
		}
		job.start(now);
		attempt.succeed(now);
		job.succeed(now);
		var revision = CaptureSyncRevision.accept(job, attempt, supersedesRevisionId, profile, summary, createdBy, now);
		this.revisionRepository.saveAndFlush(revision);
		var streamRevisions = streams.stream()
			.map(input -> CaptureSyncStreamRevision.accept(revision, input.expectedCamera(), input.stream(),
					input.presenceState(), input.presentFrameCount(), input.missingFrameCount(),
					input.invalidFrameCount(), input.maxSyncErrorNs(), input.p95SyncErrorNs(), now))
			.toList();
		this.streamRevisionRepository.saveAllAndFlush(streamRevisions);
		return revision;
	}

	static void validateRevisionPayload(CaptureSyncJob job, SyncProfile profile, CaptureSyncRevisionSummary summary,
			List<CaptureSyncStreamRevisionInput> streams) {
		Objects.requireNonNull(job, "job");
		Objects.requireNonNull(profile, "profile");
		Objects.requireNonNull(summary, "summary");
		Objects.requireNonNull(streams, "streams");
		var expectedCameras = job.getExpectedCameras();
		if (streams.size() != expectedCameras.size()) {
			throw new IllegalArgumentException("A revision must contain exactly one stream row per expected camera.");
		}
		var expectedByNumber = expectedCameras.stream()
			.collect(Collectors.toUnmodifiableMap(ExpectedCamera::cameraNumber, camera -> camera));
		var frozenStreams = job.getFrozenStreams();
		var seenCameras = new HashSet<Integer>();
		long receivedCameraCount = 0;
		long presentFrameCount = 0;
		long missingFrameCount = 0;
		long invalidFrameCount = 0;
		Long maximumStreamError = null;
		for (var input : streams) {
			Objects.requireNonNull(input, "stream revision input");
			var cameraNumber = input.expectedCamera().cameraNumber();
			if (!input.expectedCamera().equals(expectedByNumber.get(cameraNumber)) || !seenCameras.add(cameraNumber)) {
				throw new IllegalArgumentException(
						"Stream revision camera identity must exactly match the frozen expected-camera set.");
			}
			var frozenStream = frozenStreams.get(cameraNumber);
			if (input.presenceState() == CaptureSyncStreamPresenceState.MISSING) {
				if (frozenStream != null || input.presentFrameCount() != 0 || input.invalidFrameCount() != 0
						|| input.missingFrameCount() != summary.syncFrameCount() || input.maxSyncErrorNs() != null
						|| input.p95SyncErrorNs() != null) {
					throw new IllegalArgumentException(
							"A missing stream must be absent from frozen input and contribute only missing Frame Slots.");
				}
			}
			else {
				if (frozenStream == null || !frozenStream.matches(input.stream())) {
					throw new IllegalArgumentException(
							"Stream revision provenance must match the frozen input manifest.");
				}
				receivedCameraCount++;
			}
			var streamFrameCount = sumCounts(input.presentFrameCount(), input.missingFrameCount(),
					input.invalidFrameCount());
			if (streamFrameCount != summary.syncFrameCount()) {
				throw new IllegalArgumentException("Each expected camera must contribute one row per Sync Frame.");
			}
			validateErrorSummary(input.maxSyncErrorNs(), input.p95SyncErrorNs(), profile.allowedToleranceNs());
			presentFrameCount = Math.addExact(presentFrameCount, input.presentFrameCount());
			missingFrameCount = Math.addExact(missingFrameCount, input.missingFrameCount());
			invalidFrameCount = Math.addExact(invalidFrameCount, input.invalidFrameCount());
			if (input.maxSyncErrorNs() != null
					&& (maximumStreamError == null || input.maxSyncErrorNs() > maximumStreamError)) {
				maximumStreamError = input.maxSyncErrorNs();
			}
		}
		if (receivedCameraCount != summary.cameraCountReceived() || presentFrameCount != summary.presentFrameCount()
				|| missingFrameCount != summary.missingFrameCount()
				|| invalidFrameCount != summary.invalidFrameCount()) {
			throw new IllegalArgumentException("Revision totals must equal the complete per-camera stream matrix.");
		}
		validateErrorSummary(summary.maxSyncErrorNs(), summary.p95SyncErrorNs(), profile.allowedToleranceNs());
		if (!Objects.equals(maximumStreamError, summary.maxSyncErrorNs())) {
			throw new IllegalArgumentException("Revision maximum sync error must equal the per-stream maximum.");
		}
		if (!job.getFrozenReferenceClockSource().equals(summary.referenceClockSource())) {
			throw new IllegalArgumentException("Revision reference clock source must match the frozen SyncProfile.");
		}
		if (summary.referenceCameraNumber() != null) {
			var referenceCamera = summary.referenceCameraNumber();
			if (!seenCameras.contains(referenceCamera) || frozenStreams.get(referenceCamera) == null) {
				throw new IllegalArgumentException("A reference camera must be a received frozen-input camera.");
			}
		}
	}

	private static long sumCounts(long present, long missing, long invalid) {
		if (present < 0 || missing < 0 || invalid < 0) {
			throw new IllegalArgumentException("Stream revision counts must not be negative.");
		}
		return Math.addExact(Math.addExact(present, missing), invalid);
	}

	private static void validateErrorSummary(Long maximum, Long p95, long tolerance) {
		if (maximum != null && (maximum < 0 || maximum > tolerance)
				|| p95 != null && (p95 < 0 || p95 > tolerance || maximum == null || p95 > maximum)) {
			throw new IllegalArgumentException("Sync error summaries must be ordered and within the frozen tolerance.");
		}
	}

	private CaptureSyncJobAttempt newAttempt(CaptureSyncJob job, int attemptNumber, Instant now) {
		var leaseToken = UUID.randomUUID() + UUID.randomUUID().toString();
		return CaptureSyncJobAttempt.queue(job.getCaptureSyncJobId(), attemptNumber, UUID.randomUUID(),
				CaptureSyncJobAttempt.hashLeaseToken(leaseToken), now);
	}

	private static CaptureSyncJob requireSameRequest(CaptureSyncJob job, String fingerprint) {
		if (!MessageDigest.isEqual(job.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
				fingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw new IllegalStateException("The synchronization idempotency key was reused with different input.");
		}
		return job;
	}

	private static String fingerprint(UUID projectId, UUID captureSessionId, String inputDigest, SyncProfile profile,
			String algorithmKey, String algorithmVersion) {
		var canonical = String.join("\n", projectId.toString(), captureSessionId.toString(), inputDigest,
				profile.profileKey(), profile.profileVersion(), profile.profileSha256(), algorithmKey,
				algorithmVersion);
		try {
			return java.util.HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable in the Java runtime.", exception);
		}
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

}
