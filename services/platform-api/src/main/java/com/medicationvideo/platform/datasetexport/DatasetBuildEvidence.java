package com.medicationvideo.platform.datasetexport;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

record DatasetBuildEvidence(
		String uniformLabelSchemaDigest,
		String uniformCleaningRuleSetDigest) {

	static DatasetBuildEvidence from(List<DatasetMemberStream> streams) {
		return new DatasetBuildEvidence(
				onlyDistinct(streams, DatasetMemberStream::getLabelSchemaDigest),
				onlyDistinct(streams, DatasetMemberStream::getCleaningRuleSetDigest));
	}

	boolean isComplete() {
		return this.uniformLabelSchemaDigest != null && this.uniformCleaningRuleSetDigest != null;
	}

	private static String onlyDistinct(
			List<DatasetMemberStream> streams, Function<DatasetMemberStream, String> value) {
		var values = new LinkedHashSet<String>();
		for (var stream : streams) {
			values.add(value.apply(stream));
			if (values.size() > 1) {
				return null;
			}
		}
		return values.size() == 1 ? values.getFirst() : null;
	}
}
