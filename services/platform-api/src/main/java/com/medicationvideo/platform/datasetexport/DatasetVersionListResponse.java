package com.medicationvideo.platform.datasetexport;

import java.util.List;

record DatasetVersionListResponse(
		List<DatasetVersionResponse> items,
		String nextCursor) {

	DatasetVersionListResponse {
		items = List.copyOf(items);
	}
}
