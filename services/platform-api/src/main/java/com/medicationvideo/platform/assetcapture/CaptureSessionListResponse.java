package com.medicationvideo.platform.assetcapture;

import java.util.List;

record CaptureSessionListResponse(List<CaptureSessionListItemResponse> items, String nextCursor) {
}
