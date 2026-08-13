package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCaptureSyncStorageTests {

	@Test
	void resolvesOnlyRelativePathsInsideConfiguredRoot(@TempDir Path tempDir) {
		var storage = new LocalCaptureSyncStorage(tempDir.toString());
		var resolved = storage.resolve(new CaptureSyncStoragePort.ObjectReference("bucket", "nested/file.parquet"));
		assertEquals(tempDir.resolve("capture-sync-artifacts/bucket/nested/file.parquet").toAbsolutePath().normalize(),
				resolved);
	}

	@Test
	void rejectsParentTraversalInBucketOrObjectKey(@TempDir Path tempDir) {
		var storage = new LocalCaptureSyncStorage(tempDir.toString());
		assertThrows(CaptureSyncExecutionException.class,
				() -> storage.resolve(new CaptureSyncStoragePort.ObjectReference("../outside", "file.parquet")));
		assertThrows(CaptureSyncExecutionException.class,
				() -> storage.resolve(new CaptureSyncStoragePort.ObjectReference("bucket", "../outside")));
	}

	@Test
	void rejectsAbsoluteBucketOrObjectKey(@TempDir Path tempDir) {
		var storage = new LocalCaptureSyncStorage(tempDir.toString());
		assertThrows(CaptureSyncExecutionException.class,
				() -> storage.resolve(new CaptureSyncStoragePort.ObjectReference(tempDir.toString(), "file.parquet")));
		assertThrows(CaptureSyncExecutionException.class, () -> storage
			.resolve(new CaptureSyncStoragePort.ObjectReference("bucket", tempDir + "\\file.parquet")));
	}

}
