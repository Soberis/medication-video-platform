package com.medicationvideo.platform.integration;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param endpoint primary endpoint used by the API host itself and by same-host clients
 * @param workerEndpoint endpoint a Worker on another machine can fetch from; {@code null}
 * keeps the primary endpoint
 */
@Validated
@ConfigurationProperties("platform.object-storage")
record S3ObjectStorageProperties(@NotNull URI endpoint, @NotBlank String region, @NotBlank String bucket,
		@NotBlank String accessKey, @NotBlank String secretKey, boolean pathStyleAccess, URI workerEndpoint) {

	/**
	 * An unset environment variable arrives as a blank string, which binds to a host-less
	 * URI rather than {@code null}. Normalising here keeps every consumer on a single
	 * rule: a worker endpoint is either usable or absent.
	 */
	S3ObjectStorageProperties {
		if (workerEndpoint != null && (workerEndpoint.getHost() == null || workerEndpoint.getHost().isBlank())) {
			workerEndpoint = null;
		}
	}

}
