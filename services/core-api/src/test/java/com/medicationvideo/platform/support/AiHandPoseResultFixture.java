package com.medicationvideo.platform.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Deterministic complete-result fixture for the MediaPipe hand-pose result contract. The
 * candidate coordinates intentionally preserve the Worker representation: x/y are
 * normalized image coordinates, z is relative landmark depth, and neither is a pixel or
 * world-landmark coordinate. Frame timestamps are container PTS values in milliseconds.
 */
public final class AiHandPoseResultFixture {

	public static final int IMAGE_WIDTH = 1280;

	public static final int IMAGE_HEIGHT = 720;

	public static final String PROFILE_SHA256 = "3a3be8035ec8a9756a32f43b007a4bb2cc2e2c0468d744d248f19c692fd90626";

	private static final List<Observation> OBSERVATIONS = List.of(new Observation(100, 0, "RIGHT", 0.91),
			new Observation(200, 0, "LEFT", 0.92), new Observation(300, 0, "LEFT", 0.93),
			new Observation(300, 1, "RIGHT", 0.94));

	private AiHandPoseResultFixture() {
	}

	public static List<Observation> observations() {
		return OBSERVATIONS;
	}

	/**
	 * Returns a complete SUCCEEDED/HAND_DETECTED v2 envelope with four candidates across
	 * three sampled frame timestamps.
	 */
	public static String handDetectedResult(UUID messageId, UUID jobId, int attempt, String leaseToken,
			String inputSha256, String modelSha256, Instant startedAt, Instant completedAt) {
		return """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":%d,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"SUCCEEDED","analysis_complete":true,
				"hand_result":"HAND_DETECTED","total_video_frames":3,"inference_frame_count":3,
				"frames_with_hand":3,"frames_without_hand":0,"left_hand_detection_count":2,
				"right_hand_detection_count":2,"invalid_landmark_count":0,"processing_duration_ms":17,
				"failure_reason":null,"candidates":[%s]}
				""".formatted(messageId, jobId, attempt, leaseToken, inputSha256, modelSha256, PROFILE_SHA256,
				startedAt, completedAt, candidatesJson());
	}

	/**
	 * Returns a retryable FAILED v2 envelope with no candidate rows. A failed run remains
	 * distinct from a successful explicit NO_HAND_DETECTED result.
	 */
	public static String failedResult(UUID messageId, UUID jobId, int attempt, String leaseToken, String inputSha256,
			String modelSha256, Instant startedAt, Instant completedAt, String failureReason) {
		return """
				{"schema_version":"ai-hand-pose-result.v2","message_id":"%s","job_id":"%s","attempt":%d,
				"lease_token":"%s","input_sha256":"%s","model_sha256":"%s","profile_sha256":"%s",
				"started_at":"%s","completed_at":"%s","outcome":"FAILED","analysis_complete":false,
				"hand_result":"UNKNOWN","total_video_frames":3,"inference_frame_count":3,
				"frames_with_hand":0,"frames_without_hand":3,"left_hand_detection_count":0,
				"right_hand_detection_count":0,"invalid_landmark_count":1,"processing_duration_ms":17,
				"failure_reason":"%s","candidates":[]}
				""".formatted(messageId, jobId, attempt, leaseToken, inputSha256, modelSha256, PROFILE_SHA256,
				startedAt, completedAt, failureReason);
	}

	public static String candidatesJson() {
		return IntStream.range(0, OBSERVATIONS.size())
			.mapToObj(index -> candidateJson(OBSERVATIONS.get(index), index))
			.collect(Collectors.joining(","));
	}

	private static String candidateJson(Observation observation, int seed) {
		return """
				{"frame_timestamp_ms":%d,"image_width":%d,"image_height":%d,"hand_index":%d,
				"handedness":"%s","handedness_score":%s,
				"bounding_box":{"x":%s,"y":%s,"width":0.020,"height":0.040},"landmarks":[%s],
				"valid_landmark_count":21}
				""".formatted(observation.frameTimestampMs(), IMAGE_WIDTH, IMAGE_HEIGHT, observation.handIndex(),
				observation.handedness(), observation.handednessScore(), decimal(100 + seed * 10),
				decimal(200 + seed * 10), landmarksJson(seed));
	}

	private static String landmarksJson(int seed) {
		return IntStream.range(0, 21)
			.mapToObj(index -> "{\"x\":" + decimal(100 + seed * 10 + index) + ",\"y\":"
					+ decimal(200 + seed * 10 + index * 2) + ",\"z\":" + decimal(-(10 + seed + index)) + "}")
			.collect(Collectors.joining(","));
	}

	private static String decimal(int thousandths) {
		return BigDecimal.valueOf(thousandths, 3).toPlainString();
	}

	public record Observation(long frameTimestampMs, int handIndex, String handedness, double handednessScore) {
	}

}
