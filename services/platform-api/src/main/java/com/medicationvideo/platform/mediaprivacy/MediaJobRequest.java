package com.medicationvideo.platform.mediaprivacy;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

record MediaJobRequest(
		@NotNull UUID clipAssetId,
		@NotNull MediaJobType jobType,
		@Pattern(regexp = "^[a-f0-9]{64}$", message = "recipe_digest must be a 64-character lowercase hex SHA-256.")
		String recipeDigest) {
}
