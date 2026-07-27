package com.medicationvideo.platform.mediaprivacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.assetcapture.ClipAssetMediaIngestService;

@Service
class MediaJobExecutionService {

	private final MediaJobRepository jobRepository;
	private final MediaJobAttemptRepository attemptRepository;
	private final MediaReportRepository reportRepository;
	private final MediaResultInboxPort inbox;
	private final ClipAssetMediaIngestService assetIngest;
	private final MediaJobAttemptQueueService attemptQueue;
	private final MediaJobRetryPolicy retryPolicy;
	private final Duration leaseDuration;
	private final Duration heartbeatInterval;
	private final Duration inputAuthorizationLifetime;
	private final Duration resultClockSkew;
	private final Set<String> approvedSoftwareDigests;

	MediaJobExecutionService(
			MediaJobRepository jobRepository,
			MediaJobAttemptRepository attemptRepository,
			MediaReportRepository reportRepository,
			MediaResultInboxPort inbox,
			ClipAssetMediaIngestService assetIngest,
			MediaJobAttemptQueueService attemptQueue,
			MediaJobRetryPolicy retryPolicy,
			@Value("${platform.media-probe.lease-duration:PT30M}") Duration leaseDuration,
			@Value("${platform.media-probe.heartbeat-interval:PT5M}") Duration heartbeatInterval,
			@Value("${platform.media-probe.input-authorization-ttl:PT15M}")
			Duration inputAuthorizationLifetime,
			@Value("${platform.media-probe.result-clock-skew:PT5M}") Duration resultClockSkew,
			@Value("${platform.media-probe.approved-software-digests:}")
			String approvedSoftwareDigests) {
		this.jobRepository = jobRepository;
		this.attemptRepository = attemptRepository;
		this.reportRepository = reportRepository;
		this.inbox = inbox;
		this.assetIngest = assetIngest;
		this.attemptQueue = attemptQueue;
		this.retryPolicy = retryPolicy;
		this.leaseDuration = requirePositive(leaseDuration, "Media probe lease duration");
		this.heartbeatInterval = requireHeartbeatInterval(heartbeatInterval, this.leaseDuration);
		this.inputAuthorizationLifetime = requirePositive(
				inputAuthorizationLifetime, "Media input authorization lifetime");
		this.resultClockSkew = requirePositive(resultClockSkew, "Media result clock skew");
		this.approvedSoftwareDigests = parseApprovedDigests(approvedSoftwareDigests);
	}

	@Transactional
	MediaLeaseResponse acquire(
			UUID mediaJobId,
			int attemptNumber,
			String leaseToken,
			MediaLeaseRequest request) {
		var now = now();
		var job = lockJob(mediaJobId);
		var attempt = lockAttempt(mediaJobId, attemptNumber);
		requireLease(attempt, leaseToken);
		attempt.acquire(request.workerId(), now, this.leaseDuration);
		job.start(now);
		var grant = this.assetIngest.begin(
				job.getProjectId(),
				job.getClipAssetId(),
				this.inputAuthorizationLifetime,
				now);
		return new MediaLeaseResponse(
				job.getMediaJobId(),
				attempt.getAttempt(),
				grant.inputUrl(),
				grant.expiresAt(),
				attempt.getLeaseUntil(),
				this.heartbeatInterval.toSeconds(),
				job.getInputDigest(),
				job.getInputSizeBytes(),
				job.getContentType());
	}

	@Transactional
	MediaHeartbeatResponse heartbeat(
			UUID mediaJobId,
			int attemptNumber,
			String leaseToken,
			MediaLeaseRequest request) {
		var now = now();
		var job = lockJob(mediaJobId);
		var attempt = lockAttempt(mediaJobId, attemptNumber);
		requireLease(attempt, leaseToken);
		if (job.getStatus() != MediaJobStatus.RUNNING) {
			throw new MediaJobExecutionException(
					"STALE_ATTEMPT", "The media job is no longer running.", false);
		}
		attempt.heartbeat(request.workerId(), now, this.leaseDuration);
		return new MediaHeartbeatResponse(
				job.getMediaJobId(),
				attempt.getAttempt(),
				attempt.getLeaseUntil(),
				this.heartbeatInterval.toSeconds());
	}

