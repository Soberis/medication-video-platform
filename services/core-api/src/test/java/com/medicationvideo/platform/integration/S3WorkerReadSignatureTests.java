package com.medicationvideo.platform.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Proves against real object storage that a worker-facing read URL is downloadable at the
 * authority the Worker dials.
 *
 * <p>
 * The production failure this guards against: a URL signed for the primary endpoint
 * ({@code localhost}) had its authority rewritten to the Worker-reachable one before
 * being handed out, so every download answered {@code 403 SignatureDoesNotMatch}. The
 * container below publishes one port that is reachable under two different authorities;
 * the intermediate assertion reproduces the rejected request so this test cannot silently
 * pass if the object storage ever stops enforcing the signature.
 */
class S3WorkerReadSignatureTests {

	private static final DockerImageName SEAWEEDFS_IMAGE = DockerImageName
		.parse("chrislusf/seaweedfs:4.29@sha256:d47c7ee99fcb951351d7194915f4e3a5ea604a8e8871183d713907dec4fb9bf5");

	private static final String ACCESS_KEY = "synthetic-worker-signature-access";

	private static final String SECRET_KEY = "synthetic-worker-signature-secret";

	private static final String BUCKET = "worker-signature";

	@Test
	void workerReadUrlValidatesAtTheAuthorityTheWorkerDials() throws Exception {
		try (var seaweed = new GenericContainer<>(SEAWEEDFS_IMAGE).withExposedPorts(8333)
			.withEnv("AWS_ACCESS_KEY_ID", ACCESS_KEY)
			.withEnv("AWS_SECRET_ACCESS_KEY", SECRET_KEY)
			.withEnv("S3_BUCKET", BUCKET)
			.withCommand("mini", "-dir=/data")
			.waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403))) {
			seaweed.start();

			var port = seaweed.getMappedPort(8333);
			// One container, two spellings of the same published port: the authority the
			// API
			// host uses, and the authority a Worker elsewhere dials.
			var endpoint = URI.create("http://127.0.0.1:" + port);
			var workerEndpoint = URI.create("http://localhost:" + port);
			var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
			var s3Configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build();

			try (var s3 = S3Client.builder()
				.endpointOverride(endpoint)
				.region(Region.US_EAST_1)
				.credentialsProvider(credentials)
				.serviceConfiguration(s3Configuration)
				.httpClient(UrlConnectionHttpClient.create())
				.build();
					var presigner = presigner(endpoint, credentials, s3Configuration);
					var workerPresigner = presigner(workerEndpoint, credentials, s3Configuration)) {
				var key = "synthetic/worker-read.bin";
				var payload = "worker-read-signature".getBytes(StandardCharsets.UTF_8);
				// Seed through a presigned PUT, the same way the platform accepts
				// uploads. SeaweedFS answers the SDK's aws-chunked streaming PUT with
				// "The Content-Md5 you specified is not valid", and how the object got
				// there is irrelevant to what this test asserts.
				var seedUrl = presigner
					.presignPutObject(PutObjectPresignRequest.builder()
						.signatureDuration(Duration.ofMinutes(5))
						.putObjectRequest(PutObjectRequest.builder().bucket(BUCKET).key(key).build())
						.build())
					.url();
				var seedResponse = HttpClient.newHttpClient()
					.send(HttpRequest.newBuilder(seedUrl.toURI())
						.PUT(HttpRequest.BodyPublishers.ofByteArray(payload))
						.build(), HttpResponse.BodyHandlers.discarding());
				assertEquals(200, seedResponse.statusCode(), "object seeding failed");

				var properties = new S3ObjectStorageProperties(endpoint, Region.US_EAST_1.id(), BUCKET, ACCESS_KEY,
						SECRET_KEY, true, workerEndpoint);
				var storage = new S3MultipartObjectStorage(s3, presigner, workerPresigner, properties);
				var reference = new ObjectReference(BUCKET, key);

				var primaryUrl = storage.authorizeRead(reference, Duration.ofMinutes(5));
				assertTrue(primaryUrl.toString().startsWith("http://127.0.0.1:"), primaryUrl.toString());
				assertEquals(200, fetch(primaryUrl).statusCode());

				// Guards the guard: a URL signed for the other authority is refused,
				// which is
				// exactly what rewriting the authority of a signed URL used to produce.
				var rewritten = URI.create(workerEndpoint + primaryUrl.getRawPath() + "?" + primaryUrl.getRawQuery());
				var rewrittenResponse = fetch(rewritten);
				assertEquals(403, rewrittenResponse.statusCode(),
						"object storage no longer enforces the signed Host, so this test proves nothing: "
								+ new String(rewrittenResponse.body(), StandardCharsets.UTF_8));

				var workerUrl = storage.authorizeWorkerRead(reference, Duration.ofMinutes(5));
				assertTrue(workerUrl.toString().startsWith("http://localhost:"), workerUrl.toString());
				var workerResponse = fetch(workerUrl);
				assertEquals(200, workerResponse.statusCode(),
						"worker read URL was refused at the authority the worker dials: "
								+ new String(workerResponse.body(), StandardCharsets.UTF_8));
				assertArrayEquals(payload, workerResponse.body());
			}
		}
	}

	private static HttpResponse<byte[]> fetch(URI url) throws Exception {
		return HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	private static S3Presigner presigner(URI endpoint, StaticCredentialsProvider credentials,
			S3Configuration s3Configuration) {
		return S3Presigner.builder()
			.endpointOverride(endpoint)
			.region(Region.US_EAST_1)
			.credentialsProvider(credentials)
			.serviceConfiguration(s3Configuration)
			.build();
	}

}
