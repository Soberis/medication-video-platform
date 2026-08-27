package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

record CaptureSessionResponse(UUID captureSessionId, UUID captureGroupId, UUID projectId, UUID batchId, String deviceId,
		UUID metadataUploadFileId, String metadataSchemaVersion, String metadataSha256, int expectedCameraCount,
		int receivedCameraCount, String syncGroupId, Instant deviceTimeUtc, String deviceTimezone,
		Instant serverReceiveTime, CaptureSessionAssemblyStatus assemblyStatus, CaptureQualityStatus qualityStatus,
		String captureGroupKey, ExternalVideoCaptureGroupKeySource captureGroupKeySource, UUID captureGroupManifestId,
		List<Integer> expectedCameraNumbers, List<Integer> receivedCameraNumbers, List<Integer> missingCameraNumbers,
		CaptureGroupReceiptState receiptState, Instant receiptFinalizedAt, CameraSetSnapshotResponse cameraSetSnapshot,
		List<CameraStreamResponse> streams, Instant createdAt) {

	static CaptureSessionResponse from(CaptureSession session, CameraSetSnapshot snapshot,
			List<CameraSetSnapshotMember> members, List<CameraStream> streams) {
		Map<UUID, CameraSetSnapshotMember> membersByChannel = members.stream()
			.collect(Collectors.toUnmodifiableMap(CameraSetSnapshotMember::getCameraChannelId, Function.identity()));
		var expectedCameraNumbers = members.stream()
			.filter(CameraSetSnapshotMember::isEnabled)
			.map(member -> member.getCameraIndex() + 1)
			.sorted()
			.toList();
		var receivedCameraNumbers = streams.stream().map(stream -> stream.getCameraIndex() + 1).sorted().toList();
		var receivedSet = java.util.Set.copyOf(receivedCameraNumbers);
		var missingCameraNumbers = expectedCameraNumbers.stream()
			.filter(cameraNumber -> !receivedSet.contains(cameraNumber))
			.toList();
		return new CaptureSessionResponse(session.getCaptureSessionId(), session.getCaptureSessionId(),
				session.getProjectId(), session.getBatchId(), session.getDeviceId(), session.getMetadataUploadFileId(),
				session.getMetadataSchemaVersion(), session.getMetadataSha256(), session.getExpectedCameraCount(),
				session.getReceivedCameraCount(), session.getSyncGroupId(), session.getDeviceTimeUtc(),
				session.getDeviceTimezone(), session.getServerReceiveTime(), session.getAssemblyStatus(),
				session.getQualityStatus(), session.getCaptureGroupKey(), session.getCaptureGroupKeySource(),
				session.getCaptureGroupManifestId(), expectedCameraNumbers, receivedCameraNumbers, missingCameraNumbers,
				session.getReceiptState(), session.getReceiptFinalizedAt(),
				CameraSetSnapshotResponse.from(snapshot, members),
				streams.stream()
					.sorted(java.util.Comparator.comparingInt(CameraStream::getCameraIndex))
					.map(stream -> CameraStreamResponse.from(stream,
							requireMember(membersByChannel, stream.getCameraChannelId())))
					.toList(),
				session.getCreatedAt());
	}

	private static CameraSetSnapshotMember requireMember(Map<UUID, CameraSetSnapshotMember> membersByChannel,
			UUID cameraChannelId) {
		var member = membersByChannel.get(cameraChannelId);
		if (member == null) {
			throw new IllegalStateException("A camera stream has no matching snapshot member.");
		}
		return member;
	}
}
