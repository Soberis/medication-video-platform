package com.medicationvideo.platform.datasetexport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.medicationvideo.platform.annotationreview.AnnotationExportQueryService;
import com.medicationvideo.platform.annotationreview.AnnotationExportQueryService.AnnotationExportReference;
import com.medicationvideo.platform.assetcapture.AssetCatalogQueryException;
import com.medicationvideo.platform.assetcapture.ClipAssetExportReadService;
import com.medicationvideo.platform.assetcapture.ClipAssetExportReadService.ClipAssetExportReadGrant;
import com.medicationvideo.platform.assetcapture.ClipAssetExportReadService.ClipAssetExportReference;
import com.medicationvideo.platform.assetcapture.ClipAssetExportReadService.ClipAssetExportSummary;
import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;

@Service
class DatasetExportExecutionService {

	private static final long OUTPUT_PART_SIZE_BYTES = 64L * 1024L * 1024L;
	private static final Set<String> METADATA_PATHS = Set.of(
			"README.md",
			"label_schema.json",
			"manifest.jsonl",
			"action_segments.jsonl",
			"splits/pilot.txt",
			"splits/holdout.txt",
			"SHA256SUMS");

	private final DatasetExportRepository exportRepository;
	private final DatasetExportAttemptRepository attemptRepository;
	private final ExportArtifactRepository artifactRepository;
	private final DatasetBuildSpecificationRepository specificationRepository;
	private final DatasetMemberSessionRepository memberRepository;
	private final DatasetMemberStreamRepository streamRepository;
	private final DatasetSplitAssignmentRepository assignmentRepository;
	private final ClipAssetExportReadService assetReadService;
	private final AnnotationExportQueryService annotationQueryService;
	private final DatasetExportStoragePort storage;
	private final DatasetExportResultInboxPort inbox;
	private final DatasetExportAttemptQueueService attemptQueue;
	private final DatasetExportRetryPolicy retryPolicy;
	private final AuditEventRecorder auditRecorder;
	private final Duration leaseDuration;
	private final Duration heartbeatInterval;
	private final Duration inputAuthorizationLifetime;
	private final Duration outputPartAuthorizationLifetime;
	private final Duration resultClockSkew;
	private final Set<String> approvedSoftwareDigests;

	DatasetExportExecutionService(
			DatasetExportRepository exportRepository,
			DatasetExportAttemptRepository attemptRepository,
			ExportArtifactRepository artifactRepository,
			DatasetBuildSpecificationRepository specificationRepository,
			DatasetMemberSessionRepository memberRepository,
			DatasetMemberStreamRepository streamRepository,
			DatasetSplitAssignmentRepository assignmentRepository,
			ClipAssetExportReadService assetReadService,
			AnnotationExportQueryService annotationQueryService,
			DatasetExportStoragePort storage,
			DatasetExportResultInboxPort inbox,
			DatasetExportAttemptQueueService attemptQueue,
			DatasetExportRetryPolicy retryPolicy,
			AuditEventRecorder auditRecorder,
			@Value("${platform.dataset-export.lease-duration:PT2H}") Duration leaseDuration,
			@Value("${platform.dataset-export.heartbeat-interval:PT5M}") Duration heartbeatInterval,
			@Value("${platform.dataset-export.input-authorization-ttl:PT2H}")
			Duration inputAuthorizationLifetime,
			@Value("${platform.dataset-export.output-part-authorization-ttl:PT15M}")
			Duration outputPartAuthorizationLifetime,
			@Value("${platform.dataset-export.result-clock-skew:PT5M}") Duration resultClockSkew,
			@Value("${platform.dataset-export.approved-software-digests:}")
			String approvedSoftwareDigests) {
		this.exportRepository = exportRepository;
		this.attemptRepository = attemptRepository;
		this.artifactRepository = artifactRepository;
		this.specificationRepository = specificationRepository;
		this.memberRepository = memberRepository;
		this.streamRepository = streamRepository;
		this.assignmentRepository = assignmentRepository;
		this.assetReadService = assetReadService;
		this.annotationQueryService = annotationQueryService;
		this.storage = storage;
		this.inbox = inbox;
		this.attemptQueue = attemptQueue;
		this.retryPolicy = retryPolicy;
		this.auditRecorder = auditRecorder;
		this.leaseDuration = requirePositive(leaseDuration, "Dataset export lease duration");
		this.heartbeatInterval = requireHeartbeatInterval(heartbeatInterval, leaseDuration);
		this.inputAuthorizationLifetime = requirePositive(
				inputAuthorizationLifetime, "Dataset export input authorization lifetime");
		this.outputPartAuthorizationLifetime = requirePositive(
				outputPartAuthorizationLifetime, "Dataset export part authorization lifetime");
		this.resultClockSkew = requirePositive(resultClockSkew, "Dataset export result clock skew");
		this.approvedSoftwareDigests = parseApprovedDigests(approvedSoftwareDigests);
	}

