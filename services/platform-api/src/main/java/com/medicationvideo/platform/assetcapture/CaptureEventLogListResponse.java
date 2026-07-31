package com.medicationvideo.platform.assetcapture;

import java.util.List;

record CaptureEventLogListResponse(List<CaptureEventLogReadResponse> items, String nextCursor) {
}
