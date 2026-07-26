package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PostCleaningResultSummary(
		UUID cleaningResultId,
		UUID cleaningRunId,
		String ruleSetKey,
		String ruleSetVersion,
		String ruleSetDigest,
		String outcome,
		List<String> reasonCodes,
		List<String> executedChecks,
		Instant evaluatedAt) {

	static PostCleaningResultSummary from(CleaningResult result) {
		var run = result.getCleaningRun();
		var ruleSet = run.getRuleSet();
		return new PostCleaningResultSummary(
				result.getCleaningResultId(),
				run.getCleaningRunId(),
				ruleSet.getRuleSetKey(),
				ruleSet.getRuleSetVersion(),
				ruleSet.getRuleSetDigest(),
				result.getOutcome().name(),
				result.getReasonCodes(),
				result.getExecutedChecks(),
				result.getEvaluatedAt());
	}
}
