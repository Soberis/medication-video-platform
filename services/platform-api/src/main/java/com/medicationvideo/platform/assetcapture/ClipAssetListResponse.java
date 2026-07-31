package com.medicationvideo.platform.assetcapture;

import java.util.List;

record ClipAssetListResponse(List<ClipAssetReadResponse> items, String nextCursor) {
}
