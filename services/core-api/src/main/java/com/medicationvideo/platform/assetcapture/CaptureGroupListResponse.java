package com.medicationvideo.platform.assetcapture;

import java.util.List;

record CaptureGroupListResponse(List<CaptureSessionResponse> items) {

	CaptureGroupListResponse {
		items = List.copyOf(items);
	}

}
