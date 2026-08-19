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
 * Immutable accepted media-probe report. Failed probe diagnostics belong to the job
 * attempt and result envelope; a MediaReport exists only after input digest and
 * full-stream decode checks have succeeded. Timing fields preserve rational media time
 * instead of deriving timestamps from a floating-point FPS value.
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

	private MediaReport(MediaJob job, MediaReportPayload payload, String softwareDigest, Instant createdAt) {
		this.mediaReportId = UUID.randomUUID();
		this.mediaJobId = job.getMediaJobId();
		this.projectId = job.getProjectId();
		this.clipAssetId = job.getClipAssetId();
		this.reportSchemaVersion = "media-report.v1";
		this.inputDigest = job.getInputDigest();
		this.recipeDigest = job.getRecipeDigest();
		this.softwareDigest = softwareDigest;
		this.containerFormat = payload.containerFormat();
		this.durationMicroseconds = payload.durationMicroseconds();
		this.containerStartTimeMicroseconds = payload.containerStartTimeMicroseconds();
		this.videoStreamCount = payload.videoStreamCount();
		this.primaryVideoStreamIndex = payload.primaryVideoStreamIndex();
		this.videoCodec = payload.videoCodec();
		this.videoProfile = payload.videoProfile();
		this.width = payload.width();
		this.height = payload.height();
		this.pixelFormat = payload.pixelFormat();
		this.colorSpace = payload.colorSpace();
		this.primaryVideoBitrateBps = payload.primaryVideoBitrateBps();
		this.hasBFrames = payload.hasBFrames();
		this.streamStartTimeMicroseconds = payload.streamStartTimeMicroseconds();
		this.timeBaseNumerator = payload.timeBaseNumerator();
		this.timeBaseDenominator = payload.timeBaseDenominator();
		this.firstPts = payload.firstPts();
		this.lastPts = payload.lastPts();
		this.nominalFrameRateNumerator = payload.nominalFrameRateNumerator();
		this.nominalFrameRateDenominator = payload.nominalFrameRateDenominator();
		this.averageFrameRateNumerator = payload.averageFrameRateNumerator();
		this.averageFrameRateDenominator = payload.averageFrameRateDenominator();
		this.frameCount = payload.frameCount();
		this.variableFrameRate = payload.variableFrameRate();
		this.audioStreamCount = payload.audioStreamCount();
		this.sampleAspectRatioNumerator = payload.sampleAspectRatioNumerator();
		this.sampleAspectRatioDenominator = payload.sampleAspectRatioDenominator();
		this.decodeVerified = payload.decodeVerified();
		this.inputDigestVerified = payload.inputDigestVerified();
		this.warningCodes = payload.warningCodes().stream().map(Enum::name).toList();
		this.createdAt = createdAt;
	}

	static MediaReport accept(MediaJob job, MediaReportPayload payload, String softwareDigest, Instant createdAt) {
		if (!Boolean.TRUE.equals(payload.decodeVerified()) || !Boolean.TRUE.equals(payload.inputDigestVerified())) {
			throw new MediaJobExecutionException("MEDIA_REPORT_VERIFICATION_REQUIRED",
					"A successful media report must prove digest and full decode verification.", false);
		}
		return new MediaReport(job, payload, softwareDigest, createdAt);
	}

	UUID getMediaReportId() {
		return this.mediaReportId;
	}

	UUID getMediaJobId() {
		return this.mediaJobId;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getClipAssetId() {
		return this.clipAssetId;
	}

	String getReportSchemaVersion() {
		return this.reportSchemaVersion;
	}

	String getInputDigest() {
		return this.inputDigest;
	}

	String getRecipeDigest() {
		return this.recipeDigest;
	}

	String getSoftwareDigest() {
		return this.softwareDigest;
	}

	String getContainerFormat() {
		return this.containerFormat;
	}

	long getDurationMicroseconds() {
		return this.durationMicroseconds;
	}

	Long getContainerStartTimeMicroseconds() {
		return this.containerStartTimeMicroseconds;
	}

	int getVideoStreamCount() {
		return this.videoStreamCount;
	}

	int getPrimaryVideoStreamIndex() {
		return this.primaryVideoStreamIndex;
	}

	String getVideoCodec() {
		return this.videoCodec;
	}

	String getVideoProfile() {
		return this.videoProfile;
	}

	int getWidth() {
		return this.width;
	}

	int getHeight() {
		return this.height;
	}

	String getPixelFormat() {
		return this.pixelFormat;
	}

	String getColorSpace() {
		return this.colorSpace;
	}

	Long getPrimaryVideoBitrateBps() {
		return this.primaryVideoBitrateBps;
	}

	Boolean getHasBFrames() {
		return this.hasBFrames;
	}

	Long getStreamStartTimeMicroseconds() {
		return this.streamStartTimeMicroseconds;
	}

	int getTimeBaseNumerator() {
		return this.timeBaseNumerator;
	}

	int getTimeBaseDenominator() {
		return this.timeBaseDenominator;
	}

	Long getFirstPts() {
		return this.firstPts;
	}

	Long getLastPts() {
		return this.lastPts;
	}

	Integer getNominalFrameRateNumerator() {
		return this.nominalFrameRateNumerator;
	}

	Integer getNominalFrameRateDenominator() {
		return this.nominalFrameRateDenominator;
	}

	Integer getAverageFrameRateNumerator() {
		return this.averageFrameRateNumerator;
	}

	Integer getAverageFrameRateDenominator() {
		return this.averageFrameRateDenominator;
	}

	Long getFrameCount() {
		return this.frameCount;
	}

	boolean isVariableFrameRate() {
		return this.variableFrameRate;
	}

	int getAudioStreamCount() {
		return this.audioStreamCount;
	}

	Integer getSampleAspectRatioNumerator() {
		return this.sampleAspectRatioNumerator;
	}

	Integer getSampleAspectRatioDenominator() {
		return this.sampleAspectRatioDenominator;
	}

	boolean isDecodeVerified() {
		return this.decodeVerified;
	}

	boolean isInputDigestVerified() {
		return this.inputDigestVerified;
	}

	List<String> getWarningCodes() {
		return List.copyOf(this.warningCodes);
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

}
