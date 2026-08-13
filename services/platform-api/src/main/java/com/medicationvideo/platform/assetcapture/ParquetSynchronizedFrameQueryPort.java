package com.medicationvideo.platform.assetcapture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.schema.MessageType;
import org.springframework.stereotype.Component;

/**
 * Reads the canonical Parquet artifact itself. The artifact manifest is metadata only;
 * dense Frame Slot rows are never copied into it or used as a query index.
 */
@Component
class ParquetSynchronizedFrameQueryPort implements SynchronizedFrameQueryPort {

	private static final List<String> REQUIRED_COLUMNS = List.of("schema_version", "capture_id", "sync_revision_id",
			"sync_frame_index", "capture_timestamp_ns", "camera_number", "camera_stream_id", "video_id",
			"source_frame_index", "source_pts_ns", "source_pts_ordinal", "frame_state", "frame_reason_code",
			"sync_error_ns");

	private final CaptureSyncStoragePort storage;

	ParquetSynchronizedFrameQueryPort(CaptureSyncStoragePort storage) {
		this.storage = storage;
	}

	@Override
	public FrameQueryPage query(CaptureSyncArtifact artifact, FrameQueryRequest request) {
		if (request.limit() < 1 || request.limit() > 100) {
			throw new AssetCatalogQueryException("CAPTURE_SYNC_QUERY_LIMIT_INVALID",
					"The frame query limit is outside the allowed range.");
		}
		var cameraFilter = request.cameraNumbers() == null || request.cameraNumbers().isEmpty() ? null
				: new LinkedHashSet<>(request.cameraNumbers());
		var cursor = CaptureSyncFrameCursor.decode(request.cursor(), request.syncRevisionId(), cameraFilter);
		try {
			var input = this.storage.open(artifact.getObjectReference());
			try (var reader = ParquetFileReader.open(input)) {
				var schema = reader.getFileMetaData().getSchema();
				validateSchema(schema, artifact, request);
				var page = new ArrayList<SynchronizedFrameSlotResponse>(request.limit() + 1);
				SynchronizedFrameSlotResponse previous = null;
				var blocks = reader.getRowGroups();
				for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
					var block = blocks.get(blockIndex);
					if (!mayContainFrame(block, request.fromSyncFrameIndex())) {
						continue;
					}
					var rows = readRowGroup(reader, schema, blockIndex, block, request, cursor, cameraFilter,
							request.limit() + 1 - page.size());
					for (var row : rows) {
						if (previous != null && compare(previous, row) >= 0) {
							throw invalid("The synchronized-frame artifact is not in stable frame/camera order.");
						}
						previous = row;
						page.add(row);
						if (page.size() > request.limit()) {
							var result = List.copyOf(page.subList(0, request.limit()));
							var next = CaptureSyncFrameCursor.encode(result.getLast(), request.syncRevisionId(),
									cameraFilter);
							return new FrameQueryPage(result, next);
						}
					}
				}
				return new FrameQueryPage(List.copyOf(page), null);
			}
		}
		catch (AssetCatalogQueryException exception) {
			throw exception;
		}
		catch (IOException | RuntimeException exception) {
			throw invalid("The synchronized-frame Parquet artifact could not be read.", exception);
		}
	}

	private static List<SynchronizedFrameSlotResponse> readRowGroup(ParquetFileReader reader, MessageType schema,
			int blockIndex, BlockMetaData block, FrameQueryRequest request, CaptureSyncFrameCursor.Position cursor,
			LinkedHashSet<Integer> cameraFilter, int maxRows) throws IOException {
		var pageStore = reader.readRowGroup(blockIndex);
		var columnIO = new ColumnIOFactory().getColumnIO(schema);
		RecordReader<Group> records = columnIO.getRecordReader(pageStore, new GroupRecordConverter(schema));
		var result = new ArrayList<SynchronizedFrameSlotResponse>();
		for (long rowIndex = 0; rowIndex < block.getRowCount(); rowIndex++) {
			var group = records.read();
			if (group == null) {
				break;
			}
			var slot = toResponse(group, request.captureSessionId(), request.syncRevisionId());
			if (request.fromSyncFrameIndex() != null && slot.syncFrameIndex() < request.fromSyncFrameIndex()) {
				continue;
			}
			if (cameraFilter != null && !cameraFilter.contains(slot.cameraNumber())) {
				continue;
			}
			if (cursor != null && compare(slot, cursor) <= 0) {
				continue;
			}
			result.add(slot);
			if (result.size() == maxRows) {
				break;
			}
		}
		return result;
	}

	private static boolean mayContainFrame(BlockMetaData block, Long fromSyncFrameIndex) {
		if (fromSyncFrameIndex == null) {
			return true;
		}
		for (ColumnChunkMetaData column : block.getColumns()) {
			if (!"sync_frame_index".equals(column.getPath().toDotString())) {
				continue;
			}
			var statistics = column.getStatistics();
			if (statistics != null && statistics.hasNonNullValue()
					&& statistics.genericGetMax() instanceof Number max) {
				return max.longValue() >= fromSyncFrameIndex;
			}
			return true;
		}
		return true;
	}

	private static void validateSchema(MessageType schema, CaptureSyncArtifact artifact, FrameQueryRequest request) {
		for (var column : REQUIRED_COLUMNS) {
			if (!schema.containsPath(new String[] { column })) {
				throw invalid("The synchronized-frame Parquet schema is missing " + column + ".");
			}
		}
		var manifest = artifact.getArtifactManifest();
		if (!CaptureSyncArtifact.SCHEMA_VERSION.equals(manifestString(manifest, "schema_version"))) {
			throw invalid("The synchronized-frame artifact manifest schema version is invalid.");
		}
		if (!request.captureSessionId().equals(manifestUuid(manifest, "capture_id"))
				|| !request.syncRevisionId().equals(manifestUuid(manifest, "sync_revision_id"))
				|| !artifact.getCaptureSyncRevisionId().equals(request.syncRevisionId())) {
			throw invalid("The synchronized-frame artifact manifest identity is invalid.");
		}
	}

	private static SynchronizedFrameSlotResponse toResponse(Group group, UUID requestedCaptureId,
			UUID requestedRevisionId) {
		var schemaVersion = requiredString(group, "schema_version");
		if (!CaptureSyncArtifact.SCHEMA_VERSION.equals(schemaVersion)) {
			throw invalid("The synchronized-frame row schema version is invalid.");
		}
		var rowCapture = requiredUuid(group, "capture_id");
		var rowRevision = requiredUuid(group, "sync_revision_id");
		if (!requestedCaptureId.equals(rowCapture) || !requestedRevisionId.equals(rowRevision)) {
			throw invalid("The synchronized-frame row identity does not match the requested revision.");
		}
		return new SynchronizedFrameSlotResponse(requiredLong(group, "sync_frame_index"),
				requiredLong(group, "capture_timestamp_ns"), requiredInt(group, "camera_number"),
				optionalUuid(group, "camera_stream_id"), optionalUuid(group, "video_id"),
				optionalLong(group, "source_frame_index"), optionalLong(group, "source_pts_ns"),
				optionalInt(group, "source_pts_ordinal"), requiredString(group, "frame_state"),
				optionalString(group, "frame_reason_code"), optionalLong(group, "sync_error_ns"));
	}

	private static String requiredString(Group group, String name) {
		if (group.getFieldRepetitionCount(name) != 1) {
			throw invalid("The synchronized-frame row is missing " + name + ".");
		}
		return group.getString(name, 0);
	}

	private static String optionalString(Group group, String name) {
		return group.getFieldRepetitionCount(name) == 0 ? null : group.getString(name, 0);
	}

	private static long requiredLong(Group group, String name) {
		if (group.getFieldRepetitionCount(name) != 1) {
			throw invalid("The synchronized-frame row is missing " + name + ".");
		}
		return group.getLong(name, 0);
	}

	private static Long optionalLong(Group group, String name) {
		return group.getFieldRepetitionCount(name) == 0 ? null : group.getLong(name, 0);
	}

	private static int requiredInt(Group group, String name) {
		if (group.getFieldRepetitionCount(name) != 1) {
			throw invalid("The synchronized-frame row is missing " + name + ".");
		}
		return group.getInteger(name, 0);
	}

	private static Integer optionalInt(Group group, String name) {
		return group.getFieldRepetitionCount(name) == 0 ? null : group.getInteger(name, 0);
	}

	private static UUID optionalUuid(Group group, String name) {
		var value = optionalString(group, name);
		return value == null ? null : parseUuid(value, name);
	}

	private static UUID requiredUuid(Group group, String name) {
		return parseUuid(requiredString(group, name), name);
	}

	private static String manifestString(Map<String, Object> manifest, String name) {
		var value = manifest.get(name);
		return value instanceof String text ? text : null;
	}

	private static UUID manifestUuid(Map<String, Object> manifest, String name) {
		var value = manifestString(manifest, name);
		return value == null ? null : parseUuid(value, name);
	}

	private static UUID parseUuid(String value, String name) {
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException exception) {
			throw invalid("The synchronized-frame identity field " + name + " is not a UUID.");
		}
	}

	private static int compare(SynchronizedFrameSlotResponse left, SynchronizedFrameSlotResponse right) {
		var frame = Long.compare(left.syncFrameIndex(), right.syncFrameIndex());
		return frame != 0 ? frame : Integer.compare(left.cameraNumber(), right.cameraNumber());
	}

	private static int compare(SynchronizedFrameSlotResponse slot, CaptureSyncFrameCursor.Position cursor) {
		var frame = Long.compare(slot.syncFrameIndex(), cursor.syncFrameIndex());
		return frame != 0 ? frame : Integer.compare(slot.cameraNumber(), cursor.cameraNumber());
	}

	private static AssetCatalogQueryException invalid(String message) {
		return new AssetCatalogQueryException("CAPTURE_SYNC_QUERY_ARTIFACT_INVALID", message);
	}

	private static AssetCatalogQueryException invalid(String message, Throwable cause) {
		var exception = new AssetCatalogQueryException("CAPTURE_SYNC_QUERY_ARTIFACT_UNREADABLE", message);
		exception.initCause(cause);
		return exception;
	}

}
