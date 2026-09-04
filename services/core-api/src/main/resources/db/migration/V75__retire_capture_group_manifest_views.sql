-- Retire the superseded CaptureGroup eligibility manifest projections.
-- The underlying CaptureGroup, Review and Cleaning facts remain authoritative.
DROP VIEW IF EXISTS training_data_group_eligibility_v3;
DROP VIEW IF EXISTS training_data_group_eligibility_v3_integrity;
