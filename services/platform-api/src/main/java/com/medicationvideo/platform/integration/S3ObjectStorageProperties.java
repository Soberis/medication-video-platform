package com.medicationvideo.platform.integration;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties("platform.object-storage")
record S3ObjectStorageProperties(
		@NotNull URI endpoint,
		@NotBlank String region,
		@NotBlank String bucket,
		@NotBlank String accessKey,
		@NotBlank String secretKey,
		boolean pathStyleAccess) {
}
