package com.medicationvideo.platform.iamproject;

import java.util.UUID;

/**
 * Authenticated user identity exposed to project-scoped application boundaries.
 */
public interface ProjectActor {

	UUID projectId();

	String actorId();
}
