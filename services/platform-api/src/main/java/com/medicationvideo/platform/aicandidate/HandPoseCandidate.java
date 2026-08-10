package com.medicationvideo.platform.aicandidate;

import tools.jackson.databind.JsonNode;

record HandPoseCandidate(long frameTimestampMs, int imageWidth, int imageHeight, int handIndex, String handedness,
		double handednessScore, JsonNode boundingBox, JsonNode landmarks, int validLandmarkCount) {
}
