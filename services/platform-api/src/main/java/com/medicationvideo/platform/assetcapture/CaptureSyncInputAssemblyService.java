package com.medicationvideo.platform.assetcapture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CaptureSyncInputAssemblyService {

	private final CaptureSessionRepository sessionRepository;

	private final CameraSetSnapshotRepository snapshotRepository;

	private final CameraSetSnapshotMemberRepository snapshotMemberRepository;

	private final CameraStreamRepository streamRepository;

	private final JdbcTemplate jdbcTemplate;

	CaptureSyncInputAssemblyService(CaptureSessionRepository sessionRepository,
			CameraSetSnapshotRepository snapshotRepository, CameraSetSnapshotMemberRepository snapshotMemberRepository,
			CameraStreamRepository streamRepository, JdbcTemplate jdbcTemplate) {
		this.sessionRepository = sessionRepository;
		this.snapshotRepository = snapshotRepository;
		this.snapshotMemberRepository = snapshotMemberRepository;
		this.streamRepository = streamRepository;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(readOnly = true)
	CaptureSyncInput assemble(UUID projectId, UUID captureSessionId) {
		var session = this.sessionRepository.findByCaptureSessionIdAndProjectId(captureSessionId, projectId)
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_SESSION_NOT_FOUND",
					"The requested capture session was not found."));
		var snapshot = this.snapshotRepository.findByCaptureSessionId(captureSessionId)
			.orElseThrow(() -> new AssetCatalogQueryException("CAPTURE_SYNC_INPUT_NOT_READY",
					"The capture session has no frozen camera snapshot."));
		var members = this.snapshotMemberRepository
			.findAllByCameraSetSnapshotIdOrderByCameraIndexAsc(snapshot.getCameraSetSnapshotId());
		if (members.isEmpty()) {
			throw new AssetCatalogQueryException("CAPTURE_SYNC_INPUT_NOT_READY",
					"The capture session snapshot has no expected cameras.");
		}
		var expectedCameras = members.stream()
			.map(member -> new ExpectedCamera(member.getCameraSetSnapshotMemberId(), member.getCameraIndex() + 1))
			.toList();
		var streams = this.streamRepository.findAllByCaptureSessionIdOrderByCameraIndexAsc(captureSessionId);
		var syncStreams = new ArrayList<SyncStream>();
		for (var member : members) {
			var cameraNumber = member.getCameraIndex() + 1;
			var stream = streams.stream()
				.filter(candidate -> candidate.getCameraIndex() == member.getCameraIndex())
				.findFirst();
			if (stream.isEmpty()) {
				continue;
			}
			var cameraStream = stream.get();
			var mediaReport = loadMediaReport(projectId, cameraStream.getClipAssetId());
			var sourceFrames = synthesizeSourceFrames(mediaReport);
			syncStreams
				.add(new SyncStream(cameraNumber, cameraStream.getCameraStreamId(), cameraStream.getClipAssetId(),
						mediaReport.inputDigest(), mediaReport.mediaReportId(), mediaReport.timeBaseDigest(),
						StreamTimeMapping.offsetOnly(new SyncAnchor(sourceFrames.getFirst().sourcePtsNs(), 0),
								"MEDIA_REPORT", "SYNTHETIC_TEST"),
						sourceFrames));
		}
		if (syncStreams.isEmpty()) {
			throw new AssetCatalogQueryException("CAPTURE_SYNC_INPUT_NOT_READY",
					"No ingested camera streams with media reports are available for synchronization.");
		}
		return new CaptureSyncInput(projectId, captureSessionId, snapshot.getCameraSetSnapshotId(),
				session.getSyncGroupId(), snapshotDigest(snapshot.getCameraSetSnapshotId(), expectedCameras),
				expectedCameras, syncStreams);
	}

	private MediaReportFacts loadMediaReport(UUID projectId, UUID clipAssetId) {
		var rows = this.jdbcTemplate.query("""
				SELECT media_report_id, input_digest, time_base_numerator, time_base_denominator,
				       first_pts, last_pts, frame_count
				FROM media_report
				WHERE project_id = ? AND clip_asset_id = ?
				ORDER BY created_at DESC, media_report_id DESC
				LIMIT 1
				""",
				(rs, rowNum) -> new MediaReportFacts(rs.getObject("media_report_id", UUID.class),
						rs.getString("input_digest"), rs.getInt("time_base_numerator"),
						rs.getInt("time_base_denominator"), rs.getObject("first_pts", Long.class),
						rs.getObject("last_pts", Long.class), rs.getObject("frame_count", Long.class)),
				projectId, clipAssetId);
		if (rows.isEmpty()) {
			throw new AssetCatalogQueryException("CAPTURE_SYNC_INPUT_NOT_READY",
					"A media report is required before synchronization can start.");
		}
		return rows.getFirst();
	}

	private static List<SourceFrame> synthesizeSourceFrames(MediaReportFacts report) {
		var firstPts = report.firstPts() == null ? 0L : report.firstPts();
		var lastPts = report.lastPts() == null ? firstPts : report.lastPts();
		var frameCount = report.frameCount() == null || report.frameCount() < 1 ? 1 : report.frameCount();
		var step = frameCount == 1 ? 0L : Math.floorDiv(lastPts - firstPts, frameCount - 1);
		var frames = new ArrayList<SourceFrame>();
		for (long index = 0; index < frameCount; index++) {
			var rawPts = firstPts + step * index;
			var sourcePtsNs = Rational.of(rawPts * report.timeBaseNumerator(), report.timeBaseDenominator())
				.multiply(Rational.whole(1_000_000_000L))
				.roundHalfEvenLong();
			frames.add(SourceFrame.valid(index, rawPts, sourcePtsNs, 0));
		}
		return List.copyOf(frames);
	}

	private static String snapshotDigest(UUID snapshotId, List<ExpectedCamera> expectedCameras) {
		var canonical = new StringBuilder(snapshotId.toString());
		expectedCameras.forEach(camera -> canonical.append('\n')
			.append(camera.cameraNumber())
			.append(':')
			.append(camera.cameraSetSnapshotMemberId()));
		return sha256(canonical.toString());
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable in the Java runtime.", exception);
		}
	}

	private record MediaReportFacts(UUID mediaReportId, String inputDigest, int timeBaseNumerator,
			int timeBaseDenominator, Long firstPts, Long lastPts, Long frameCount) {

		String timeBaseDigest() {
			return sha256("time_base=" + this.timeBaseNumerator + "/" + this.timeBaseDenominator);
		}
	}

}
