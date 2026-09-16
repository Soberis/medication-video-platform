-- Nullable target object captures grounded recipient item without breaking historical candidates.
ALTER TABLE action_candidate
    ADD COLUMN target_object VARCHAR(64);
