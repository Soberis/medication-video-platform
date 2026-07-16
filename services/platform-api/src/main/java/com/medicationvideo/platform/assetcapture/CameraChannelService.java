package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
class CameraChannelService {

	private final CameraChannelRepository repository;

	CameraChannelService(CameraChannelRepository repository) {
		this.repository = repository;
	}

	CameraChannelResponse provision(UUID projectId, String provisioningKey) {
		var existing = this.repository.findByProjectIdAndProvisioningKey(projectId, provisioningKey);
		if (existing.isPresent()) {
			return CameraChannelResponse.from(existing.get());
		}
		var channel = CameraChannel.provision(
				projectId,
				provisioningKey,
				Instant.now().truncatedTo(ChronoUnit.MICROS));
		try {
			return CameraChannelResponse.from(this.repository.saveAndFlush(channel));
		}
		catch (DataIntegrityViolationException exception) {
			return this.repository.findByProjectIdAndProvisioningKey(projectId, provisioningKey)
					.map(CameraChannelResponse::from)
					.orElseThrow(() -> exception);
		}
	}
}
