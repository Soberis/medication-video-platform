package com.medicationvideo.platform.iamproject;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface PlatformAccountRepository extends JpaRepository<PlatformAccount, UUID> {

	Optional<PlatformAccount> findByUsername(String username);

	List<PlatformAccount> findAllByOrderByUsernameAsc();
}
