package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DatasetExportResultInboxPort {

	Optional<DatasetExportInboxReceipt> find(UUID messageId);

	DatasetExportInboxReceipt record(
			UUID messageId,
			UUID exportId,
			int attempt,
			String resultFingerprint,
			String resultStatus,
			Instant processedAt);
}
