package com.medicationvideo.platform.mediaprivacy;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

record MediaJobRequest(@NotNull UUID clipAssetId, @NotNull MediaJobType jobType) {
}