	@Transactional
	DatasetExportLeaseResponse acquire(
			UUID exportId,
			int attemptNumber,
			String leaseToken,
			DatasetExportLeaseRequest request) {
		var now = now();
		var export = lockExport(exportId);
		var attempt = lockAttempt(exportId, attemptNumber);
		requireLease(attempt, leaseToken);
		attempt.acquire(request.workerId(), now, this.leaseDuration);
		export.start(now);
		var upload = attempt.getUpload();
		if (upload == null) {
			upload = this.storage.create(outputObjectKey(export, attempt), ExportArtifact.MEDIA_TYPE);
			registerAbortOnRollback(upload);
			attempt.attachUpload(upload, now);
		}

		var specification = this.specificationRepository.findByDatasetVersionId(export.getDatasetVersionId())
				.filter(value -> value.getDatasetBuildSpecificationId()
						.equals(export.getDatasetBuildSpecificationId()))
				.orElseThrow(DatasetExportExecutionService::frozenEvidenceChanged);
		var members = this.memberRepository
				.findByDatasetVersionIdOrderByCandidateIndexAsc(export.getDatasetVersionId());
		var streams = this.streamRepository
				.findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(
						export.getDatasetVersionId());
		var assignments = this.assignmentRepository
				.findByDatasetSplitPlanIdOrderByCandidateIndexAsc(specification.getDatasetSplitPlanId());
		if (members.isEmpty() || streams.isEmpty() || assignments.size() != members.size()) {
			throw frozenEvidenceChanged();
		}

		var assetReferences = assetReferences(streams);
		List<ClipAssetExportReadGrant> grants;
		try {
			grants = this.assetReadService.authorize(
					export.getProjectId(),
					assetReferences,
					this.inputAuthorizationLifetime,
					now);
		}
		catch (AssetCatalogQueryException exception) {
			throw new DatasetExportExecutionException(
					exception.getErrorCode(),
					exception.getMessage(),
					"OBJECT_STORAGE_UNAVAILABLE".equals(exception.getErrorCode()));
		}
		AnnotationExportQueryService.AnnotationExportBundle annotations;
		try {
			annotations = this.annotationQueryService.getExact(
					export.getProjectId(),
					specification.getLabelSchemaDigest(),
					annotationReferences(streams));
		}
		catch (IllegalStateException exception) {
			throw frozenEvidenceChanged();
		}

		var grantsByAsset = uniqueMap(grants, ClipAssetExportReadGrant::clipAssetId);
		var revisionsById = uniqueMap(
				annotations.revisions(),
				com.medicationvideo.platform.annotationreview.AnnotationExportQueryService
						.AnnotationExportRevision::annotationRevisionId);
		var assignmentsByMember = uniqueMap(
				assignments, DatasetSplitAssignment::getDatasetMemberSessionId);
		var streamsByMember = streams.stream().collect(Collectors.groupingBy(
				DatasetMemberStream::getDatasetMemberSessionId,
				LinkedHashMap::new,
				Collectors.toList()));
		var sessions = members.stream().map(member -> {
			var assignment = requireValue(
					assignmentsByMember, member.getDatasetMemberSessionId(), "split assignment");
			var sessionStreams = streamsByMember
					.getOrDefault(member.getDatasetMemberSessionId(), List.of())
					.stream()
					.map(stream -> {
						var grant = requireValue(grantsByAsset, stream.getClipAssetId(), "asset grant");
						var revision = requireValue(
								revisionsById, stream.getAnnotationRevisionId(), "annotation revision");
						return new DatasetExportStreamInput(
								stream.getCameraStreamId(),
								stream.getClipAssetId(),
								stream.getCameraIndex(),
								grant.contentType(),
								grant.sizeBytes(),
								grant.sha256(),
								grant.inputUrl(),
								DatasetExportPackagePaths.videoPath(
										member.getCaptureSessionId(),
										stream.getCameraIndex(),
										stream.getClipAssetId(),
										grant.contentType()),
								revision.annotationRevisionId(),
								revision.annotationSchemaVersion(),
								revision.documentSha256(),
								revision.document());
					})
					.toList();
			if (sessionStreams.isEmpty()) {
				throw frozenEvidenceChanged();
			}
			return new DatasetExportSessionInput(
					member.getCaptureSessionId(), assignment.getSplit().name(), sessionStreams);
		}).toList();
		this.attemptRepository.saveAndFlush(attempt);
		this.exportRepository.saveAndFlush(export);
		return new DatasetExportLeaseResponse(
				exportId,
				attemptNumber,
				attempt.getLeaseUntil(),
				this.heartbeatInterval.toSeconds(),
				now.plus(this.inputAuthorizationLifetime),
				OUTPUT_PART_SIZE_BYTES,
				ExportArtifact.FILE_NAME,
				export.getSnapshotDigest(),
				export.getExportProfileKey(),
				export.getExportProfileDigest(),
				annotations.labelSchema(),
				sessions);
	}

