package com.medicationvideo.platform.assetcapture;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AssetFolderRepository extends JpaRepository<AssetFolder, UUID> {

	Optional<AssetFolder> findByFolderIdAndProjectId(UUID folderId, UUID projectId);

}
