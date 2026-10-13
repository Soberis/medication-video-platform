package com.medicationvideo.platform.aicandidate;

import java.util.UUID;

/**
 * 动作分割候选作业完成领域事件 (V1.36).
 */
public record ActionCandidateJobCompletedEvent(UUID projectId, UUID captureGroupId, UUID clipAssetId) {
}
