package com.medicationvideo.platform.datasetexport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
class DatasetSplitPlanner {

	private final SecureRandom secureRandom = new SecureRandom();

	DatasetSplitPlanningResult plan(List<DatasetMemberSession> members, int holdoutCount) {
		return plan(members, holdoutCount, this.secureRandom.nextInt(1, Integer.MAX_VALUE));
	}

	static DatasetSplitPlanningResult plan(
			List<DatasetMemberSession> members, int holdoutCount, int splitSeed) {
		var ranked = members.stream()
				.map(member -> new RankedDatasetMember(member, rankKey(member, splitSeed)))
				.sorted(Comparator.comparing(RankedDatasetMember::rankKey)
						.thenComparing(item -> item.member().getCaptureSessionId()))
				.toList();
		var assignments = new ArrayList<PlannedDatasetSplit>(ranked.size());
		for (int rank = 0; rank < ranked.size(); rank++) {
			assignments.add(new PlannedDatasetSplit(
					ranked.get(rank).member(),
					rank < holdoutCount ? DatasetSplit.HOLDOUT : DatasetSplit.PILOT,
					rank));
		}
		return new DatasetSplitPlanningResult(splitSeed, assignments);
	}

	private static String rankKey(DatasetMemberSession member, int splitSeed) {
		var canonical = DatasetSplitPolicy.CAPTURE_SESSION_HASH_RANK_V1.name()
				+ "\n" + splitSeed
				+ "\n" + member.getCaptureSessionId();
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available.", exception);
		}
	}
}

record DatasetSplitPlanningResult(int splitSeed, List<PlannedDatasetSplit> assignments) {
	DatasetSplitPlanningResult {
		assignments = List.copyOf(assignments);
	}
}

record PlannedDatasetSplit(DatasetMemberSession member, DatasetSplit split, int assignmentRank) {
}

record RankedDatasetMember(DatasetMemberSession member, String rankKey) {
}
