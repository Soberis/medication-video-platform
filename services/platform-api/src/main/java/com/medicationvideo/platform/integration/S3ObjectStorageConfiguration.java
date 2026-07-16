package com.medicationvideo.platform.integration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

	@Bean
	S3Presigner s3Presigner(S3ObjectStorageProperties properties) {
		return S3Presigner.builder()
				.endpointOverride(properties.endpoint())
				.region(Region.of(properties.region()))
				.credentialsProvider(credentials(properties))
				.serviceConfiguration(serviceConfiguration(properties))
				.build();
	}

	private static StaticCredentialsProvider credentials(S3ObjectStorageProperties properties) {
		return StaticCredentialsProvider.create(
				AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
	}

	private static S3Configuration serviceConfiguration(S3ObjectStorageProperties properties) {
		return S3Configuration.builder()
				.pathStyleAccessEnabled(properties.pathStyleAccess())
				.build();
	}
}
