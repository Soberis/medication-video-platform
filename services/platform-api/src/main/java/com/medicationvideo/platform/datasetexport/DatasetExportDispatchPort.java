package com.medicationvideo.platform.datasetexport;

import java.time.Instant;

public interface DatasetExportDispatchPort {

	void enqueue(DatasetExportJobEnvelope envelope, Instant availableAt);
}
