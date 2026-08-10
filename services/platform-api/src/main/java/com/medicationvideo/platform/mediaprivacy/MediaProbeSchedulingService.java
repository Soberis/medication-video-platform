package com.medicationvideo.platform.mediaprivacy;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public cross-module application service for scheduling the frozen media probe after
 * upload integrity has completed. It deliberately exposes neither job internals nor
 * broker details.
 */
@Service
public class MediaProbeSchedulingService {

	private final MediaJobService mediaJobService;

	private final boolean autoOnUploadCompletion;

	MediaProbeSchedulingService(MediaJobService mediaJobService,
			@Value("${platform.media-probe.auto-on-upload-completion:true}") boolean autoOnUploadCompletion) {
		this.mediaJobService = mediaJobService;
		this.autoOnUploadCompletion = autoOnUploadCompletion;
	}

	@Transactional
	public void ensureForUploadedClip(UUID projectId, UUID clipAssetId) {
		if (!this.autoOnUploadCompletion) {
			return;
		}
		this.mediaJobService.create(projectId, "auto-media-probe-" + clipAssetId,
				new MediaJobRequest(clipAssetId, MediaJobType.MEDIA_PROBE));
	}

}