	@Transactional
	DatasetExportHeartbeatResponse heartbeat(
			UUID exportId,
			int attemptNumber,
			String leaseToken,
			DatasetExportLeaseRequest request) {
		var now = now();
		var export = lockExport(exportId);
		var attempt = lockAttempt(exportId, attemptNumber);
		requireLease(attempt, leaseToken);
		if (export.getStatus() != DatasetExportStatus.RUNNING) {
			throw new DatasetExportExecutionException(
					"STALE_ATTEMPT", "The dataset export is no longer running.", false);
		}
		attempt.heartbeat(request.workerId(), now, this.leaseDuration);
		return new DatasetExportHeartbeatResponse(
				exportId,
				attemptNumber,
				attempt.getLeaseUntil(),
				this.heartbeatInterval.toSeconds());
	}

	@Transactional
	DatasetExportPartAuthorizationResponse authorizePart(
			UUID exportId,
			int attemptNumber,
			int partNumber,
			String leaseToken,
			DatasetExportLeaseRequest request) {
		var now = now();
		var export = lockExport(exportId);
		var attempt = lockAttempt(exportId, attemptNumber);
		requireLease(attempt, leaseToken);
		if (export.getStatus() != DatasetExportStatus.RUNNING) {
			throw new DatasetExportExecutionException(
					"STALE_ATTEMPT", "The dataset export is no longer running.", false);
		}
		attempt.requireActiveLease(now);
		if (!request.workerId().equals(attempt.getWorkerId())) {
			throw new DatasetExportExecutionException(
					"EXPORT_WORKER_IDENTITY_MISMATCH",
					"The dataset export attempt belongs to another Worker.",
					false);
		}
		var upload = attempt.getUpload();
		if (upload == null) {
			throw new DatasetExportExecutionException(
					"EXPORT_OUTPUT_NOT_INITIALIZED",
					"The dataset export output upload is unavailable.",
					true);
		}
		var expiresAt = now.plus(this.outputPartAuthorizationLifetime);
		return new DatasetExportPartAuthorizationResponse(
				exportId,
				attemptNumber,
				partNumber,
				this.storage.authorizePart(
						upload, partNumber, this.outputPartAuthorizationLifetime),
				expiresAt);
	}

