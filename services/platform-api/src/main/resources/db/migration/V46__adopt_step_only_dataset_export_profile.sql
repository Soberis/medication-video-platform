ALTER TABLE dataset_build_specification
    DROP CONSTRAINT ck_dataset_build_specification_export_profile_key,
    DROP CONSTRAINT ck_dataset_build_specification_export_profile_digest,
    ADD CONSTRAINT ck_dataset_build_specification_export_profile
        CHECK (
            (
                export_profile_key = 'p0-dataset-export-v1'
                AND export_profile_digest = '3fac783efda31ad2b68ec4bc18ca7b46c7f6636aeae7bb61c070f4ac1d6da888'
            )
            OR (
                export_profile_key = 'p0-dataset-export-v2'
                AND export_profile_digest = '5f86b48ccd02ef8ea0a05c967b85017bb9cd5de59b8f466b7bb93dd552fdcb96'
            )
        );

ALTER TABLE dataset_export
    DROP CONSTRAINT ck_dataset_export_profile_key,
    DROP CONSTRAINT ck_dataset_export_profile_digest,
    ADD CONSTRAINT ck_dataset_export_profile
        CHECK (
            (
                export_profile_key = 'p0-dataset-export-v1'
                AND export_profile_digest = '3fac783efda31ad2b68ec4bc18ca7b46c7f6636aeae7bb61c070f4ac1d6da888'
            )
            OR (
                export_profile_key = 'p0-dataset-export-v2'
                AND export_profile_digest = '5f86b48ccd02ef8ea0a05c967b85017bb9cd5de59b8f466b7bb93dd552fdcb96'
            )
        );
