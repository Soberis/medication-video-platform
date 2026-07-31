package com.medicationvideo.platform.assetcapture;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClipAssetExportReadService {

	private final ClipAssetRepository repository;

	private final MultipartObjectStorage objectStorage;

	ClipAssetExportReadService(ClipAssetRepository repository, MultipartObjectStorage objectStorage) {
		this.repository = repository;
		this.objectStorage = objectStorage;
	}

	@Transactional(readOnly = true)
	public List<ClipAssetExportReadGrant> authorize(UUID projectId, Collection<ClipAssetExportReference> references,
			Duration lifetime, Instant authorizedAt) {
		return validatedAssets(projectId, references).stream()
			.map(asset -> grant(asset, lifetime, authorizedAt))
			.toList();
	}

	@Transactional(readOnly = true)
	public List<ClipAssetExportSummary> describe(UUID projectId, Collection<ClipAssetExportReference> references) {
		return validatedAssets(projectId, references).stream()
			.map(asset -> new ClipAssetExportSummary(asset.getClipAssetId(), asset.getContentType(),
					asset.getSizeBytes(), asset.getSha256()))
			.toList();
	}

	private List<ClipAsset> validatedAssets(UUID projectId, Collection<ClipAssetExportReference> references) {
		if (references.isEmpty()) {
			return List.of();
		}
		var ordered = List.copyOf(references);
		var assets = this.repository
			.findAllByClipAssetIdInAndProjectId(ordered.stream().map(ClipAssetExportReference::clipAssetId).toList(),
					projectId)
			.stream()
			.collect(Collectors.toMap(ClipAsset::getClipAssetId, Function.identity(), (first, second) -> first,
					LinkedHashMap::new));
		return ordered.stream().map(reference -> validate(assets.get(reference.clipAssetId()), reference)).toList();
	}

	private static ClipAsset validate(ClipAsset asset, ClipAssetExportReference reference) {
		if (asset == null || asset.isDeleted() || asset.getStatus() != ClipAssetStatus.INGESTED
				|| !asset.getSha256().equals(reference.sha256())) {
			throw new AssetCatalogQueryException("CLIP_ASSET_EXPORT_EVIDENCE_CHANGED",
					"A frozen dataset input is no longer available with the approved evidence.");
		}
		return asset;
	}

	private ClipAssetExportReadGrant grant(ClipAsset asset, Duration lifetime, Instant authorizedAt) {
		try {
			return new ClipAssetExportReadGrant(asset.getClipAssetId(), asset.getContentType(), asset.getSizeBytes(),
					asset.getSha256(), this.objectStorage.authorizeRead(asset.getObjectReference(), lifetime),
					authorizedAt.plus(lifetime));
		}
		catch (MultipartObjectStorageException exception) {
			throw new AssetCatalogQueryException("OBJECT_STORAGE_UNAVAILABLE",
					"Object storage could not authorize a frozen dataset input.");
		}
	}

	public record ClipAssetExportReference(UUID clipAssetId, String sha256) {
	}

	public record ClipAssetExportReadGrant(UUID clipAssetId, String contentType, long sizeBytes, String sha256,
			URI inputUrl, Instant expiresAt) {
	}

	public record ClipAssetExportSummary(UUID clipAssetId, String contentType, long sizeBytes, String sha256) {
	}

}
