package com.medicationvideo.platform.mediaprivacy;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface MediaJobAttemptRepository extends JpaRepository<MediaJobAttempt, UUID> {
}
