ALTER TABLE ai_job
    ADD COLUMN profile_sha256 VARCHAR(64) NOT NULL DEFAULT '3a3be8035ec8a9756a32f43b007a4bb2cc2e2c0468d744d248f19c692fd90626';

ALTER TABLE ai_job
    ALTER COLUMN profile_sha256 DROP DEFAULT,
    ADD CONSTRAINT ck_ai_job_profile_sha256 CHECK (profile_sha256 ~ '^[a-f0-9]{64}$'),
    DROP CONSTRAINT uk_ai_job_input_algorithm,
    ADD CONSTRAINT uk_ai_job_input_algorithm_profile UNIQUE (project_id, clip_asset_id, input_sha256, algorithm_key,
        algorithm_version, model_sha256, sampling_fps, profile_sha256);
