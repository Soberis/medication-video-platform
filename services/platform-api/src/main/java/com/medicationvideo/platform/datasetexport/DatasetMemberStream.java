package com.medicationvideo.platform.datasetexport;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "dataset_member_stream")
class DatasetMemberStream {

	@Id
	@Column(name = "dataset_member_stream_id", nullable = false, updatable = false)
	private UUID datasetMemberStreamId;

	@Column(name = "dataset_member_session_id", nullable = false, updatable = false)
	private UUID datasetMemberSessionId;

	@Column(name = "dataset_version_id", nullable = false, updatable = false)
	private UUID datasetVersionId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "camera_stream_id", nullable = false, updatable = false)
	private UUID cameraStreamId;

	@Column(name = "clip_asset_id", nullable = false, updatable = false)
	private UUID clipAssetId;

	@Column(name = "camera_index", nullable = false, updatable = false)
	private int cameraIndex;

	@Column(name = "asset_sha256", nullable = false, updatable = false, length = 64)
	private String assetSha256;

	@Column(name = "quality_review_decision", nullable = false, updatable = false, length = 32)
	private String qualityReviewDecision;

	@Column(name = "quality_review_version", nullable = false, updatable = false)
	private long qualityReviewVersion;

	@Column(name = "annotation_revision_id", nullable = false, updatable = false)
	private UUID annotationRevisionId;

	@Column(name = "annotation_document_sha256", nullable = false, updatable = false, length = 64)
	private String annotationDocumentSha256;

	@Column(name = "label_schema_digest", nullable = false, updatable = false, length = 64)
	private String labelSchemaDigest;

	@Column(name = "cleaning_result_id", nullable = false, updatable = false)
	private UUID cleaningResultId;

	@Column(name = "cleaning_run_id", nullable = false, updatable = false)
	private UUID cleaningRunId;

	@Column(name = "cleaning_rule_set_digest", nullable = false, updatable = false, length = 64)
	private String cleaningRuleSetDigest;

	@Column(name = "post_cleaning_outcome", nullable = false, updatable = false, length = 16)
	private String postCleaningOutcome;

	protected DatasetMemberStream() {
	}

	private DatasetMemberStream(DatasetVersion version, DatasetMemberSession session,
			DatasetAdmissionStreamEvidence evidence) {
		this.datasetMemberStreamId = UUID.randomUUID();
		this.datasetMemberSessionId = session.getDatasetMemberSessionId();
		this.datasetVersionId = version.getDatasetVersionId();
		this.projectId = version.getProjectId();
		this.cameraStreamId = evidence.cameraStreamId();
		this.clipAssetId = evidence.clipAssetId();
		this.cameraIndex = evidence.cameraIndex();
		this.assetSha256 = evidence.assetSha256();
		this.qualityReviewDecision = evidence.qualityReviewDecision();
		this.qualityReviewVersion = evidence.qualityReviewVersion();
		this.annotationRevisionId = evidence.annotationRevisionId();
		this.annotationDocumentSha256 = evidence.annotationDocumentSha256();
		this.labelSchemaDigest = evidence.labelSchemaDigest();
		this.cleaningResultId = evidence.cleaningResultId();
		this.cleaningRunId = evidence.cleaningRunId();
		this.cleaningRuleSetDigest = evidence.cleaningRuleSetDigest();
		this.postCleaningOutcome = evidence.postCleaningOutcome();
	}

	static DatasetMemberStream snapshot(DatasetVersion version, DatasetMemberSession session,
			DatasetAdmissionStreamEvidence evidence) {
		return new DatasetMemberStream(version, session, evidence);
	}

	UUID getDatasetMemberSessionId() {
		return this.datasetMemberSessionId;
	}

	UUID getCameraStreamId() {
		return this.cameraStreamId;
	}

	UUID getClipAssetId() {
		return this.clipAssetId;
	}

	int getCameraIndex() {
		return this.cameraIndex;
	}

	String getAssetSha256() {
		return this.assetSha256;
	}

	String getQualityReviewDecision() {
		return this.qualityReviewDecision;
	}

	long getQualityReviewVersion() {
		return this.qualityReviewVersion;
	}

	UUID getAnnotationRevisionId() {
		return this.annotationRevisionId;
	}

	String getAnnotationDocumentSha256() {
		return this.annotationDocumentSha256;
	}

	String getLabelSchemaDigest() {
		return this.labelSchemaDigest;
	}

	UUID getCleaningResultId() {
		return this.cleaningResultId;
	}

	UUID getCleaningRunId() {
		return this.cleaningRunId;
	}

	String getCleaningRuleSetDigest() {
		return this.cleaningRuleSetDigest;
	}

	String getPostCleaningOutcome() {
		return this.postCleaningOutcome;
	}

}
