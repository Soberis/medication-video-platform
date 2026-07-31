from __future__ import annotations

import hashlib
import json
import zipfile
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

from medication_export_worker.api import ExportStream, LeaseGrant
from medication_export_worker.contracts import DatasetExportJob

ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
CHUNK_SIZE = 1024 * 1024


class PackageBuildError(RuntimeError):
    """The deterministic package could not be constructed from the frozen inputs."""


class PackageInputError(PackageBuildError):
    """Frozen metadata does not satisfy the approved export profile."""


@dataclass(frozen=True, slots=True)
class FileResult:
    path: str
    sha256: str
    size_bytes: int

    def as_contract(self) -> dict[str, object]:
        return {"path": self.path, "sha256": self.sha256, "size_bytes": self.size_bytes}


@dataclass(frozen=True, slots=True)
class PackageResult:
    path: Path
    sha256: str
    size_bytes: int
    files: tuple[FileResult, ...]


class DatasetPackageBuilder:
    def build(
        self,
        job: DatasetExportJob,
        grant: LeaseGrant,
        downloaded_inputs: Mapping[str, Path],
        target: Path,
    ) -> PackageResult:
        expected_paths = {
            stream.video_path for session in grant.sessions for stream in session.streams
        }
        if set(downloaded_inputs) != expected_paths:
            raise PackageInputError("Downloaded inputs do not match the frozen stream set")

        metadata = _metadata_files(job, grant)
        file_results: dict[str, FileResult] = {}
        try:
            with zipfile.ZipFile(
                target,
                mode="x",
                compression=zipfile.ZIP_STORED,
                allowZip64=True,
            ) as archive:
                for path in sorted(metadata):
                    file_results[path] = _write_bytes(archive, path, metadata[path])

                streams = sorted(
                    (stream for session in grant.sessions for stream in session.streams),
                    key=lambda stream: stream.video_path,
                )
                for stream in streams:
                    result = _write_file(
                        archive,
                        stream.video_path,
                        downloaded_inputs[stream.video_path],
                    )
                    if result.sha256 != stream.sha256 or result.size_bytes != stream.size_bytes:
                        raise PackageInputError(
                            "A downloaded input changed before deterministic packaging"
                        )
                    file_results[result.path] = result

                checksums = "".join(
                    f"{file_results[path].sha256}  {path}\n" for path in sorted(file_results)
                ).encode()
                file_results["SHA256SUMS"] = _write_bytes(archive, "SHA256SUMS", checksums)
        except PackageBuildError:
            raise
        except (OSError, ValueError, zipfile.BadZipFile) as exc:
            raise PackageBuildError("Dataset package could not be written") from exc

        package_sha256, package_size = _digest_file(target)
        return PackageResult(
            path=target,
            sha256=package_sha256,
            size_bytes=package_size,
            files=tuple(file_results[path] for path in sorted(file_results)),
        )


