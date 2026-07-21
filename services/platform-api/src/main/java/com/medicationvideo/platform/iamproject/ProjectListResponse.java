package com.medicationvideo.platform.iamproject;

import java.util.List;

record ProjectListResponse(List<ProjectResponse> items, boolean canCreateProjects) {

	ProjectListResponse {
		items = List.copyOf(items);
	}
}
