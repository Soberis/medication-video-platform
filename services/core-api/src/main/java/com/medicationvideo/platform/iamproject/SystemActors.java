package com.medicationvideo.platform.iamproject;

/**
 * Internal, non-human actors used for trusted in-process background work.
 *
 * <p>
 * These identifiers are never provisioned as platform accounts and therefore can never
 * authenticate through the HTTP API; they are only passed between application services
 * for automated system processing (for example V1.36 optimistic auto-approval). Access
 * checks still require the target project to be ACTIVE.
 */
public final class SystemActors {

	/** Actor and reviewer recorded for automatic optimistic approval (V1.36). */
	public static final String AUTO_SYSTEM = "AUTO_SYSTEM";

	private SystemActors() {
	}

	public static boolean isSystemActor(String actorId) {
		return AUTO_SYSTEM.equals(actorId);
	}

}
