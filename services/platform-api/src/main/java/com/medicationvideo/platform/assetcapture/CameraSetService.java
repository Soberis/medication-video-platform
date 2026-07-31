package com.medicationvideo.platform.assetcapture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class CameraSetService {

	private final CameraSetConfigurationRepository configurationRepository;

	private final CameraSetMemberRepository memberRepository;

	private final CameraChannelRepository channelRepository;

	private final TransactionTemplate transactionTemplate;

	CameraSetService(CameraSetConfigurationRepository configurationRepository,
			CameraSetMemberRepository memberRepository, CameraChannelRepository channelRepository,
			TransactionTemplate transactionTemplate) {
		this.configurationRepository = configurationRepository;
		this.memberRepository = memberRepository;
		this.channelRepository = channelRepository;
		this.transactionTemplate = transactionTemplate;
	}

	CameraSetResponse configure(UUID projectId, String cameraSetKey, int configurationVersion,
			CameraSetRequest request) {
		validateMembers(request);
		var fingerprint = fingerprint(cameraSetKey, configurationVersion, request);
		var existing = find(projectId, cameraSetKey, configurationVersion);
		if (existing != null) {
			return requireSameConfiguration(existing, fingerprint);
		}

		try {
			var response = this.transactionTemplate
				.execute(status -> create(projectId, cameraSetKey, configurationVersion, request, fingerprint));
			if (response == null) {
				throw new IllegalStateException("Camera set transaction returned no result.");
			}
			return response;
		}
		catch (DataIntegrityViolationException exception) {
			var concurrent = find(projectId, cameraSetKey, configurationVersion);
			if (concurrent != null) {
				return requireSameConfiguration(concurrent, fingerprint);
			}
			throw exception;
		}
	}

	private CameraSetResponse create(UUID projectId, String cameraSetKey, int configurationVersion,
			CameraSetRequest request, String fingerprint) {
		var existing = find(projectId, cameraSetKey, configurationVersion);
		if (existing != null) {
			return requireSameConfiguration(existing, fingerprint);
		}
		var channelIds = request.members().stream().map(CameraSetMemberRequest::cameraChannelId).toList();
		var ownedChannels = this.channelRepository.findAllByProjectIdAndCameraChannelIdIn(projectId, channelIds);
		if (ownedChannels.size() != channelIds.size()) {
			throw failure("CAMERA_SET_CHANNEL_NOT_FOUND",
					"Every camera set member must reference a channel in the authenticated project.");
		}

		var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		var configuration = CameraSetConfiguration.create(projectId, cameraSetKey, configurationVersion,
				request.layoutVersion(), fingerprint, now);
		this.configurationRepository.saveAndFlush(configuration);
		var members = request.members()
			.stream()
			.map(member -> CameraSetMember.create(configuration.getCameraSetId(), member, now))
			.toList();
		this.memberRepository.saveAllAndFlush(members);
		return CameraSetResponse.from(configuration, members);
	}

	private CameraSetConfiguration find(UUID projectId, String cameraSetKey, int configurationVersion) {
		return this.configurationRepository
			.findByProjectIdAndCameraSetKeyAndConfigurationVersion(projectId, cameraSetKey, configurationVersion)
			.orElse(null);
	}

	private CameraSetResponse requireSameConfiguration(CameraSetConfiguration configuration, String fingerprint) {
		if (!MessageDigest.isEqual(configuration.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
				fingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw failure("CAMERA_SET_VERSION_CONFLICT",
					"The immutable camera set version already exists with different content.");
		}
		var members = this.memberRepository.findAllByCameraSetIdOrderByCameraIndexAsc(configuration.getCameraSetId());
		return CameraSetResponse.from(configuration, members);
	}

	private static void validateMembers(CameraSetRequest request) {
		var channelIds = new HashSet<UUID>();
		var indexes = new HashSet<Integer>();
		var hasEnabledMember = false;
		for (var member : request.members()) {
			if (!channelIds.add(member.cameraChannelId())) {
				throw failure("CAMERA_SET_DUPLICATE_CHANNEL",
						"A camera channel can appear only once in a set version.");
			}
			if (!indexes.add(member.cameraIndex())) {
				throw failure("CAMERA_SET_DUPLICATE_INDEX", "A camera index can appear only once in a set version.");
			}
			hasEnabledMember = hasEnabledMember || member.enabled();
		}
		if (!hasEnabledMember) {
			throw failure("CAMERA_SET_NO_ENABLED_CHANNEL", "A camera set must contain at least one enabled channel.");
		}
	}

	private static String fingerprint(String cameraSetKey, int configurationVersion, CameraSetRequest request) {
		var canonical = new StringBuilder("camera-set-v1\n").append(cameraSetKey)
			.append('\n')
			.append(configurationVersion)
			.append('\n')
			.append(request.layoutVersion())
			.append('\n');
		request.members()
			.stream()
			.sorted(java.util.Comparator.comparingInt(CameraSetMemberRequest::cameraIndex))
			.forEach(member -> canonical.append(member.cameraChannelId())
				.append('\t')
				.append(member.cameraIndex())
				.append('\t')
				.append(member.cameraRole())
				.append('\t')
				.append(member.mountPosition())
				.append('\t')
				.append(member.enabled())
				.append('\n'));
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available in this Java runtime.", exception);
		}
	}

	private static CameraConfigurationException failure(String errorCode, String message) {
		return new CameraConfigurationException(errorCode, message);
	}

}