	@Transactional
	DatasetExportResultAcceptedResponse acceptResult(
			UUID exportId,
			int attemptNumber,
			String leaseToken,
			DatasetExportResultRequest request) {
		var now = now();
		var export = lockExport(exportId);
		var attempt = lockAttempt(exportId, attemptNumber);
		requireResultIdentity(export, attempt, exportId, attemptNumber, leaseToken, request, now);
		var fingerprint = request.fingerprint();
		var existing = this.inbox.find(request.messageId());
		if (existing.isPresent()) {
			return requireSameReceipt(existing.get(), request, fingerprint, export.getStatus());
		}
		if (!this.approvedSoftwareDigests.contains(request.softwareDigest())) {
			throw new DatasetExportExecutionException(
					"EXPORT_WORKER_SOFTWARE_UNAPPROVED",
					"The Worker software digest is not approved for dataset exports.",
					false);
		}
		attempt.requireActiveLease(now);
		if (export.getStatus() != DatasetExportStatus.RUNNING) {
			throw new DatasetExportExecutionException(
					"STALE_ATTEMPT", "The dataset export is no longer running.", false);
		}

		if (request.status() == DatasetExportResultStatus.SUCCEEDED) {
			var outputError = validateAndRegisterOutput(export, attempt, request, now);
			if (outputError == null) {
				attempt.succeed(now);
				export.makeAvailable(now);
				var receipt = recordReceipt(request, fingerprint, DatasetExportResultStatus.SUCCEEDED, now);
				recordExecutionAudit(export, attempt, "DATASET_EXPORT_AVAILABLE", "EXPORT_PACKAGE_VERIFIED", now);
				return response(receipt, export.getStatus());
			}
			attempt.fail(outputError, now);
			export.fail(now);
			var receipt = recordReceipt(request, fingerprint, DatasetExportResultStatus.FAILED, now);
			recordExecutionAudit(export, attempt, "DATASET_EXPORT_FAILED", outputError.errorCode().name(), now);
			return response(receipt, export.getStatus());
		}

		var error = request.error();
		attempt.fail(error, now);
		var upload = attempt.getUpload();
		if (upload != null) {
			this.storage.abort(upload);
		}
		if (this.retryPolicy.shouldRetry(error, attemptNumber)) {
			export.waitForRetry();
			var nextAttempt = attemptNumber + 1;
			this.attemptQueue.enqueue(
					export,
					nextAttempt,
					now,
					this.retryPolicy.nextAvailableAt(nextAttempt, now));
			recordExecutionAudit(export, attempt, "DATASET_EXPORT_RETRY_SCHEDULED", error.errorCode().name(), now);
		}
		else {
			export.fail(now);
			recordExecutionAudit(export, attempt, "DATASET_EXPORT_FAILED", error.errorCode().name(), now);
		}
		var receipt = recordReceipt(request, fingerprint, DatasetExportResultStatus.FAILED, now);
		return response(receipt, export.getStatus());
	}

	private DatasetExportErrorPayload validateAndRegisterOutput(
			DatasetExport export,
			DatasetExportAttempt attempt,
			DatasetExportResultRequest request,
			Instant now) {
		var manifestError = validateFileManifest(export, request.files());
		if (manifestError != null) {
			deleteOrAbort(attempt);
			return manifestError;
		}
		var upload = attempt.getUpload();
		if (upload == null) {
			return outputError(
					DatasetExportErrorCode.OUTPUT_UPLOAD_FAILED,
					"The approved output upload is unavailable.");
		}
		var completed = this.storage.findCompletedObject(upload).orElseGet(() -> {
			var parts = this.storage.listParts(upload);
			if (parts.isEmpty()) {
				throw new DatasetExportExecutionException(
						"EXPORT_OUTPUT_MISSING",
						"The Worker did not upload any dataset package parts.",
						true);
			}
			return this.storage.complete(upload, parts);
		});
		var actualDigest = this.storage.calculateSha256(upload);
		if (completed.sizeBytes() != request.packageSizeBytes()
				|| !constantTimeEquals(actualDigest, request.packageSha256())) {
			this.storage.delete(new DatasetExportStoragePort.ObjectReference(
					upload.bucket(), upload.objectKey()));
			return outputError(
					DatasetExportErrorCode.OUTPUT_DIGEST_MISMATCH,
					"The stored dataset package size or SHA-256 does not match the Worker result.");
		}
		this.artifactRepository.saveAndFlush(ExportArtifact.register(
				export,
				attempt,
				completed.sizeBytes(),
				actualDigest,
				upload,
				request.artifactManifest(),
				request.artifactManifestDigest(),
				now));
		return null;
	}

