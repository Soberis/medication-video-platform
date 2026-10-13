package com.medicationvideo.platform.aicandidate;

import java.util.UUID;

/**
 * 手部骨骼检测作业完成领域事件 (V1.36).
 */
public record HandPoseJobCompletedEvent(UUID projectId, UUID captureGroupId, UUID clipAssetId) {
}
