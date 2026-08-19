package com.medicationvideo.platform.iamproject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ProjectAccessServiceTests {

	private static final UUID PROJECT_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");

	private static final String ACTOR_ID = "training.admin";

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void requiresPlatformAdministratorBeforeReadingProjectMembership() {
		var projects = mock(DataProjectRepository.class);
		var memberships = mock(ProjectMembershipRepository.class);
		var service = new ProjectAccessService(projects, memberships);
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(ACTOR_ID, null, List.of()));

		var exception = assertThrows(ProjectAccessException.class,
				() -> service.requireAdministrator(PROJECT_ID, ACTOR_ID));

		assertEquals("PROJECT_ACCESS_DENIED", exception.getErrorCode());
	}

	@Test
	void requiresActiveProjectAdministratorMembership() {
		var projects = mock(DataProjectRepository.class);
		var memberships = mock(ProjectMembershipRepository.class);
		var project = mock(DataProject.class);
		var membership = mock(ProjectMembership.class);
		when(projects.findByProjectIdAndStatus(PROJECT_ID, ProjectStatus.ACTIVE)).thenReturn(Optional.of(project));
		when(project.getStatus()).thenReturn(ProjectStatus.ACTIVE);
		when(memberships.findByProjectIdAndActorId(PROJECT_ID, ACTOR_ID)).thenReturn(Optional.of(membership));
		when(membership.isActive()).thenReturn(true);
		when(membership.getRole()).thenReturn(ProjectRole.MEMBER);
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(ACTOR_ID, null,
					List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
		var service = new ProjectAccessService(projects, memberships);

		var exception = assertThrows(ProjectAccessException.class,
				() -> service.requireAdministrator(PROJECT_ID, ACTOR_ID));

		assertEquals("PROJECT_ACCESS_DENIED", exception.getErrorCode());
	}

}
