package com.medicationvideo.platform.datasetexport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.medicationvideo.platform.datasetexport.HumanDemonstrationHdf5ExportService.ExportOptions;

@Service
class HumanDemonstrationHdf5ExportTaskManager {

	private static final Logger log = LoggerFactory.getLogger(HumanDemonstrationHdf5ExportTaskManager.class);

	private final HumanDemonstrationHdf5ExportService exportService;

	private final ExecutorService executor = Executors.newCachedThreadPool();

	private final Map<UUID, ExportTaskRecord> tasks = new ConcurrentHashMap<>();

	HumanDemonstrationHdf5ExportTaskManager(HumanDemonstrationHdf5ExportService exportService) {
		this.exportService = exportService;
	}

	ExportTaskRecord submit(UUID projectId, List<UUID> captureGroupIds, ExportOptions options) {
		var taskId = UUID.randomUUID();
		var now = Instant.now();
		var task = new ExportTaskRecord(taskId, projectId, TaskStatus.PROCESSING, 0, 0, 0, "正在准备数据集元数据...", null, 0L,
				null, null, now, null, now.plus(2, ChronoUnit.HOURS));
		this.tasks.put(taskId, task);

		this.executor.submit(() -> {
			try {
				var file = this.exportService.download(projectId, captureGroupIds, options, (current, total, label) -> {
					var currentTask = this.tasks.get(taskId);
					if (currentTask != null && currentTask.status() == TaskStatus.PROCESSING) {
						var percent = total > 0 ? (int) Math.round(((double) current / total) * 100.0) : 0;
						this.tasks.put(taskId, currentTask.withProgress(current, total, percent, label));
					}
				});
				var fileSize = Files.size(file);
				var fileName = HumanDemonstrationHdf5ExportService.fileName(captureGroupIds, Instant.now());
				var finishedTask = this.tasks.get(taskId);
				if (finishedTask != null) {
					this.tasks.put(taskId, finishedTask.withCompleted(file, fileSize, fileName));
				}
			}
			catch (Exception ex) {
				log.error("HDF5 export task {} failed", taskId, ex);
				var failedTask = this.tasks.get(taskId);
				if (failedTask != null) {
					var message = ex instanceof TrainingDataExportException t ? t.getMessage() : "导出处理失败，请稍后重试。";
					this.tasks.put(taskId, failedTask.withFailed(message));
				}
			}
		});

		return task;
	}

	ExportTaskRecord get(UUID taskId) {
		return this.tasks.get(taskId);
	}

	boolean cancel(UUID taskId) {
		var task = this.tasks.remove(taskId);
		if (task != null && task.outputFile() != null) {
			try {
				Files.deleteIfExists(task.outputFile());
			}
			catch (IOException ignored) {
			}
			return true;
		}
		return false;
	}

	@Scheduled(fixedDelay = 600000)
	void cleanupExpiredTasks() {
		var now = Instant.now();
		this.tasks.entrySet().removeIf(entry -> {
			var task = entry.getValue();
			if (task.expiresAt() != null && task.expiresAt().isBefore(now)) {
				if (task.outputFile() != null) {
					try {
						Files.deleteIfExists(task.outputFile());
					}
					catch (IOException ignored) {
					}
				}
				return true;
			}
			return false;
		});
	}

	enum TaskStatus {

		PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED

	}

	record ExportTaskRecord(UUID taskId, UUID projectId, TaskStatus status, int current, int total, int percent,
			String currentLabel, Path outputFile, long fileSizeBytes, String fileName, String errorMessage,
			Instant createdAt, Instant completedAt, Instant expiresAt) {

		ExportTaskRecord withProgress(int current, int total, int percent, String label) {
			return new ExportTaskRecord(this.taskId, this.projectId, this.status, current, total, percent, label,
					this.outputFile, this.fileSizeBytes, this.fileName, this.errorMessage, this.createdAt,
					this.completedAt, this.expiresAt);
		}

		ExportTaskRecord withCompleted(Path file, long size, String name) {
			return new ExportTaskRecord(this.taskId, this.projectId, TaskStatus.COMPLETED, this.total, this.total, 100,
					"导出已完成", file, size, name, null, this.createdAt, Instant.now(), this.expiresAt);
		}

		ExportTaskRecord withFailed(String message) {
			return new ExportTaskRecord(this.taskId, this.projectId, TaskStatus.FAILED, this.current, this.total,
					this.percent, this.currentLabel, null, 0L, null, message, this.createdAt, Instant.now(),
					this.expiresAt);
		}
	}

}
