package com.medicationvideo.platform.iamproject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
	void platformAdministratorDoesNotNeedProjectMembership() {
		var projects = mock(DataProjectRepository.class);
		var memberships = mock(ProjectMembershipRepository.class);
		var project = mock(DataProject.class);
		when(projects.findByProjectIdAndStatus(PROJECT_ID, ProjectStatus.ACTIVE)).thenReturn(Optional.of(project));
		when(project.getStatus()).thenReturn(ProjectStatus.ACTIVE);
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(ACTOR_ID, null,
					List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
		var service = new ProjectAccessService(projects, memberships);

		assertEquals(ProjectRole.ADMIN, service.requireMember(PROJECT_ID, ACTOR_ID));
		assertEquals(ProjectRole.ADMIN, service.requireAdministrator(PROJECT_ID, ACTOR_ID));
	}

	@Test
	void explicitProjectMembershipTakesPrecedenceOverPlatformAdministratorFallback() {
		var projects = mock(DataProjectRepository.class);
		var memberships = mock(ProjectMembershipRepository.class);
		var project = mock(DataProject.class);
		var memberMembership = mock(ProjectMembership.class);
		when(memberMembership.isActive()).thenReturn(true);
		when(memberMembership.getRole()).thenReturn(ProjectRole.MEMBER);
		when(projects.findByProjectIdAndStatus(PROJECT_ID, ProjectStatus.ACTIVE)).thenReturn(Optional.of(project));
		when(project.getStatus()).thenReturn(ProjectStatus.ACTIVE);
		when(memberships.findByProjectIdAndActorId(PROJECT_ID, ACTOR_ID)).thenReturn(Optional.of(memberMembership));

		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(ACTOR_ID, null,
					List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
		var service = new ProjectAccessService(projects, memberships);

		assertEquals(ProjectRole.MEMBER, service.requireMember(PROJECT_ID, ACTOR_ID));
		var exception = assertThrows(ProjectAccessException.class,
				() -> service.requireAdministrator(PROJECT_ID, ACTOR_ID));
		assertEquals("PROJECT_ACCESS_DENIED", exception.getErrorCode());
	}

	@Test
	void removedProjectMembershipBlocksAccessEvenForPlatformAdministrator() {
		var projects = mock(DataProjectRepository.class);
		var memberships = mock(ProjectMembershipRepository.class);
		var project = mock(DataProject.class);
		var removedMembership = mock(ProjectMembership.class);
		when(removedMembership.isActive()).thenReturn(false);
		when(projects.findByProjectIdAndStatus(PROJECT_ID, ProjectStatus.ACTIVE)).thenReturn(Optional.of(project));
		when(project.getStatus()).thenReturn(ProjectStatus.ACTIVE);
		when(memberships.findByProjectIdAndActorId(PROJECT_ID, ACTOR_ID)).thenReturn(Optional.of(removedMembership));

		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(ACTOR_ID, null,
					List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
		var service = new ProjectAccessService(projects, memberships);

		var exception = assertThrows(ProjectAccessException.class, () -> service.requireMember(PROJECT_ID, ACTOR_ID));
		assertEquals("PROJECT_NOT_FOUND", exception.getErrorCode());
	}

	@Test
	void platformAdministratorBypassAppliesOnlyToTheAuthenticatedActor() {
		var projects = mock(DataProjectRepository.class);
		var memberships = mock(ProjectMembershipRepository.class);
		when(projects.findByProjectIdAndStatus(PROJECT_ID, ProjectStatus.ACTIVE))
			.thenReturn(Optional.of(mock(DataProject.class)));
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken(ACTOR_ID, null,
					List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
		var service = new ProjectAccessService(projects, memberships);

		var exception = assertThrows(ProjectAccessException.class,
				() -> service.requireMember(PROJECT_ID, "another.actor"));

		assertEquals("PROJECT_NOT_FOUND", exception.getErrorCode());
	}

	@Test
	void localSyntheticOperatorDoesNotNeedProjectMembership() {
		var projects = mock(DataProjectRepository.class);
		var memberships = mock(ProjectMembershipRepository.class);
		when(projects.findByProjectIdAndStatus(PROJECT_ID, ProjectStatus.ACTIVE))
			.thenReturn(Optional.of(mock(DataProject.class)));
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken("local-synthetic-uploader", null, List.of()));
		var service = new ProjectAccessService(projects, memberships, true);

		assertEquals(ProjectRole.ADMIN, service.requireAdministrator(PROJECT_ID, "local-synthetic-uploader"));
	}

}
