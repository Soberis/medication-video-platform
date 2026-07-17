package com.medicationvideo.platform.assetcapture;

import java.util.List;

record AssetFolderListResponse(List<AssetFolderResponse> items) {

	AssetFolderListResponse {
		items = List.copyOf(items);
	}
}
