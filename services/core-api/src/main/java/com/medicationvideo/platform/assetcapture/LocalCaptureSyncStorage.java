package com.medicationvideo.platform.assetcapture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({ "local", "test" })
class LocalCaptureSyncStorage implements CaptureSyncStoragePort {

	private final Path root;

	LocalCaptureSyncStorage(@Value("${platform.capture-sync.local-storage-root:}") String root) {
		this.root = Path
			.of(root == null || root.isBlank() ? System.getProperty("java.io.tmpdir") : root, "capture-sync-artifacts")
			.toAbsolutePath()
			.normalize();
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
		if (hasUnsafePath(object.bucket()) || hasUnsafePath(object.objectKey())) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_ARTIFACT_PATH_INVALID",
					"The synchronized-frame artifact location is outside the approved storage root.", false);
		}
		var bucket = Path.of(object.bucket());
		var objectKey = Path.of(object.objectKey());
		if (bucket.isAbsolute() || objectKey.isAbsolute()) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_ARTIFACT_PATH_INVALID",
					"The synchronized-frame artifact location must be relative to the approved storage root.", false);
		}
		var resolved = this.root.resolve(bucket).resolve(objectKey).normalize();
		if (!resolved.startsWith(this.root)) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_ARTIFACT_PATH_INVALID",
					"The synchronized-frame artifact location is outside the approved storage root.", false);
		}
		return resolved;
	}

	@Override
	public InputFile open(ObjectReference object) {
		var path = resolve(object);
		if (!Files.isRegularFile(path)) {
			throw new CaptureSyncExecutionException("CAPTURE_SYNC_ARTIFACT_NOT_FOUND",
					"The synchronized-frame artifact was not found at the approved location.", false);
		}
		return new InputFile() {

			@Override
			public long getLength() throws IOException {
				return Files.size(path);
			}

			@Override
			public SeekableInputStream newStream() throws IOException {
				return new LocalSeekableInputStream(path);
			}

		};
	}

	private static boolean hasUnsafePath(String value) {
		return Arrays.stream(value.split("[/\\\\]", -1)).anyMatch(segment -> segment.equals(".."));
	}

	private static final class LocalSeekableInputStream extends SeekableInputStream {

		private final java.nio.channels.FileChannel channel;

		private LocalSeekableInputStream(Path path) throws IOException {
			this.channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ);
		}

		@Override
		public long getPos() throws IOException {
			return this.channel.position();
		}

		@Override
		public void seek(long newPos) throws IOException {
			if (newPos < 0) {
				throw new IOException("negative seek position");
			}
			this.channel.position(newPos);
		}

		@Override
		public int read() throws IOException {
			var buffer = java.nio.ByteBuffer.allocate(1);
			return this.channel.read(buffer) < 0 ? -1 : Byte.toUnsignedInt(buffer.array()[0]);
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			return this.channel.read(java.nio.ByteBuffer.wrap(bytes, offset, length));
		}

		@Override
		public int read(java.nio.ByteBuffer buffer) throws IOException {
			return this.channel.read(buffer);
		}

		@Override
		public void readFully(byte[] bytes) throws IOException {
			readFully(bytes, 0, bytes.length);
		}

		@Override
		public void readFully(byte[] bytes, int offset, int length) throws IOException {
			var buffer = java.nio.ByteBuffer.wrap(bytes, offset, length);
			while (buffer.hasRemaining()) {
				if (this.channel.read(buffer) < 0) {
					throw new java.io.EOFException();
				}
			}
		}

		@Override
		public void readFully(java.nio.ByteBuffer buffer) throws IOException {
			while (buffer.hasRemaining()) {
				if (this.channel.read(buffer) < 0) {
					throw new java.io.EOFException();
				}
			}
		}

		@Override
		public void close() throws IOException {
			this.channel.close();
		}

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
