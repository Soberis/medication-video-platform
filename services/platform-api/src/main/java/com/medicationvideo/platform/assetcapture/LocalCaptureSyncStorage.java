package com.medicationvideo.platform.assetcapture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({ "local", "test" })
class LocalCaptureSyncStorage implements CaptureSyncStoragePort {

	private final Path root;

	LocalCaptureSyncStorage(@Value("${platform.capture-sync.local-storage-root:}") String root) {
		this.root = Path.of(root == null || root.isBlank() ? System.getProperty("java.io.tmpdir") : root,
				"capture-sync-artifacts");
	}

	@Override
	public VerifiedArtifact verify(ObjectReference object, long expectedSizeBytes, String expectedSha256,
			String expectedMediaType) {
		var path = resolve(object);
		if (!Files.isRegularFile(path)) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_ARTIFACT_NOT_FOUND",
					"The synchronized-frame artifact was not found at the approved location.", false);
		}
		long sizeBytes;
		try {
			sizeBytes = Files.size(path);
		}
		catch (IOException exception) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_ARTIFACT_UNREADABLE",
					"The synchronized-frame artifact could not be read.", true);
		}
		if (sizeBytes != expectedSizeBytes) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_ARTIFACT_SIZE_MISMATCH",
					"The synchronized-frame artifact size does not match the Worker result.", false);
		}
		var actualSha256 = sha256(path);
		if (!MessageDigest.isEqual(actualSha256.getBytes(), expectedSha256.getBytes())) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_ARTIFACT_DIGEST_MISMATCH",
					"The synchronized-frame artifact digest does not match the Worker result.", false);
		}
		if (!CaptureSyncArtifact.MEDIA_TYPE.equals(expectedMediaType)) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_ARTIFACT_MEDIA_TYPE_MISMATCH",
					"The synchronized-frame artifact media type is invalid.", false);
		}
		return new VerifiedArtifact(object, sizeBytes, actualSha256, expectedMediaType);
	}

	Path resolve(ObjectReference object) {
		return this.root.resolve(object.bucket()).resolve(object.objectKey());
	}

	void registerForTest(ObjectReference object, byte[] content) throws IOException {
		var path = resolve(object);
		Files.createDirectories(path.getParent());
		Files.write(path, content);
	}

	private static String sha256(Path path) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			try (var input = Files.newInputStream(path)) {
				var buffer = new byte[8192];
				int read;
				while ((read = input.read(buffer)) >= 0) {
					digest.update(buffer, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (IOException | NoSuchAlgorithmException exception) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_ARTIFACT_UNREADABLE",
					"The synchronized-frame artifact could not be hashed.", true);
		}
	}

}
