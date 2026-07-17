package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService;
import com.medicationvideo.platform.assetcapture.ClipAssetStatus;
import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;

@Service
public class QuarantineCommandService {

	private final QuarantineRecordRepository repository;
	private final AssetCatalogQueryService assetCatalog;
	private final AuditEventRecorder auditRecorder;

	QuarantineCommandService(
			QuarantineRecordRepository repository,
			AssetCatalogQueryService assetCatalog,
			AuditEventRecorder auditRecorder) {
		this.repository = repository;
		this.assetCatalog = assetCatalog;
		this.auditRecorder = auditRecorder;
	}

	@Transactional
	public void activate(QuarantineActivationCommand command) {
		var existing = this.repository.findByProjectIdAndSourceTypeAndSourceId(
				command.projectId(), command.sourceType(), command.sourceId());
		if (existing.isPresent()) {
			if (!existing.get().matches(command)) {
				throw new IllegalStateException(
						"A quarantine source cannot be rebound to another asset or reason.");
			}
			return;
		}

		var asset = this.assetCatalog.getClipAssetInputSummary(
				command.projectId(), command.clipAssetId());
		if (asset.status() != ClipAssetStatus.INGESTED
				|| !asset.inputDigest().equals(command.inputDigest())) {
			throw new IllegalStateException(
					"Quarantine activation must bind the current ingested clip asset digest.");
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var record = this.repository.saveAndFlush(QuarantineRecord.activate(command, now));
		recordAudit(record, command.requestId(), command.traceId());
	}

	private void recordAudit(QuarantineRecord record, String requestId, String traceId) {
		this.auditRecorder.record(new AuditEventCommand(
				record.getActivatedAt(),
				AuditActorType.USER,
				record.getActivatedBy(),
				record.getProjectId(),
				"QUARANTINE_ACTIVATED",
				"QuarantineRecord",
				record.getQuarantineRecordId().toString(),
				"1",
				AuditOutcome.SUCCEEDED,
				record.getReasonCode().name(),
				requestId,
				traceId,
				"ROLE_ADMIN",
				Map.of(
						"clip_asset_id", record.getClipAssetId().toString(),
						"input_digest", record.getInputDigest(),
						"source_type", record.getSourceType().name(),
						"source_id", record.getSourceId().toString(),
						"source_version", Integer.toString(record.getSourceVersion()),
						"status", record.getStatus().name())));
	}
}
