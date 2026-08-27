package com.medicationvideo.platform.assetcapture;

import java.util.List;

record CaptureGroupListResponse(List<CaptureGroupSummary> items, String nextCursor) {

	CaptureGroupListResponse {
		items = List.copyOf(items);
	}

}
