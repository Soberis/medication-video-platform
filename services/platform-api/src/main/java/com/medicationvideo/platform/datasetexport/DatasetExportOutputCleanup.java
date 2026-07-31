package com.medicationvideo.platform.datasetexport;

import org.springframework.stereotype.Component;

@Component
class DatasetExportOutputCleanup {

	private final DatasetExportStoragePort storage;

	DatasetExportOutputCleanup(DatasetExportStoragePort storage) {
		this.storage = storage;
	}

	void discard(DatasetExportAttempt attempt) {
		var upload = attempt.getUpload();
		if (upload == null) {
			return;
		}
		var object = new DatasetExportStoragePort.ObjectReference(upload.bucket(), upload.objectKey());
		if (this.storage.findCompletedObject(upload).isPresent()) {
			this.storage.delete(object);
			return;
		}
		this.storage.abort(upload);
		if (this.storage.findCompletedObject(upload).isPresent()) {
			this.storage.delete(object);
		}
	}

}
