package com.medicationvideo.platform.iamproject;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Supplies the reserved project and identities used only by the local synthetic upload
 * client. Production profiles never load this configuration.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
class LocalSyntheticProjectBootstrap {

	private static final Logger LOGGER = LoggerFactory.getLogger(LocalSyntheticProjectBootstrap.class);

	private static final String BOOTSTRAP_ACTOR = "local-synthetic-bootstrap";

	private static final String PROJECT_NAME = "本地合成上传验证";

	private static final String PROJECT_IDEMPOTENCY_KEY = "local-synthetic-project-v1";

	private static final String PROJECT_FINGERPRINT = "6c6312b23bc7d8468e6abd0dc4b3094f5b5e2529101d8403f67e90285bd92d5f";

	private static final String UPLOADER_ACTOR = "local-synthetic-uploader";

	private static final String ADMIN_ACTOR = "local-synthetic-admin";

	@Bean
	ApplicationRunner bootstrapLocalSyntheticProject(DataProjectRepository projects,
			ProjectMembershipRepository memberships,
			@Value("${platform.local-synthetic-project.enabled:true}") boolean enabled,
			@Value("${platform.security.local-project-id}") UUID projectId,
			@Value("${platform.security.local-bearer-token:}") String uploaderToken,
			@Value("${platform.security.local-admin-bearer-token:}") String adminToken) {
		return arguments -> bootstrap(projects, memberships, enabled, projectId, uploaderToken, adminToken);
	}

	static void bootstrap(DataProjectRepository projects, ProjectMembershipRepository memberships, boolean enabled,
			UUID projectId, String uploaderToken, String adminToken) {
		if (!enabled || (uploaderToken.isBlank() && adminToken.isBlank())) {
			return;
		}

		var project = projects.findById(projectId)
			.orElseGet(() -> projects.save(DataProject.create(projectId, PROJECT_NAME, BOOTSTRAP_ACTOR,
					PROJECT_IDEMPOTENCY_KEY, PROJECT_FINGERPRINT, Instant.now())));
		if (project.getStatus() != ProjectStatus.ACTIVE) {
			LOGGER.warn("Local synthetic project {} is archived; skip synthetic memberships.", projectId);
			return;
		}
		if (!uploaderToken.isBlank()) {
			ensureMembership(memberships, projectId, UPLOADER_ACTOR, ProjectRole.MEMBER);
		}
		if (!adminToken.isBlank()) {
			ensureMembership(memberships, projectId, ADMIN_ACTOR, ProjectRole.ADMIN);
		}
	}

	private static void ensureMembership(ProjectMembershipRepository memberships, UUID projectId, String actorId,
			ProjectRole role) {
		var now = Instant.now();
		var membership = memberships.findByProjectIdAndActorId(projectId, actorId)
			.orElseGet(() -> ProjectMembership.grant(projectId, actorId, role, BOOTSTRAP_ACTOR, now));
		membership.activate(role, BOOTSTRAP_ACTOR, now);
		memberships.save(membership);
	}

}
