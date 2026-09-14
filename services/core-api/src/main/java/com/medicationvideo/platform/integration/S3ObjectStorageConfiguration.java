package com.medicationvideo.platform.integration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3ObjectStorageProperties.class)
class S3ObjectStorageConfiguration {

	@Bean
	S3Client s3Client(S3ObjectStorageProperties properties) {
		return S3Client.builder()
			.endpointOverride(properties.endpoint())
			.region(Region.of(properties.region()))
			.credentialsProvider(credentials(properties))
			.serviceConfiguration(serviceConfiguration(properties))
			.httpClientBuilder(UrlConnectionHttpClient.builder())
			.build();
	}

	/**
	 * Default presigner, bound to the primary endpoint. Marked {@link Primary} because
	 * two presigners exist and the primary one is what every unqualified injection point
	 * means.
	 */
	@Bean
	@Primary
	S3Presigner s3Presigner(S3ObjectStorageProperties properties) {
		return presigner(properties, properties.endpoint());
	}

	/**
	 * Presigner used exclusively for Worker-facing download URLs. SigV4 binds the
	 * {@code Host} header at signing time, so a URL that a Worker on another machine
	 * dials must be signed for <em>that</em> endpoint; rewriting the authority of a URL
	 * signed for the primary endpoint cannot validate and the object storage answers
	 * {@code 403 SignatureDoesNotMatch}. Falls back to the primary endpoint when no
	 * worker endpoint is configured.
	 */
	@Bean
	S3Presigner workerS3Presigner(S3ObjectStorageProperties properties) {
		var workerEndpoint = properties.workerEndpoint();
		return presigner(properties, workerEndpoint == null ? properties.endpoint() : workerEndpoint);
	}

	private static S3Presigner presigner(S3ObjectStorageProperties properties, java.net.URI endpoint) {
		return S3Presigner.builder()
			.endpointOverride(endpoint)
			.region(Region.of(properties.region()))
			.credentialsProvider(credentials(properties))
			.serviceConfiguration(serviceConfiguration(properties))
			.build();
	}

	private static StaticCredentialsProvider credentials(S3ObjectStorageProperties properties) {
		return StaticCredentialsProvider
			.create(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
	}

	private static S3Configuration serviceConfiguration(S3ObjectStorageProperties properties) {
		return S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyleAccess()).build();
	}

}
