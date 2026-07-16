package com.medicationvideo.platform.mediaprivacy;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Structure-frozen MediaReport table. The schema is finalised in this slice but no rows are
 * written until a Worker (with a real probe execution) is introduced. The fields are nullable
 * to support the partial and failed-probe shapes the Worker will eventually emit.
 */
@Entity
@Table(name = "media_report")
class MediaReport {

	@Id
	@Column(name = "media_report_id", nullable = false, updatable = false)
	private UUID mediaReportId;

	@Column(name = "media_job_id", nullable = false, updatable = false)
	private UUID mediaJobId;

	@Column(name = "container_format", length = 64, updatable = false)
	private String containerFormat;

	@Column(name = "video_codec", length = 64, updatable = false)
	private String videoCodec;

	@Column(name = "audio_codec", length = 64, updatable = false)
	private String audioCodec;

	@Column(name = "has_audio", updatable = false)
	private Boolean hasAudio;

	@Column(name = "duration_microseconds", updatable = false)
	private Long durationMicroseconds;

	@Column(name = "width", updatable = false)
	private Integer width;

	@Column(name = "height", updatable = false)
	private Integer height;

	@Column(name = "frame_rate_numerator", updatable = false)
	private Integer frameRateNumerator;

	@Column(name = "frame_rate_denominator", updatable = false)
	private Integer frameRateDenominator;

	@Column(name = "bitrate_bps", updatable = false)
	private Long bitrateBps;

	@Column(name = "color_space", length = 64, updatable = false)
	private String colorSpace;

	@Column(name = "has_b_frames", updatable = false)
	private Boolean hasBFrames;

	@Column(name = "sample_aspect_ratio", length = 16, updatable = false)
	private String sampleAspectRatio;

	@Column(name = "creation_time_rfc3339", length = 64, updatable = false)
	private String creationTimeRfc3339;

	@Column(name = "timebase_reference", length = 128, updatable = false)
	private String timebaseReference;

	@Column(name = "soft_hash", length = 64, updatable = false)
	private String softHash;

	@Column(name = "error_class", length = 64, updatable = false)
	private String errorClass;

	@Column(name = "error_code", length = 64, updatable = false)
	private String errorCode;

	@Column(name = "error_message", length = 512, updatable = false)
	private String errorMessage;

	@Column(name = "error_retryable", updatable = false)
	private Boolean errorRetryable;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected MediaReport() {
	}
}