	private DatasetExportErrorPayload validateFileManifest(
			DatasetExport export, List<DatasetExportFileResult> files) {
		var byPath = files.stream().collect(Collectors.toMap(
				DatasetExportFileResult::path,
				Function.identity(),
				(first, second) -> first,
				LinkedHashMap::new));
		if (byPath.size() != files.size()) {
			return outputError(
					DatasetExportErrorCode.CONTRACT_INVALID,
					"The dataset package contains duplicate manifest paths.");
		}
		var streams = this.streamRepository
				.findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(
						export.getDatasetVersionId());
		var members = this.memberRepository
				.findByDatasetVersionIdOrderByCandidateIndexAsc(export.getDatasetVersionId());
		var membersById = uniqueMap(members, DatasetMemberSession::getDatasetMemberSessionId);
		List<ClipAssetExportSummary> assets;
		try {
			assets = this.assetReadService.describe(export.getProjectId(), assetReferences(streams));
		}
		catch (AssetCatalogQueryException exception) {
			return outputError(
					DatasetExportErrorCode.INPUT_DIGEST_MISMATCH,
					"A frozen dataset input changed before output validation.");
		}
		var assetsById = uniqueMap(assets, ClipAssetExportSummary::clipAssetId);
		var expectedPaths = new LinkedHashSet<>(METADATA_PATHS);
		for (var stream : streams) {
			var member = membersById.get(stream.getDatasetMemberSessionId());
			var asset = assetsById.get(stream.getClipAssetId());
			if (member == null || asset == null) {
				return outputError(
						DatasetExportErrorCode.INPUT_DIGEST_MISMATCH,
						"The frozen dataset input graph is incomplete.");
			}
			var path = DatasetExportPackagePaths.videoPath(
					member.getCaptureSessionId(),
					stream.getCameraIndex(),
					stream.getClipAssetId(),
					asset.contentType());
			expectedPaths.add(path);
			var file = byPath.get(path);
			if (file == null
					|| file.sizeBytes() != asset.sizeBytes()
					|| !constantTimeEquals(file.sha256(), stream.getAssetSha256())) {
				return outputError(
						DatasetExportErrorCode.OUTPUT_DIGEST_MISMATCH,
						"A packaged raw video does not match the frozen input.");
			}
		}
		if (!expectedPaths.equals(byPath.keySet())) {
			return outputError(
					DatasetExportErrorCode.CONTRACT_INVALID,
					"The dataset package file set does not match the approved v2 profile.");
		}
		for (var required : List.of(
				"README.md", "label_schema.json", "manifest.jsonl",
				"action_segments.jsonl", "SHA256SUMS")) {
			if (byPath.get(required).sizeBytes() < 1) {
				return outputError(
						DatasetExportErrorCode.CONTRACT_INVALID,
						"A required dataset package metadata file is empty.");
			}
		}
		return null;
	}

	private void deleteOrAbort(DatasetExportAttempt attempt) {
		var upload = attempt.getUpload();
		if (upload == null) {
			return;
		}
		if (this.storage.findCompletedObject(upload).isPresent()) {
			this.storage.delete(new DatasetExportStoragePort.ObjectReference(
					upload.bucket(), upload.objectKey()));
		}
		else {
			this.storage.abort(upload);
		}
	}

	private DatasetExportInboxReceipt recordReceipt(
			DatasetExportResultRequest request,
			String fingerprint,
			DatasetExportResultStatus status,
			Instant now) {
		return this.inbox.record(
				request.messageId(),
				request.exportId(),
				request.attempt(),
				fingerprint,
				status.name(),
				now);
	}

