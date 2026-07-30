package com.medicationvideo.platform.datasetexport;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class DatasetExportOutputCleanupTests {

	@Test
	void deletesACompletedOutputWithoutTryingToAbortIt() {
		var storage = mock(DatasetExportStoragePort.class);
		var cleanup = new DatasetExportOutputCleanup(storage);
		var attempt = DatasetExportRecoveryTestFixtures.runningAttempt(1);
		var upload = DatasetExportRecoveryTestFixtures.attachUpload(attempt);
		var object = new DatasetExportStoragePort.ObjectReference(
				upload.bucket(), upload.objectKey());
		when(storage.findCompletedObject(upload))
				.thenReturn(Optional.of(new DatasetExportStoragePort.CompletedObject(42, "etag")));

		cleanup.discard(attempt);

		verify(storage).delete(object);
		verify(storage, never()).abort(upload);
	}

	@Test
	void abortsMultipartOutputAndDeletesAnObjectCompletedDuringTheRace() {
		var storage = mock(DatasetExportStoragePort.class);
		var cleanup = new DatasetExportOutputCleanup(storage);
		var attempt = DatasetExportRecoveryTestFixtures.runningAttempt(1);
		var upload = DatasetExportRecoveryTestFixtures.attachUpload(attempt);
		var object = new DatasetExportStoragePort.ObjectReference(
				upload.bucket(), upload.objectKey());
		when(storage.findCompletedObject(upload))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(
						new DatasetExportStoragePort.CompletedObject(42, "etag")));

		cleanup.discard(attempt);

		var order = inOrder(storage);
		order.verify(storage).findCompletedObject(upload);
		order.verify(storage).abort(upload);
		order.verify(storage).findCompletedObject(upload);
		order.verify(storage).delete(object);
	}

	@Test
	void doesNothingBeforeAnOutputUploadExists() {
		var storage = mock(DatasetExportStoragePort.class);
		var cleanup = new DatasetExportOutputCleanup(storage);

		cleanup.discard(DatasetExportRecoveryTestFixtures.runningAttempt(1));

		verifyNoInteractions(storage);
	}
}
