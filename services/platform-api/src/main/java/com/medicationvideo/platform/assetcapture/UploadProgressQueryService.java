package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class UploadProgressQueryService {

	private final UploadBatchRepository batchRepository;
	private final UploadFileRepository fileRepository;
	private final UploadPartRepository partRepository;
	private final UploadCleanupTaskStore cleanupStore;

	UploadProgressQueryService(
			UploadBatchRepository batchRepository,
			UploadFileRepository fileRepository,
			UploadPartRepository partRepository,
			UploadCleanupTaskStore cleanupStore) {
		this.batchRepository = batchRepository;
		this.fileRepository = fileRepository;
		this.partRepository = partRepository;
		this.cleanupStore = cleanupStore;
	}

	UploadBatchProgressResponse getBatch(UUID batchId, UploadActor actor) {
		var snapshot = load(batchId, actor);
		return UploadBatchProgressResponse.from(
				snapshot.batch(),
				snapshot.files(),
				snapshot.partProgress().values(),
				this.cleanupStore.summarize(batchId));
	}

	UploadFileProgressListResponse getFiles(UUID batchId, UploadActor actor) {
		var snapshot = load(batchId, actor);
		var responses = snapshot.files().stream()
				.map(file -> UploadFileProgressResponse.from(
						file,
						snapshot.partProgress().get(file.getUploadFileId())))
				.toList();
		return new UploadFileProgressListResponse(batchId, responses.size(), responses);
	}

	private ProgressSnapshot load(UUID batchId, UploadActor actor) {
		var batch = this.batchRepository.findByBatchIdAndProjectId(batchId, actor.projectId())
				.orElseThrow(UploadBatchNotFoundException::new);
		var files = this.fileRepository.findAllByBatchIdOrderByCreatedAtAscUploadFileIdAsc(batchId);
		if (files.isEmpty()) {
			return new ProgressSnapshot(batch, files, Map.of());
		}
		var uploadFileIds = files.stream().map(UploadFile::getUploadFileId).toList();
		var partProgress = this.partRepository.summarizeByUploadFileIds(uploadFileIds).stream()
				.collect(Collectors.toUnmodifiableMap(
						UploadPartProgressSummary::getUploadFileId,
						Function.identity()));
		return new ProgressSnapshot(batch, files, partProgress);
	}

	private record ProgressSnapshot(
			UploadBatch batch,
			List<UploadFile> files,
			Map<UUID, UploadPartProgressSummary> partProgress) {
	}
}
