package com.medicationvideo.platform.integration;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.medicationvideo.platform.assetcapture.MultipartObjectStorage;
import com.medicationvideo.platform.datasetexport.DatasetExportStoragePort;

@Component
class S3DatasetExportStorage implements DatasetExportStoragePort {

	private final MultipartObjectStorage storage;

	S3DatasetExportStorage(MultipartObjectStorage storage) {
		this.storage = storage;
	}

	@Override
	public CreatedUpload create(String objectKey, String contentType) {
		return from(this.storage.create(objectKey, contentType));
	}

	@Override
	public URI authorizePart(CreatedUpload upload, int partNumber, Duration lifetime) {
		return this.storage.authorizePart(to(upload), partNumber, lifetime);
	}

	@Override
	public List<StoredPart> listParts(CreatedUpload upload) {
		return this.storage.listParts(to(upload)).stream()
				.map(part -> new StoredPart(part.partNumber(), part.sizeBytes(), part.eTag()))
				.toList();
	}

	@Override
	public Optional<CompletedObject> findCompletedObject(CreatedUpload upload) {
		return this.storage.findCompletedObject(to(upload))
				.map(value -> new CompletedObject(value.sizeBytes(), value.eTag()));
	}

	@Override
	public CompletedObject complete(CreatedUpload upload, List<StoredPart> parts) {
		var completed = this.storage.complete(
				to(upload),
				parts.stream()
						.map(part -> new MultipartObjectStorage.StoredPart(
								part.partNumber(), part.sizeBytes(), part.eTag()))
						.toList());
		return new CompletedObject(completed.sizeBytes(), completed.eTag());
	}

	@Override
	public String calculateSha256(CreatedUpload upload) {
		return this.storage.calculateSha256(to(upload));
	}

	@Override
	public URI authorizeRead(ObjectReference object, Duration lifetime) {
		return this.storage.authorizeRead(
				new MultipartObjectStorage.ObjectReference(object.bucket(), object.objectKey()),
				lifetime);
	}

	@Override
	public void abort(CreatedUpload upload) {
		this.storage.abort(to(upload));
	}

	@Override
	public void delete(ObjectReference object) {
		this.storage.delete(new MultipartObjectStorage.ObjectReference(
				object.bucket(), object.objectKey()));
	}

	private static CreatedUpload from(MultipartObjectStorage.CreatedUpload upload) {
		return new CreatedUpload(upload.bucket(), upload.objectKey(), upload.uploadId());
	}

	private static MultipartObjectStorage.CreatedUpload to(CreatedUpload upload) {
		return new MultipartObjectStorage.CreatedUpload(
				upload.bucket(), upload.objectKey(), upload.uploadId());
	}
}