	@Transactional
	MediaResultAcceptedResponse acceptResult(
			UUID mediaJobId,
			int attemptNumber,
			String leaseToken,
			MediaProbeResultRequest request) {
		var now = now();
		var job = lockJob(mediaJobId);
		var attempt = lockAttempt(mediaJobId, attemptNumber);
		requireResultIdentity(job, attempt, mediaJobId, attemptNumber, leaseToken, request, now);

		var fingerprint = request.fingerprint();
		var existing = this.inbox.find(request.messageId());
		if (existing.isPresent()) {
			return requireSameReceipt(existing.get(), request, fingerprint);
		}
		if (!this.approvedSoftwareDigests.contains(request.softwareDigest())) {
			throw new MediaJobExecutionException(
					"MEDIA_WORKER_SOFTWARE_UNAPPROVED",
					"The Worker software digest is not approved for this profile.",
					false);
		}
		attempt.requireActiveLease(now);
		if (job.getStatus() != MediaJobStatus.RUNNING) {
			throw new MediaJobExecutionException(
					"STALE_ATTEMPT", "The media job is no longer running.", false);
		}

		var receipt = this.inbox.record(
				request.messageId(),
				job.getMediaJobId(),
				attempt.getAttempt(),
				fingerprint,
				request.status().name(),
				now);
		if (!receipt.resultFingerprint().equals(fingerprint)) {
			throw new MediaJobExecutionException(
					"INBOX_MESSAGE_REUSED",
					"The result message identifier was reused with different content.",
					false);
		}

		if (request.status() == MediaProbeResultStatus.SUCCEEDED) {
			this.reportRepository.save(MediaReport.accept(
					job, request.report(), request.softwareDigest(), now));
			attempt.succeed(now);
			job.succeed(now);
			this.assetIngest.complete(job.getProjectId(), job.getClipAssetId(), true, now);
		}
		else {
			var error = request.error();
			attempt.fail(error, now);
			if (this.retryPolicy.shouldRetry(error, attempt.getAttempt())) {
				var nextAttempt = attempt.getAttempt() + 1;
				var availableAt = this.retryPolicy.nextAvailableAt(
						job.getMediaJobId(), nextAttempt, now);
				job.waitForRetry(now);
				this.attemptQueue.enqueue(job, nextAttempt, now, availableAt);
			}
			else {
				job.fail(now);
				this.assetIngest.complete(job.getProjectId(), job.getClipAssetId(), false, now);
			}
		}
		return response(receipt);
	}

	@Transactional(readOnly = true)
	MediaReport getReport(UUID projectId, UUID mediaJobId) {
		var job = this.jobRepository.findById(mediaJobId)
				.filter(candidate -> candidate.getProjectId().equals(projectId))
				.orElseThrow(() -> new MediaJobNotFoundException("The media job was not found."));
		if (job.getStatus() != MediaJobStatus.SUCCEEDED) {
			throw new MediaJobExecutionException(
					"MEDIA_REPORT_NOT_FOUND", "The media report is not available.", false);
		}
		return this.reportRepository.findByMediaJobId(mediaJobId)
				.orElseThrow(() -> new IllegalStateException(
						"A succeeded media job has no immutable report."));
	}

	/**
	 * Reads the immutable technical facts through the video identity used by the management UI.
	 * A report only exists after a successful full-stream media probe.
	 */
	@Transactional(readOnly = true)
	MediaReport getReportForClipAsset(UUID projectId, UUID clipAssetId) {
		return this.reportRepository.findByProjectIdAndClipAssetId(projectId, clipAssetId)
				.orElseThrow(() -> new MediaJobExecutionException(
						"MEDIA_REPORT_NOT_FOUND", "The media report is not available.", false));
	}

