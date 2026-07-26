package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project-scoped, read-only capture facts for the dataset admission preview.
 */
@Service
public class CaptureSessionDatasetAdmissionQueryService {

    private final CaptureSessionRepository sessionRepository;
    private final CameraStreamRepository streamRepository;
    private final ClipAssetRepository clipAssetRepository;

    CaptureSessionDatasetAdmissionQueryService(
            CaptureSessionRepository sessionRepository,
            CameraStreamRepository streamRepository,
            ClipAssetRepository clipAssetRepository) {
        this.sessionRepository = sessionRepository;
        this.streamRepository = streamRepository;
        this.clipAssetRepository = clipAssetRepository;
    }

    @Transactional(readOnly = true)
    public CaptureSessionDatasetAdmissionFacts getFacts(UUID projectId, UUID captureSessionId) {
        var session = this.sessionRepository.findByCaptureSessionIdAndProjectId(captureSessionId, projectId)
                .orElseThrow(() -> new AssetCatalogQueryException(
                        "CAPTURE_SESSION_NOT_FOUND",
                        "The capture session was not found in the current project."));
        var streams = this.streamRepository.findAllByCaptureSessionIdOrderByCameraIndexAsc(captureSessionId);
        var assetsById = streams.isEmpty()
                ? Map.<UUID, ClipAsset>of()
                : this.clipAssetRepository.findAllByClipAssetIdInAndProjectId(
                                streams.stream().map(CameraStream::getClipAssetId).toList(), projectId)
                        .stream()
                        .collect(Collectors.toMap(ClipAsset::getClipAssetId, Function.identity()));
        return new CaptureSessionDatasetAdmissionFacts(
                session.getCaptureSessionId(),
                session.getExpectedCameraCount(),
                session.getReceivedCameraCount(),
                session.getAssemblyStatus().name(),
                session.getQualityStatus().name(),
                streams.stream().map(stream -> streamFacts(stream, assetsById)).toList());
    }

    private static CaptureSessionDatasetAdmissionFacts.Stream streamFacts(
            CameraStream stream, Map<UUID, ClipAsset> assetsById) {
        var asset = assetsById.get(stream.getClipAssetId());
        if (asset == null) {
            return new CaptureSessionDatasetAdmissionFacts.Stream(
                    stream.getCameraStreamId(), stream.getClipAssetId(), stream.getCameraIndex(),
                    stream.getStreamStatus().name(), stream.getQualityStatus().name(),
                    "MISSING", false, null);
        }
        return new CaptureSessionDatasetAdmissionFacts.Stream(
                stream.getCameraStreamId(), asset.getClipAssetId(), stream.getCameraIndex(),
                stream.getStreamStatus().name(), stream.getQualityStatus().name(),
                asset.getStatus().name(), asset.isDeleted(), asset.getSha256());
    }
}
