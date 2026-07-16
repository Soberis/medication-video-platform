package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Immutable accepted media-probe report. Failed probe diagnostics belong to the job attempt and
 * result envelope; a MediaReport exists only after input digest and full-stream decode checks have
 * succeeded. Timing fields preserve rational media time instead of deriving timestamps from a
 * floating-point FPS value.
 */
@Immutable
@Entity
@Table(name = "media_report")
class MediaReport {

	@Id
	@Column(name = "media_report_id", nullable = false, updatable = false)
	private UUID mediaReportId;

	@Column(name = "media_job_id", nullable = false, updatable = false)
	private UUID mediaJobId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "clip_asset_id", nullable = false, updatable = false)
	private UUID clipAssetId;

	@Column(name = "report_schema_version", nullable = false, length = 64, updatable = false)
	private String reportSchemaVersion;

	@Column(name = "input_digest", nullable = false, length = 64, updatable = false)
	private String inputDigest;

	@Column(name = "recipe_digest", nullable = false, length = 64, updatable = false)
	private String recipeDigest;

	@Column(name = "software_digest", nullable = false, length = 71, updatable = false)
	private String softwareDigest;

	@Column(name = "container_format", nullable = false, length = 64, updatable = false)
	private String containerFormat;

	@Column(name = "duration_microseconds", nullable = false, updatable = false)
	private long durationMicroseconds;

	@Column(name = "container_start_time_microseconds", updatable = false)
	private Long containerStartTimeMicroseconds;

	@Column(name = "video_stream_count", nullable = false, updatable = false)
	private int videoStreamCount;

	@Column(name = "primary_video_stream_index", nullable = false, updatable = false)
	private int primaryVideoStreamIndex;

	@Column(name = "video_codec", nullable = false, length = 64, updatable = false)
	private String videoCodec;

	@Column(name = "video_profile", length = 64, updatable = false)
	private String videoProfile;

	@Column(name = "width", nullable = false, updatable = false)
	private int width;

	@Column(name = "height", nullable = false, updatable = false)
	private int height;

	@Column(name = "pixel_format", length = 64, updatable = false)
	private String pixelFormat;

	@Column(name = "color_space", length = 64, updatable = false)
	private String colorSpace;

	@Column(name = "primary_video_bitrate_bps", updatable = false)
	private Long primaryVideoBitrateBps;

	@Column(name = "has_b_frames", updatable = false)
	private Boolean hasBFrames;

	@Column(name = "stream_start_time_microseconds", updatable = false)
	private Long streamStartTimeMicroseconds;

	@Column(name = "time_base_numerator", nullable = false, updatable = false)
	private int timeBaseNumerator;

	@Column(name = "time_base_denominator", nullable = false, updatable = false)
	private int timeBaseDenominator;

	@Column(name = "first_pts", updatable = false)
	private Long firstPts;

	@Column(name = "last_pts", updatable = false)
	private Long lastPts;

	@Column(name = "nominal_frame_rate_numerator", updatable = false)
	private Integer nominalFrameRateNumerator;

	@Column(name = "nominal_frame_rate_denominator", updatable = false)
	private Integer nominalFrameRateDenominator;

	@Column(name = "average_frame_rate_numerator", updatable = false)
	private Integer averageFrameRateNumerator;

	@Column(name = "average_frame_rate_denominator", updatable = false)
	private Integer averageFrameRateDenominator;

	@Column(name = "frame_count", updatable = false)
	private Long frameCount;

	@Column(name = "variable_frame_rate", nullable = false, updatable = false)
	private boolean variableFrameRate;

	@Column(name = "audio_stream_count", nullable = false, updatable = false)
	private int audioStreamCount;

	@Column(name = "sample_aspect_ratio_numerator", updatable = false)
	private Integer sampleAspectRatioNumerator;

	@Column(name = "sample_aspect_ratio_denominator", updatable = false)
	private Integer sampleAspectRatioDenominator;

	@Column(name = "decode_verified", nullable = false, updatable = false)
	private boolean decodeVerified;

	@Column(name = "input_digest_verified", nullable = false, updatable = false)
	private boolean inputDigestVerified;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "warning_codes", nullable = false, columnDefinition = "jsonb", updatable = false)
	private List<String> warningCodes;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected MediaReport() {
	}
}
