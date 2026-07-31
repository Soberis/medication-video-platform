package com.medicationvideo.platform.mediaprivacy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

final class MediaDeadLetterIdentity {

	private static final UUID URL_NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

	private MediaDeadLetterIdentity() {
	}

	static UUID from(MediaDeadLetterReason reason, String bodySha256) {
		var name = "media-dead-letter.v1:" + reason.name() + ":" + bodySha256;
		try {
			var digest = MessageDigest.getInstance("SHA-1");
			digest.update(uuidBytes(URL_NAMESPACE));
			var bytes = digest.digest(name.getBytes(StandardCharsets.UTF_8));
			bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
			bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
			var buffer = ByteBuffer.wrap(bytes, 0, 16);
			return new UUID(buffer.getLong(), buffer.getLong());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-1 is unavailable for UUIDv5 verification.", exception);
		}
	}

	private static byte[] uuidBytes(UUID value) {
		return ByteBuffer.allocate(16)
			.putLong(value.getMostSignificantBits())
			.putLong(value.getLeastSignificantBits())
			.array();
	}

}
