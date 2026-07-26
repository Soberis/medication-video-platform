package com.medicationvideo.platform.qualitycleaning;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CleaningRuleSetRepository extends JpaRepository<CleaningRuleSet, UUID> {

	Optional<CleaningRuleSet> findByRuleSetKeyAndStatus(
			String ruleSetKey,
			CleaningRuleSetStatus status);
}
