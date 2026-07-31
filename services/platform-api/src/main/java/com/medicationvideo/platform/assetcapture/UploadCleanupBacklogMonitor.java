package com.medicationvideo.platform.assetcapture;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
@ConditionalOnProperty(name = "platform.upload.cleanup-alert.enabled", havingValue = "true", matchIfMissing = true)
class UploadCleanupBacklogMonitor {

	private static final Logger log = LoggerFactory.getLogger(UploadCleanupBacklogMonitor.class);

	private static final String RUNBOOK = "docs/runbooks/upload-abort-and-expiry-cleanup.md";

	private final UploadCleanupBacklogStore store;

	private final Duration pendingAgeThreshold;

	private final AtomicReference<UploadCleanupBacklogSnapshot> current = new AtomicReference<>(
			UploadCleanupBacklogSnapshot.empty());

	private final AtomicReference<UploadCleanupAlertSeverity> lastSeverity = new AtomicReference<>(
			UploadCleanupAlertSeverity.NONE);

	private final Map<UploadCleanupAlertSeverity, Counter> transitionCounters;

	UploadCleanupBacklogMonitor(UploadCleanupBacklogStore store, MeterRegistry registry,
			@Value("${platform.upload.cleanup-alert.pending-age:PT5M}") Duration pendingAgeThreshold) {
		if (pendingAgeThreshold.isZero() || pendingAgeThreshold.isNegative()) {
			throw new IllegalArgumentException("Upload cleanup pending-age alert threshold must be positive.");
		}
		this.store = store;
		this.pendingAgeThreshold = pendingAgeThreshold;
		registerTaskGauge(registry, "pending", UploadCleanupBacklogSnapshot::pendingTasks);
		registerTaskGauge(registry, "running", UploadCleanupBacklogSnapshot::runningTasks);
		registerTaskGauge(registry, "failed", UploadCleanupBacklogSnapshot::failedTasks);
		Gauge
			.builder("platform.upload.cleanup.oldest.actionable.age", this.current,
					reference -> reference.get().oldestActionableAgeSeconds())
			.description("Age of the oldest PENDING or RUNNING upload cleanup task")
			.baseUnit("seconds")
			.register(registry);
		this.transitionCounters = Map.of(UploadCleanupAlertSeverity.NONE, transitionCounter(registry, "none"),
				UploadCleanupAlertSeverity.WARNING, transitionCounter(registry, "warning"),
				UploadCleanupAlertSeverity.CRITICAL, transitionCounter(registry, "critical"));
	}

	@EventListener(ApplicationReadyEvent.class)
	void initialize() {
		evaluate(Instant.now());
	}

	@Scheduled(fixedDelayString = "${platform.upload.cleanup-alert.poll-delay:PT30S}")
	void poll() {
		evaluate(Instant.now());
	}

	UploadCleanupAlertSeverity evaluate(Instant observedAt) {
		var snapshot = this.store.snapshot(observedAt);
		this.current.set(snapshot);
		var severity = severity(snapshot);
		var previous = this.lastSeverity.getAndSet(severity);
		if (severity != previous) {
			this.transitionCounters.get(severity).increment();
			logTransition(previous, severity, snapshot);
		}
		return severity;
	}

	private UploadCleanupAlertSeverity severity(UploadCleanupBacklogSnapshot snapshot) {
		if (snapshot.failedTasks() > 0) {
			return UploadCleanupAlertSeverity.CRITICAL;
		}
		if (snapshot.actionableTasks() > 0
				&& snapshot.oldestActionableAgeSeconds() >= this.pendingAgeThreshold.toSeconds()) {
			return UploadCleanupAlertSeverity.WARNING;
		}
		return UploadCleanupAlertSeverity.NONE;
	}

	private void logTransition(UploadCleanupAlertSeverity previous, UploadCleanupAlertSeverity severity,
			UploadCleanupBacklogSnapshot snapshot) {
		if (severity == UploadCleanupAlertSeverity.CRITICAL) {
			log.error(
					"Upload cleanup alert CRITICAL: failed_tasks={}, actionable_tasks={}, "
							+ "oldest_actionable_age_seconds={}, owner=platform-operations, runbook={}.",
					snapshot.failedTasks(), snapshot.actionableTasks(), snapshot.oldestActionableAgeSeconds(), RUNBOOK);
		}
		else if (severity == UploadCleanupAlertSeverity.WARNING) {
			log.warn(
					"Upload cleanup alert WARNING: actionable_tasks={}, oldest_actionable_age_seconds={}, "
							+ "owner=platform-operations, runbook={}.",
					snapshot.actionableTasks(), snapshot.oldestActionableAgeSeconds(), RUNBOOK);
		}
		else {
			log.info(
					"Upload cleanup alert recovered from {}: actionable_tasks={}, failed_tasks={}, "
							+ "owner=platform-operations, runbook={}.",
					previous, snapshot.actionableTasks(), snapshot.failedTasks(), RUNBOOK);
		}
	}

	private void registerTaskGauge(MeterRegistry registry, String status,
			java.util.function.ToDoubleFunction<UploadCleanupBacklogSnapshot> value) {
		Gauge.builder("platform.upload.cleanup.tasks", this.current, reference -> value.applyAsDouble(reference.get()))
			.description("Durable upload cleanup tasks by low-cardinality status")
			.tag("status", status)
			.register(registry);
	}

	private static Counter transitionCounter(MeterRegistry registry, String severity) {
		return Counter.builder("platform.upload.cleanup.alert.transitions")
			.description("Upload cleanup alert state transitions")
			.tag("severity", severity)
			.register(registry);
	}

}
