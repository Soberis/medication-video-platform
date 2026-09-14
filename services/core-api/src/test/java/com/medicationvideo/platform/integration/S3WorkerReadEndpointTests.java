package com.medicationvideo.platform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.medicationvideo.platform.assetcapture.MultipartObjectStorage.ObjectReference;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * A Worker on another machine dials an endpoint this host cannot use itself, so a worker
 * read must carry the <em>worker</em> authority. SigV4 binds the {@code Host} header at
 * signing time: a URL signed for the primary endpoint keeps a signature that can never
 * validate once its authority is rewritten, which the object storage answers with
 * {@code 403 SignatureDoesNotMatch}.
 *
 * <p>
 * These assertions are structural and offline. {@link S3WorkerReadSignatureTests} proves
 * against real object storage that the URL produced here is actually accepted.
 */
class S3WorkerReadEndpointTests {

	private static final String ACCESS_KEY = "synthetic-worker-endpoint-access";

	private static final String SECRET_KEY = "synthetic-worker-endpoint-secret";

	private static final String BUCKET = "worker-endpoint-bucket";

	private static final ObjectReference REFERENCE = new ObjectReference(BUCKET,
			"projects/synthetic/uploads/clip/video.mp4");

	@Test
	void fallsBackToThePrimaryEndpointWhenNoWorkerEndpointIsConfigured() {
		var endpoint = URI.create("http://localhost:19000");
		try (var s3 = s3Client(endpoint); var primary = presigner(endpoint)) {
			var storage = storage(s3, primary, primary, endpoint, null);

			assertEquals("localhost:19000",
					storage.authorizeWorkerRead(REFERENCE, Duration.ofMinutes(5)).getAuthority());
		}
	}

	@Test
	void signsWorkerReadsForTheWorkerEndpointAuthority() {
		var endpoint = URI.create("http://localhost:19000");
		var workerEndpoint = URI.create("http://192.168.1.7:19000");
		try (var s3 = s3Client(endpoint); var primary = presigner(endpoint); var worker = presigner(workerEndpoint)) {
			var storage = storage(s3, primary, worker, endpoint, workerEndpoint);

			assertEquals("localhost:19000", storage.authorizeRead(REFERENCE, Duration.ofMinutes(5)).getAuthority());
			assertEquals("192.168.1.7:19000",
					storage.authorizeWorkerRead(REFERENCE, Duration.ofMinutes(5)).getAuthority());
		}
	}

	private static S3MultipartObjectStorage storage(S3Client s3, S3Presigner presigner, S3Presigner workerPresigner,
			URI endpoint, URI workerEndpoint) {
		var properties = new S3ObjectStorageProperties(endpoint, Region.US_EAST_1.id(), BUCKET, ACCESS_KEY, SECRET_KEY,
				true, workerEndpoint);
		return new S3MultipartObjectStorage(s3, presigner, workerPresigner, properties);
	}

	private static S3Client s3Client(URI endpoint) {
		return S3Client.builder()
			.endpointOverride(endpoint)
			.region(Region.US_EAST_1)
			.credentialsProvider(credentials())
			.serviceConfiguration(pathStyleConfiguration())
			.build();
	}

	private static S3Presigner presigner(URI endpoint) {
		return S3Presigner.builder()
			.endpointOverride(endpoint)
			.region(Region.US_EAST_1)
			.credentialsProvider(credentials())
			.serviceConfiguration(pathStyleConfiguration())
			.build();
	}

	private static StaticCredentialsProvider credentials() {
		return StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
	}

	private static S3Configuration pathStyleConfiguration() {
		return S3Configuration.builder().pathStyleAccessEnabled(true).build();
	}

}
