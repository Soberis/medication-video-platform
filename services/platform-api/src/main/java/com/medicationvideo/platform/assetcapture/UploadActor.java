package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import com.medicationvideo.platform.iamproject.ProjectActor;

public record UploadActor(UUID projectId, String actorId) implements ProjectActor {
}
