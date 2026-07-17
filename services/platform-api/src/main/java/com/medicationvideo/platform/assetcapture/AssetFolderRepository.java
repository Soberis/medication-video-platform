package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AssetFolderRepository extends JpaRepository<AssetFolder, UUID> {

	Optional<AssetFolder> findByFolderIdAndProjectId(UUID folderId, UUID projectId);

	List<AssetFolder> findAllByProjectIdOrderByDisplayNameAscFolderIdAsc(UUID projectId);

	boolean existsByProjectIdAndDisplayNameIgnoreCase(UUID projectId, String displayName);

	boolean existsByProjectIdAndDisplayNameIgnoreCaseAndFolderIdNot(
			UUID projectId, String displayName, UUID folderId);
}
