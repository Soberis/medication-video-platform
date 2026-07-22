package com.medicationvideo.platform.iamproject;

import java.util.UUID;

/** Authenticated platform or service identity used at project-scoped boundaries. */
public record PlatformActor(UUID projectId, String actorId) implements ProjectActor {
}
