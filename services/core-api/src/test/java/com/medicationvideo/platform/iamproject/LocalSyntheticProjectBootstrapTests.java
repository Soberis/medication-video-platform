package com.medicationvideo.platform.iamproject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LocalSyntheticProjectBootstrapTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Test
	void seedsReservedProjectAndBothLocalIdentities() {
		var projects = mock(DataProjectRepository.class);
		var memberships = mock(ProjectMembershipRepository.class);
		when(projects.findById(PROJECT_ID)).thenReturn(Optional.empty());
		when(projects.save(any(DataProject.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(memberships.findByProjectIdAndActorId(PROJECT_ID, "local-synthetic-uploader"))
			.thenReturn(Optional.empty());
		when(memberships.findByProjectIdAndActorId(PROJECT_ID, "local-synthetic-admin")).thenReturn(Optional.empty());

		LocalSyntheticProjectBootstrap.bootstrap(projects, memberships, true, PROJECT_ID,
				"synthetic-local-bearer-token-do-not-reuse", "synthetic-local-admin-bearer-token-do-not-reuse");

		var project = ArgumentCaptor.forClass(DataProject.class);
		verify(projects).save(project.capture());
		assertEquals(PROJECT_ID, project.getValue().getProjectId());
		assertEquals("本地合成上传验证", project.getValue().getDisplayName());
		var membership = ArgumentCaptor.forClass(ProjectMembership.class);
		verify(memberships, org.mockito.Mockito.times(2)).save(membership.capture());
		assertEquals(java.util.Set.of("local-synthetic-uploader", "local-synthetic-admin"),
				membership.getAllValues()
					.stream()
					.map(ProjectMembership::getActorId)
					.collect(java.util.stream.Collectors.toSet()));
	}

	@Test
	void leavesRepositoriesUntouchedWhenTheFixtureIsDisabled() {
		var projects = mock(DataProjectRepository.class);
		var memberships = mock(ProjectMembershipRepository.class);

		LocalSyntheticProjectBootstrap.bootstrap(projects, memberships, false, PROJECT_ID,
				"synthetic-local-bearer-token-do-not-reuse", "synthetic-local-admin-bearer-token-do-not-reuse");

		verify(projects, never()).findById(any());
		verify(memberships, never()).findByProjectIdAndActorId(any(), any());
	}

}