def _metadata_files(job: DatasetExportJob, grant: LeaseGrant) -> dict[str, bytes]:
    pilot: list[str] = []
    holdout: list[str] = []
    manifest: list[dict[str, object]] = []
    action_segments: list[dict[str, object]] = []

    for session in sorted(grant.sessions, key=lambda value: str(value.capture_session_id)):
        split_name = session.split.lower()
        (pilot if session.split == "PILOT" else holdout).append(str(session.capture_session_id))
        streams = sorted(session.streams, key=lambda value: value.camera_index)
        manifest.append(
            {
                "capture_session_id": str(session.capture_session_id),
                "split": split_name,
                "streams": [
                    {
                        "camera_stream_id": str(stream.camera_stream_id),
                        "clip_asset_id": str(stream.clip_asset_id),
                        "camera_index": stream.camera_index,
                        "video_path": stream.video_path,
                        "content_type": stream.content_type,
                        "size_bytes": stream.size_bytes,
                        "sha256": stream.sha256,
                        "annotation_revision_id": str(stream.annotation_revision_id),
                        "annotation_document_sha256": stream.annotation_document_sha256,
                    }
                    for stream in streams
                ],
            }
        )
        action_segments.extend(
            _action_record(session.capture_session_id, stream) for stream in streams
        )

    readme = (
        "# P0 Step Dataset\n\n"
        "Internal MODEL_TRAINING export. This package contains frozen raw ClipAsset bytes "
        "and independently reviewed primary Step segments. It does not imply privacy release "
        "or external sharing approval.\n\n"
        f"- Dataset version: {job.dataset_version_id}\n"
        f"- Snapshot SHA-256: {job.snapshot_digest}\n"
        f"- Export profile: {job.export_profile_key}\n"
        f"- Export profile SHA-256: {job.export_profile_digest}\n"
        "- Split unit: CAPTURE_SESSION\n"
        "- Input policy: RAW_CLIP_ASSET_V1\n"
    ).encode()
    return {
        "README.md": readme,
        "label_schema.json": _canonical_json(grant.label_schema),
        "manifest.jsonl": _json_lines(manifest),
        "action_segments.jsonl": _json_lines(action_segments),
        "splits/pilot.txt": _text_lines(pilot),
        "splits/holdout.txt": _text_lines(holdout),
    }


def _action_record(capture_session_id: object, stream: ExportStream) -> dict[str, object]:
    segments = stream.annotation_document.get("segments")
    if not isinstance(segments, list) or not segments:
        raise PackageInputError("A frozen annotation document has no Step segments")
    normalized: list[dict[str, object]] = []
    for segment in segments:
        if not isinstance(segment, dict):
            raise PackageInputError("A frozen Step segment is invalid")
        start = segment.get("start_ms")
        end = segment.get("end_ms")
        label = segment.get("label")
        if (
            isinstance(start, bool)
            or not isinstance(start, int)
            or isinstance(end, bool)
            or not isinstance(end, int)
            or start < 0
            or end <= start
            or not isinstance(label, str)
            or not label
        ):
            raise PackageInputError("A frozen Step segment is invalid")
        normalized.append({"start_ms": start, "end_ms": end, "label": label})
    return {
        "capture_session_id": str(capture_session_id),
        "camera_stream_id": str(stream.camera_stream_id),
        "clip_asset_id": str(stream.clip_asset_id),
        "annotation_revision_id": str(stream.annotation_revision_id),
        "annotation_schema_version": stream.annotation_schema_version,
        "annotation_document_sha256": stream.annotation_document_sha256,
        "segments": normalized,
    }


def _canonical_json(value: object) -> bytes:
    try:
        return (
            json.dumps(
                value,
                ensure_ascii=False,
                allow_nan=False,
                separators=(",", ":"),
                sort_keys=True,
            )
            + "\n"
        ).encode()
    except (TypeError, ValueError) as exc:
        raise PackageInputError("Frozen export metadata is not valid JSON") from exc


def _json_lines(values: list[dict[str, object]]) -> bytes:
    return b"".join(_canonical_json(value) for value in values)


def _text_lines(values: list[str]) -> bytes:
    return "".join(f"{value}\n" for value in sorted(values)).encode()


def _zip_info(path: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(path, ZIP_TIMESTAMP)
    info.compress_type = zipfile.ZIP_STORED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    return info


def _write_bytes(archive: zipfile.ZipFile, path: str, value: bytes) -> FileResult:
    archive.writestr(_zip_info(path), value)
    return FileResult(path, hashlib.sha256(value).hexdigest(), len(value))


def _write_file(archive: zipfile.ZipFile, path: str, source: Path) -> FileResult:
    digest = hashlib.sha256()
    size = 0
    with (
        source.open("rb") as input_file,
        archive.open(_zip_info(path), "w", force_zip64=True) as output,
    ):
        while chunk := input_file.read(CHUNK_SIZE):
            output.write(chunk)
            digest.update(chunk)
            size += len(chunk)
    return FileResult(path, digest.hexdigest(), size)


def _digest_file(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        while chunk := source.read(CHUNK_SIZE):
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size
