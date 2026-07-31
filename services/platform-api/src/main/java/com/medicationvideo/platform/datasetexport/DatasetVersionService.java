package com.medicationvideo.platform.datasetexport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

/**
 * Creates a DRAFT candidate set. It intentionally does not freeze, split or export a
 * dataset.
 */
@Service
class DatasetVersionService {

	private final ProjectAccessService projectAccessService;

	private final DatasetAdmissionPreviewService admissionService;

	private final DatasetVersionRepository versionRepository;

	private final DatasetMemberSessionRepository memberSessionRepository;

	private final DatasetMemberStreamRepository memberStreamRepository;

	private final AuditEventRecorder auditRecorder;

	DatasetVersionService(ProjectAccessService projectAccessService, DatasetAdmissionPreviewService admissionService,
			DatasetVersionRepository versionRepository, DatasetMemberSessionRepository memberSessionRepository,
			DatasetMemberStreamRepository memberStreamRepository, AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.admissionService = admissionService;
		this.versionRepository = versionRepository;
		this.memberSessionRepository = memberSessionRepository;
		this.memberStreamRepository = memberStreamRepository;
		this.auditRecorder = auditRecorder;
	}

	@Transactional
	DatasetVersionResponse createDraft(UUID projectId, String actorId, String idempotencyKey, String requestId,
			String traceId, DatasetVersionCreateCommand command) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var captureSessionIds = distinctSessionIds(command.captureSessionIds());
		var fingerprint = requestFingerprint(command.displayName(), captureSessionIds);
		var replay = this.versionRepository.findByProjectIdAndCreatedByAndIdempotencyKey(projectId, actorId,
				idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.get().matchesCreateRequest(fingerprint)) {
				throw new DatasetVersionException("DATASET_VERSION_IDEMPOTENCY_KEY_REUSED",
						"The idempotency key was already used for a different dataset draft.", 409);
			}
			return DatasetVersionResponse.from(replay.get());
		}

		var evaluations = new ArrayList<DatasetAdmissionEvaluation>();
		for (var captureSessionId : captureSessionIds) {
			var evaluation = this.admissionService.evaluate(projectId, captureSessionId);
			if (!evaluation.preview().eligible()) {
				throw new DatasetVersionException("DATASET_VERSION_SESSION_NOT_ELIGIBLE",
						"Every capture session must satisfy the current dataset-admission prerequisites.", 409,
						Map.of("capture_session_id", captureSessionId.toString()));
			}
			evaluations.add(evaluation);
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var version = this.versionRepository.saveAndFlush(
				DatasetVersion.create(projectId, command.displayName(), actorId, idempotencyKey, fingerprint, now));
		var members = new ArrayList<DatasetMemberSession>();
		var streams = new ArrayList<DatasetMemberStream>();
		for (int index = 0; index < evaluations.size(); index++) {
			var evaluation = evaluations.get(index);
			var member = DatasetMemberSession.snapshot(version, index, evaluation.captureSession());
			members.add(member);
			for (var evidence : evaluation.streamEvidence()) {
				streams.add(DatasetMemberStream.snapshot(version, member, evidence));
			}
		}
		this.memberSessionRepository.saveAll(members);
		this.memberStreamRepository.saveAll(streams);
		this.auditRecorder.record(new AuditEventCommand(now, AuditActorType.USER, actorId, projectId,
				"DATASET_VERSION_DRAFT_CREATED", "DatasetVersion", version.getDatasetVersionId().toString(),
				Long.toString(version.getVersion()), AuditOutcome.SUCCEEDED, "CURRENT_ADMISSION_ELIGIBLE", requestId,
				traceId, "PROJECT_MEMBERSHIP_ADMIN", Map.of("capture_session_count", Integer.toString(members.size()),
						"stream_count", Integer.toString(streams.size()), "request_fingerprint", fingerprint)));
		return DatasetVersionResponse.from(version);
	}

	private static List<UUID> distinctSessionIds(List<UUID> captureSessionIds) {
		var distinct = new LinkedHashSet<>(captureSessionIds);
		if (distinct.size() != captureSessionIds.size()) {
			throw new DatasetVersionException("DATASET_VERSION_CAPTURE_SESSION_DUPLICATE",
					"A capture session may be selected only once in a dataset draft.", 400);
		}
		return List.copyOf(distinct);
	}

	private static String requestFingerprint(String displayName, List<UUID> captureSessionIds) {
		var canonical = displayName + "\n"
				+ captureSessionIds.stream()
					.map(UUID::toString)
					.reduce((left, right) -> left + "\n" + right)
					.orElseThrow();
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available.", exception);
		}
	}

}

record DatasetVersionCreateCommand(String displayName, List<UUID> captureSessionIds) {
	DatasetVersionCreateCommand {
		captureSessionIds = List.copyOf(captureSessionIds);
	}
}
