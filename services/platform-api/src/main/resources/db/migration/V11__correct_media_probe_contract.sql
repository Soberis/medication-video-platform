ALTER TABLE media_job
    ADD COLUMN probe_profile_key VARCHAR(64),
    ADD COLUMN recipe_digest VARCHAR(64);

UPDATE media_job
SET probe_profile_key = 'media-probe-baseline-v1',
    recipe_digest = '8900937958d0fb81a9c9bf2d9e81ac3e57f35d838d5a4da52b58ccf0f5cd5765'
WHERE probe_profile_key IS NULL OR recipe_digest IS NULL;

ALTER TABLE media_job
    ALTER COLUMN probe_profile_key SET NOT NULL,
    ALTER COLUMN recipe_digest SET NOT NULL,
    DROP CONSTRAINT uk_media_job_project_natural_key,
    ADD CONSTRAINT uk_media_job_project_natural_key
        UNIQUE (project_id, job_type, clip_asset_id, input_digest, recipe_digest),
    ADD CONSTRAINT ck_media_job_probe_profile_key
        CHECK (probe_profile_key ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    ADD CONSTRAINT ck_media_job_recipe_digest
        CHECK (recipe_digest ~ '^[a-f0-9]{64}$');

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM media_report) THEN
        RAISE EXCEPTION 'V11 requires media_report to be empty because no result-acceptance path existed before this migration';
    END IF;
END $$;

ALTER TABLE media_report
    DROP COLUMN audio_codec,
    DROP COLUMN has_audio,
    DROP COLUMN frame_rate_numerator,
    DROP COLUMN frame_rate_denominator,
    DROP COLUMN creation_time_rfc3339,
    DROP COLUMN timebase_reference,
    DROP COLUMN soft_hash,
    DROP COLUMN error_class,
    DROP COLUMN error_code,
    DROP COLUMN error_message,
    DROP COLUMN error_retryable,
    DROP COLUMN updated_at,
    DROP COLUMN version,
    DROP CONSTRAINT fk_media_report_job,
    ADD CONSTRAINT fk_media_report_job
        FOREIGN KEY (media_job_id) REFERENCES media_job (media_job_id) ON DELETE RESTRICT;

ALTER TABLE media_report
    RENAME COLUMN bitrate_bps TO primary_video_bitrate_bps;

ALTER TABLE media_report
    ADD COLUMN project_id UUID NOT NULL,
    ADD COLUMN clip_asset_id UUID NOT NULL,
    ADD COLUMN report_schema_version VARCHAR(64) NOT NULL,
    ADD COLUMN input_digest VARCHAR(64) NOT NULL,
    ADD COLUMN recipe_digest VARCHAR(64) NOT NULL,
    ADD COLUMN software_digest VARCHAR(71) NOT NULL,
    ADD COLUMN container_start_time_microseconds BIGINT,
    ADD COLUMN video_stream_count INTEGER NOT NULL,
    ADD COLUMN primary_video_stream_index INTEGER NOT NULL,
    ADD COLUMN video_profile VARCHAR(64),
    ADD COLUMN pixel_format VARCHAR(64),
    ADD COLUMN stream_start_time_microseconds BIGINT,
    ADD COLUMN time_base_numerator INTEGER NOT NULL,
    ADD COLUMN time_base_denominator INTEGER NOT NULL,
    ADD COLUMN first_pts BIGINT,
    ADD COLUMN last_pts BIGINT,
    ADD COLUMN nominal_frame_rate_numerator INTEGER,
    ADD COLUMN nominal_frame_rate_denominator INTEGER,
    ADD COLUMN average_frame_rate_numerator INTEGER,
    ADD COLUMN average_frame_rate_denominator INTEGER,
    ADD COLUMN frame_count BIGINT,
    ADD COLUMN variable_frame_rate BOOLEAN NOT NULL,
    ADD COLUMN audio_stream_count INTEGER NOT NULL,
    ADD COLUMN sample_aspect_ratio_numerator INTEGER,
    ADD COLUMN sample_aspect_ratio_denominator INTEGER,
    ADD COLUMN decode_verified BOOLEAN NOT NULL,
    ADD COLUMN input_digest_verified BOOLEAN NOT NULL,
    ADD COLUMN warning_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
	ALTER COLUMN container_format SET NOT NULL,
	ALTER COLUMN video_codec SET NOT NULL,
	ALTER COLUMN duration_microseconds SET NOT NULL,
	ALTER COLUMN width SET NOT NULL,
	ALTER COLUMN height SET NOT NULL,
    ADD CONSTRAINT fk_media_report_clip_asset
        FOREIGN KEY (clip_asset_id) REFERENCES clip_asset (clip_asset_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_media_report_schema_version
        CHECK (report_schema_version = 'media-report.v1'),
    ADD CONSTRAINT ck_media_report_input_digest
        CHECK (input_digest ~ '^[a-f0-9]{64}$'),
    ADD CONSTRAINT ck_media_report_recipe_digest
        CHECK (recipe_digest ~ '^[a-f0-9]{64}$'),
    ADD CONSTRAINT ck_media_report_software_digest
        CHECK (software_digest ~ '^sha256:[a-f0-9]{64}$'),
    ADD CONSTRAINT ck_media_report_video_stream_count
        CHECK (video_stream_count > 0),
    ADD CONSTRAINT ck_media_report_primary_video_stream_index
        CHECK (primary_video_stream_index >= 0),
    ADD CONSTRAINT ck_media_report_time_base
        CHECK (time_base_numerator > 0 AND time_base_denominator > 0),
    ADD CONSTRAINT ck_media_report_nominal_frame_rate
        CHECK ((nominal_frame_rate_numerator IS NULL) = (nominal_frame_rate_denominator IS NULL)
            AND (nominal_frame_rate_denominator IS NULL OR nominal_frame_rate_denominator > 0)
            AND (nominal_frame_rate_numerator IS NULL OR nominal_frame_rate_numerator >= 0)),
    ADD CONSTRAINT ck_media_report_average_frame_rate
        CHECK ((average_frame_rate_numerator IS NULL) = (average_frame_rate_denominator IS NULL)
            AND (average_frame_rate_denominator IS NULL OR average_frame_rate_denominator > 0)
            AND (average_frame_rate_numerator IS NULL OR average_frame_rate_numerator >= 0)),
    ADD CONSTRAINT ck_media_report_frame_count
        CHECK (frame_count IS NULL OR frame_count >= 0),
    ADD CONSTRAINT ck_media_report_audio_stream_count
        CHECK (audio_stream_count >= 0),
    ADD CONSTRAINT ck_media_report_sample_aspect_ratio
        CHECK ((sample_aspect_ratio_numerator IS NULL) = (sample_aspect_ratio_denominator IS NULL)
            AND (sample_aspect_ratio_denominator IS NULL OR sample_aspect_ratio_denominator > 0)
            AND (sample_aspect_ratio_numerator IS NULL OR sample_aspect_ratio_numerator > 0)),
    ADD CONSTRAINT ck_media_report_warning_codes_array
        CHECK (jsonb_typeof(warning_codes) = 'array'),
    ADD CONSTRAINT ck_media_report_acceptance_verified
        CHECK (decode_verified AND input_digest_verified);

CREATE INDEX ix_media_report_project_asset
    ON media_report (project_id, clip_asset_id, created_at DESC);