	private void requireResultIdentity(
			DatasetExport export,
			DatasetExportAttempt attempt,
			UUID exportId,
			int attemptNumber,
			String leaseToken,
			DatasetExportResultRequest request,
			Instant now) {
		if (!DatasetExportResultRequest.SCHEMA_VERSION.equals(request.schemaVersion())
				|| !exportId.equals(request.exportId())
				|| attemptNumber != request.attempt()
				|| !attempt.getMessageId().equals(request.messageId())) {
			throw new DatasetExportExecutionException(
					"EXPORT_RESULT_IDENTITY_MISMATCH",
					"The result does not identify the current dataset export attempt.",
					false);
		}
		if (!constantTimeEquals(leaseToken, request.leaseToken())) {
			throw new DatasetExportExecutionException(
					"EXPORT_LEASE_INVALID", "The dataset export lease token is invalid.", false);
		}
		requireLease(attempt, leaseToken);
		if (!constantTimeEquals(export.getSnapshotDigest(), request.snapshotDigest())
				|| !constantTimeEquals(
						export.getExportProfileDigest(), request.exportProfileDigest())) {
			throw new DatasetExportExecutionException(
					"EXPORT_RESULT_DIGEST_MISMATCH",
					"The result snapshot or profile digest does not match the export.",
					false);
		}
		if (request.startedAt().isBefore(attempt.getStartedAt().minus(this.resultClockSkew))
				|| request.completedAt().isBefore(request.startedAt())
				|| request.completedAt().isAfter(now.plus(this.resultClockSkew))) {
			throw new DatasetExportExecutionException(
					"EXPORT_RESULT_TIME_INVALID",
					"The Worker result timestamps exceed the approved bounds.",
					false);
		}
	}

	private static DatasetExportResultAcceptedResponse requireSameReceipt(
			DatasetExportInboxReceipt receipt,
			DatasetExportResultRequest request,
			String fingerprint,
			DatasetExportStatus exportStatus) {
		if (!receipt.exportId().equals(request.exportId())
				|| receipt.attempt() != request.attempt()
				|| !receipt.resultFingerprint().equals(fingerprint)) {
			throw new DatasetExportExecutionException(
					"INBOX_MESSAGE_REUSED",
					"The result message identifier was reused with different content.",
					false);
		}
		return response(receipt, exportStatus);
	}

	private static DatasetExportResultAcceptedResponse response(
			DatasetExportInboxReceipt receipt, DatasetExportStatus exportStatus) {
		return new DatasetExportResultAcceptedResponse(
				receipt.messageId(),
				receipt.exportId(),
				receipt.attempt(),
				DatasetExportResultStatus.valueOf(receipt.resultStatus()),
				exportStatus,
				receipt.processedAt());
	}

	private void recordExecutionAudit(
			DatasetExport export,
			DatasetExportAttempt attempt,
			String action,
			String reason,
			Instant now) {
		this.auditRecorder.record(new AuditEventCommand(
				now,
				AuditActorType.SYSTEM,
				"dataset-export-worker",
				export.getProjectId(),
				action,
				"DatasetExport",
				export.getDatasetExportId().toString(),
				Long.toString(export.getVersion()),
				AuditOutcome.SUCCEEDED,
				reason,
				attempt.getMessageId().toString(),
				null,
				"EXPORT_ATTEMPT:" + attempt.getDatasetExportAttemptId(),
				Map.of(
						"attempt", Integer.toString(attempt.getAttempt()),
						"snapshot_digest", export.getSnapshotDigest(),
						"export_profile_digest", export.getExportProfileDigest())));
	}

	private DatasetExport lockExport(UUID exportId) {
		return this.exportRepository.findForUpdate(exportId)
				.orElseThrow(() -> new DatasetExportExecutionException(
						"DATASET_EXPORT_NOT_FOUND", "The dataset export was not found.", false));
	}

