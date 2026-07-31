package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "cleaning_rule_set")
class CleaningRuleSet {

	@Id
	@Column(name = "cleaning_rule_set_id", nullable = false, updatable = false)
	private UUID cleaningRuleSetId;

	@Column(name = "rule_set_key", nullable = false, updatable = false, length = 64)
	private String ruleSetKey;

	@Column(name = "rule_set_version", nullable = false, updatable = false, length = 64)
	private String ruleSetVersion;

	@Column(name = "rule_set_digest", nullable = false, updatable = false, length = 64)
	private String ruleSetDigest;

	@Enumerated(EnumType.STRING)
	@Column(name = "cleaning_stage", nullable = false, updatable = false, length = 32)
	private CleaningStage cleaningStage;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, updatable = false, length = 16)
	private CleaningRuleSetStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected CleaningRuleSet() {
	}

	UUID getCleaningRuleSetId() {
		return this.cleaningRuleSetId;
	}

	String getRuleSetKey() {
		return this.ruleSetKey;
	}

	String getRuleSetVersion() {
		return this.ruleSetVersion;
	}

	String getRuleSetDigest() {
		return this.ruleSetDigest;
	}

}

enum CleaningStage {

	POST_ANNOTATION

}

enum CleaningRuleSetStatus {

	ACTIVE, RETIRED

}
