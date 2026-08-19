package com.medicationvideo.platform.iamproject;

import java.util.List;

record PlatformAccountListResponse(List<PlatformAccountResponse> items) {

	PlatformAccountListResponse {
		items = List.copyOf(items);
	}
}
