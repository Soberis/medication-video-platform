package com.medicationvideo.platform.datasetexport;

import java.util.UUID;

import com.medicationvideo.platform.assetcapture.CaptureSessionDatasetAdmissionFacts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "dataset_member_session")
class DatasetMemberSession {

	@Id
	@Column(name = "dataset_member_session_id", nullable = false, updatable = false)
	private UUID datasetMemberSessionId;

	@Column(name = "dataset_version_id", nullable = false, updatable = false)
	private UUID datasetVersionId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "capture_session_id", nullable = false, updatable = false)
	private UUID captureSessionId;

	@Column(name = "candidate_index", nullable = false, updatable = false)
	private int candidateIndex;

	@Column(name = "expected_camera_count", nullable = false, updatable = false)
	private int expectedCameraCount;

	@Column(name = "received_camera_count", nullable = false, updatable = false)
	private int receivedCameraCount;

	@Column(name = "assembly_status", nullable = false, updatable = false, length = 32)
	private String assemblyStatus;

	@Column(name = "quality_status", nullable = false, updatable = false, length = 32)
	private String qualityStatus;

	protected DatasetMemberSession() {
	}

	private DatasetMemberSession(DatasetVersion version, int candidateIndex,
			CaptureSessionDatasetAdmissionFacts facts) {
		this.datasetMemberSessionId = UUID.randomUUID();
		this.datasetVersionId = version.getDatasetVersionId();
		this.projectId = version.getProjectId();
		this.captureSessionId = facts.captureSessionId();
		this.candidateIndex = candidateIndex;
		this.expectedCameraCount = facts.expectedCameraCount();
		this.receivedCameraCount = facts.receivedCameraCount();
		this.assemblyStatus = facts.assemblyStatus();
		this.qualityStatus = facts.qualityStatus();
	}

	static DatasetMemberSession snapshot(DatasetVersion version, int candidateIndex,
			CaptureSessionDatasetAdmissionFacts facts) {
		return new DatasetMemberSession(version, candidateIndex, facts);
	}

	UUID getDatasetMemberSessionId() {
		return this.datasetMemberSessionId;
	}

	UUID getDatasetVersionId() {
		return this.datasetVersionId;
	}

	UUID getCaptureSessionId() {
		return this.captureSessionId;
	}

	int getCandidateIndex() {
		return this.candidateIndex;
	}

	int getExpectedCameraCount() {
		return this.expectedCameraCount;
	}

	int getReceivedCameraCount() {
		return this.receivedCameraCount;
	}

	String getAssemblyStatus() {
		return this.assemblyStatus;
	}

	String getQualityStatus() {
		return this.qualityStatus;
	}

}
