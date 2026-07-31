package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "cleaning_result")
class CleaningResult {

	@Id
	@Column(name = "cleaning_result_id", nullable = false, updatable = false)
	private UUID cleaningResultId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "cleaning_run_id", nullable = false, updatable = false)
	private UUID cleaningRunId;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "cleaning_run_id", referencedColumnName = "cleaning_run_id", insertable = false,
			updatable = false)
	private CleaningRun cleaningRun;

	@Column(name = "annotation_revision_id", nullable = false, updatable = false)
	private UUID annotationRevisionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "outcome", nullable = false, updatable = false, length = 16)
	private CleaningOutcome outcome;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "reason_codes", nullable = false, updatable = false, columnDefinition = "jsonb")
	private List<String> reasonCodes;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "executed_checks", nullable = false, updatable = false, columnDefinition = "jsonb")
	private List<String> executedChecks;

	@Column(name = "evaluated_at", nullable = false, updatable = false)
	private Instant evaluatedAt;

	protected CleaningResult() {
	}

	private CleaningResult(CleaningRun run, CleaningOutcome outcome, List<String> reasonCodes, Instant evaluatedAt) {
		this.cleaningResultId = UUID.randomUUID();
		this.projectId = run.getProjectId();
		this.cleaningRunId = run.getCleaningRunId();
		this.cleaningRun = run;
		this.annotationRevisionId = run.getAnnotationRevisionId();
		this.outcome = outcome;
		this.reasonCodes = List.copyOf(reasonCodes);
		this.executedChecks = PostCleaningProfile.EXECUTED_CHECKS;
		this.evaluatedAt = evaluatedAt;
	}

	static CleaningResult record(CleaningRun run, CleaningOutcome outcome, List<String> reasonCodes,
			Instant evaluatedAt) {
		return new CleaningResult(run, outcome, reasonCodes, evaluatedAt);
	}

	UUID getCleaningResultId() {
		return this.cleaningResultId;
	}

	UUID getAnnotationRevisionId() {
		return this.annotationRevisionId;
	}

	CleaningRun getCleaningRun() {
		return this.cleaningRun;
	}

	CleaningOutcome getOutcome() {
		return this.outcome;
	}

	List<String> getReasonCodes() {
		return this.reasonCodes;
	}

	List<String> getExecutedChecks() {
		return this.executedChecks;
	}

	Instant getEvaluatedAt() {
		return this.evaluatedAt;
	}

}

enum CleaningOutcome {

	PASS, WARNING, REJECT

}
