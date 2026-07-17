package com.medicationvideo.platform.iamproject;

import java.util.List;

record ProjectMembershipListResponse(List<ProjectMembershipResponse> items) {

	ProjectMembershipListResponse {
		items = List.copyOf(items);
	}
}
