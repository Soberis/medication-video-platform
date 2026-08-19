package com.medicationvideo.platform.assetcapture;

import java.util.List;

public record VideoSummaryPage(List<VideoSummary> items, String nextCursor) {
}