	private void requireResultIdentity(
			MediaJob job,
			MediaJobAttempt attempt,
			UUID mediaJobId,
			int attemptNumber,
			String leaseToken,
			MediaProbeResultRequest request,
			Instant now) {
		if (!mediaJobId.equals(request.jobId()) || attemptNumber != request.attempt()
				|| !attempt.getMessageId().equals(request.messageId())) {
			throw new MediaJobExecutionException(
					"MEDIA_RESULT_IDENTITY_MISMATCH",
					"The result does not identify the current media job attempt.",
					false);
		}
		if (!constantTimeEquals(leaseToken, request.leaseToken())) {
			throw new MediaJobExecutionException(
					"MEDIA_JOB_LEASE_INVALID", "The media job lease token is invalid.", false);
		}
		requireLease(attempt, leaseToken);
		if (!constantTimeEquals(job.getInputDigest(), request.inputSha256())
				|| !constantTimeEquals(job.getRecipeDigest(), request.recipeDigest())) {
			throw new MediaJobExecutionException(
					"MEDIA_RESULT_DIGEST_MISMATCH",
					"The result input or recipe digest does not match the job.",
					false);
		}
		if (request.startedAt().isBefore(job.getCreatedAt().minus(this.resultClockSkew))
				|| request.completedAt().isAfter(now.plus(this.resultClockSkew))) {
			throw new MediaJobExecutionException(
					"MEDIA_RESULT_TIME_INVALID",
					"The Worker result timestamps exceed the approved clock-skew window.",
					false);
		}
	}

	private MediaJob lockJob(UUID mediaJobId) {
		return this.jobRepository.findForUpdate(mediaJobId)
				.orElseThrow(() -> new MediaJobNotFoundException("The media job was not found."));
	}

	private MediaJobAttempt lockAttempt(UUID mediaJobId, int attemptNumber) {
		return this.attemptRepository.findForUpdate(mediaJobId, attemptNumber)
				.orElseThrow(() -> new MediaJobExecutionException(
						"STALE_ATTEMPT", "The media job attempt was not found.", false));
	}

	private static void requireLease(MediaJobAttempt attempt, String leaseToken) {
		if (!attempt.matchesLeaseHash(MediaLeaseCapabilityFactory.hash(leaseToken))) {
			throw new MediaJobExecutionException(
					"MEDIA_JOB_LEASE_INVALID", "The media job lease token is invalid.", false);
		}
	}

	private static MediaResultAcceptedResponse requireSameReceipt(
			MediaInboxReceipt receipt,
			MediaProbeResultRequest request,
			String fingerprint) {
		if (!receipt.jobId().equals(request.jobId())
				|| receipt.attempt() != request.attempt()
				|| !receipt.resultFingerprint().equals(fingerprint)
				|| !receipt.resultStatus().equals(request.status().name())) {
			throw new MediaJobExecutionException(
					"INBOX_MESSAGE_REUSED",
					"The result message identifier was reused with different content.",
					false);
		}
		return response(receipt);
	}

	private static MediaResultAcceptedResponse response(MediaInboxReceipt receipt) {
		return new MediaResultAcceptedResponse(
				receipt.messageId(),
				receipt.jobId(),
				receipt.attempt(),
				MediaProbeResultStatus.valueOf(receipt.resultStatus()),
				receipt.processedAt());
	}

	private static Set<String> parseApprovedDigests(String configured) {
		return Arrays.stream(configured.split(","))
				.map(String::trim)
				.filter(value -> !value.isEmpty())
				.peek(value -> {
					if (!value.matches("^sha256:[a-f0-9]{64}$")) {
						throw new IllegalArgumentException(
								"Approved Worker software digests must use sha256:<64 hex>.");
					}
				})
				.collect(Collectors.toUnmodifiableSet());
	}

	private static Duration requirePositive(Duration value, String label) {
		if (value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(label + " must be positive.");
		}
		return value;
	}

	private static Duration requireHeartbeatInterval(Duration value, Duration leaseDuration) {
		requirePositive(value, "Media probe heartbeat interval");
		if (value.toSeconds() < 1 || value.compareTo(leaseDuration) >= 0) {
			throw new IllegalArgumentException(
					"Media probe heartbeat interval must be at least one second and shorter than the lease.");
		}
		return value;
	}

	private static boolean constantTimeEquals(String first, String second) {
		return MessageDigest.isEqual(
				first.getBytes(StandardCharsets.US_ASCII),
				second.getBytes(StandardCharsets.US_ASCII));
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}
}
