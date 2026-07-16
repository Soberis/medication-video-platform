package com.medicationvideo.platform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.medicationvideo.platform.assetcapture.MultipartObjectStorage.ObjectReference;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3MultipartCompatibilityTests {

	private static final DockerImageName SEAWEEDFS_IMAGE = DockerImageName.parse(
			"chrislusf/seaweedfs:4.29@sha256:d47c7ee99fcb951351d7194915f4e3a5ea604a8e8871183d713907dec4fb9bf5");
	private static final String ACCESS_KEY = "synthetic-compatibility-access";
	private static final String SECRET_KEY = "synthetic-compatibility-secret";
	private static final String BUCKET = "multipart-compatibility";

	@Test
	void supportsPresignedUploadCompletionInspectionAndFullSha256() throws Exception {
		try (var seaweed = new GenericContainer<>(SEAWEEDFS_IMAGE)
				.withExposedPorts(8333)
				.withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY)
				.withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
				.withEnv("S3_BUCKET", BUCKET)
				.withCommand("mini", "-dir=/data")
				.waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403))) {
			seaweed.start();

			var endpoint = URI.create("http://" + seaweed.getHost() + ":" + seaweed.getMappedPort(8333));
			var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
			var s3Configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build();

			try (var s3 = S3Client.builder()
					.endpointOverride(endpoint)
					.region(Region.US_EAST_1)
					.credentialsProvider(credentials)
					.serviceConfiguration(s3Configuration)
					.httpClient(UrlConnectionHttpClient.create())
					.build();
				var presigner = S3Presigner.builder()
						.endpointOverride(endpoint)
						.region(Region.US_EAST_1)
						.credentialsProvider(credentials)
						.serviceConfiguration(s3Configuration)
						.build()) {
				var key = "synthetic/compatibility.bin";
				var properties = new S3ObjectStorageProperties(
						endpoint,
						Region.US_EAST_1.id(),
						BUCKET,
						ACCESS_KEY,
						SECRET_KEY,
						true);
				var storage = new S3MultipartObjectStorage(s3, presigner, properties);
				var created = storage.create(key, "application/octet-stream");
				assertFalse(created.uploadId().isBlank());
				assertFalse(storage.findCompletedObject(created).isPresent());
				var completed = false;

				try {
					var syntheticPart = new byte[8 * 1024 * 1024];
					var request = HttpRequest.newBuilder(storage.authorizePart(created, 1, Duration.ofMinutes(5)))
							.PUT(HttpRequest.BodyPublishers.ofByteArray(syntheticPart))
							.build();
					var response = HttpClient.newHttpClient()
							.send(request, HttpResponse.BodyHandlers.discarding());
					assertEquals(200, response.statusCode());

					var part = storage.findPart(created, 1).orElseThrow();
					assertEquals(1, part.partNumber());
					assertEquals(syntheticPart.length, part.sizeBytes());
					var storedParts = storage.listParts(created);
					assertEquals(1, storedParts.size());

					var completedObject = storage.complete(created, storedParts);
					completed = true;
					assertEquals(syntheticPart.length, completedObject.sizeBytes());
					assertEquals(completedObject, storage.findCompletedObject(created).orElseThrow());
					assertEquals(
							HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(syntheticPart)),
							storage.calculateSha256(created));
					assertArrayEquals(
							syntheticPart,
							storage.readCompletedObject(
									new ObjectReference(BUCKET, key),
									syntheticPart.length));
					var readRequest = HttpRequest.newBuilder(storage.authorizeRead(
							new ObjectReference(BUCKET, key), Duration.ofMinutes(5)))
							.GET()
							.build();
					var readResponse = HttpClient.newHttpClient()
							.send(readRequest, HttpResponse.BodyHandlers.ofByteArray());
					assertEquals(200, readResponse.statusCode());
					assertArrayEquals(syntheticPart, readResponse.body());

					storage.delete(new ObjectReference(BUCKET, key));
					assertFalse(storage.findCompletedObject(created).isPresent());

					var abandoned = storage.create(
							"synthetic/abandoned-compatibility.bin", "application/octet-stream");
					storage.abort(abandoned);
					storage.abort(abandoned);
				}
				finally {
					if (!completed) {
						storage.abort(created);
					}
				}
			}
		}
	}
}