	private DatasetExportAttempt lockAttempt(UUID exportId, int attemptNumber) {
		return this.attemptRepository.findForUpdate(exportId, attemptNumber)
				.orElseThrow(() -> new DatasetExportExecutionException(
						"STALE_ATTEMPT", "The dataset export attempt was not found.", false));
	}

	private static void requireLease(DatasetExportAttempt attempt, String leaseToken) {
		if (!attempt.matchesLeaseHash(DatasetExportLeaseCapabilityFactory.hash(leaseToken))) {
			throw new DatasetExportExecutionException(
					"EXPORT_LEASE_INVALID", "The dataset export lease token is invalid.", false);
		}
	}

	private static List<ClipAssetExportReference> assetReferences(
			List<DatasetMemberStream> streams) {
		return streams.stream()
				.map(stream -> new ClipAssetExportReference(
						stream.getClipAssetId(), stream.getAssetSha256()))
				.toList();
	}

	private static List<AnnotationExportReference> annotationReferences(
			List<DatasetMemberStream> streams) {
		return streams.stream()
				.map(stream -> new AnnotationExportReference(
						stream.getAnnotationRevisionId(),
						stream.getClipAssetId(),
						stream.getAnnotationDocumentSha256()))
				.toList();
	}

	private void registerAbortOnRollback(DatasetExportStoragePort.CreatedUpload upload) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != TransactionSynchronization.STATUS_COMMITTED) {
					try {
						storage.abort(upload);
					}
					catch (RuntimeException ignored) {
						// The unregistered upload remains invisible and is handled by storage lifecycle cleanup.
					}
				}
			}
		});
	}

	private static String outputObjectKey(
			DatasetExport export, DatasetExportAttempt attempt) {
		return "dataset-exports/" + export.getProjectId()
				+ "/" + export.getDatasetExportId()
				+ "/attempt-" + attempt.getAttempt()
				+ "/" + ExportArtifact.FILE_NAME;
	}

	private static DatasetExportErrorPayload outputError(
			DatasetExportErrorCode code, String safeMessage) {
		return new DatasetExportErrorPayload(
				DatasetExportErrorClass.NON_RETRYABLE_INPUT,
				code,
				safeMessage,
				false);
	}

	private static DatasetExportExecutionException frozenEvidenceChanged() {
		return new DatasetExportExecutionException(
				"EXPORT_FROZEN_EVIDENCE_INVALID",
				"The frozen dataset export evidence is incomplete or changed.",
				false);
	}

	private static <K, V> Map<K, V> uniqueMap(
			List<V> values, Function<V, K> key) {
		try {
			return values.stream().collect(Collectors.toMap(
					key, Function.identity(), (first, second) -> {
						throw frozenEvidenceChanged();
					}, LinkedHashMap::new));
		}
		catch (IllegalStateException exception) {
			throw frozenEvidenceChanged();
		}
	}

	private static <K, V> V requireValue(Map<K, V> values, K key, String label) {
		var value = values.get(key);
		if (value == null) {
			throw new DatasetExportExecutionException(
					"EXPORT_FROZEN_EVIDENCE_INVALID",
					"The frozen dataset " + label + " is unavailable.",
					false);
		}
		return value;
	}

	private static Set<String> parseApprovedDigests(String configured) {
		return Arrays.stream(configured.split(","))
				.map(String::trim)
				.filter(value -> !value.isEmpty())
				.peek(value -> {
					if (!value.matches("^sha256:[a-f0-9]{64}$")) {
						throw new IllegalArgumentException(
								"Approved Export Worker digests must use sha256:<64 hex>.");
					}
				})
				.collect(Collectors.toUnmodifiableSet());
	}

	private static Duration requirePositive(Duration value, String label) {
		if (value.isNegative() || value.isZero()) {
			throw new IllegalArgumentException(label + " must be positive.");
		}
		return value;
	}

	private static Duration requireHeartbeatInterval(Duration value, Duration leaseDuration) {
		requirePositive(value, "Dataset export heartbeat interval");
		if (value.toSeconds() < 1 || value.compareTo(leaseDuration) >= 0) {
			throw new IllegalArgumentException(
					"Dataset export heartbeat interval must be shorter than the lease.");
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
