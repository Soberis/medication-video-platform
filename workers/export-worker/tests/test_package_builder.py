from __future__ import annotations

import hashlib
import json
import zipfile
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import UUID

import pytest

from medication_export_worker.api import ExportSession, ExportStream, LeaseGrant
from medication_export_worker.contracts import (
    CURRENT_PROFILE_DIGEST,
    CURRENT_PROFILE_KEY,
    DatasetExportJob,
)
from medication_export_worker.package_builder import (
    DatasetPackageBuilder,
    PackageInputError,
)


def export_job() -> DatasetExportJob:
    return DatasetExportJob(
        message_id=UUID("00000000-0000-0000-0000-000000000201"),
        export_id=UUID("00000000-0000-0000-0000-000000000202"),
        attempt=1,
        lease_token="a" * 43,
        project_id=UUID("00000000-0000-0000-0000-000000000203"),
        dataset_version_id=UUID("00000000-0000-0000-0000-000000000204"),
        snapshot_digest="b" * 64,
        export_profile_key=CURRENT_PROFILE_KEY,
        export_profile_digest=CURRENT_PROFILE_DIGEST,
        requested_at=datetime(2026, 7, 30, tzinfo=UTC),
    )


def lease(video: bytes, *, document: dict[str, object] | None = None) -> LeaseGrant:
    stream = ExportStream(
        camera_stream_id=UUID("00000000-0000-0000-0000-000000000205"),
        clip_asset_id=UUID("00000000-0000-0000-0000-000000000206"),
        camera_index=0,
        content_type="video/mp4",
        size_bytes=len(video),
        sha256=hashlib.sha256(video).hexdigest(),
        input_url="http://storage.invalid/input",
        video_path=(
            "videos/raw/00000000-0000-0000-0000-000000000207/"
            "camera-0-00000000-0000-0000-0000-000000000206.mp4"
        ),
        annotation_revision_id=UUID("00000000-0000-0000-0000-000000000208"),
        annotation_schema_version="annotation-document.v1",
        annotation_document_sha256="c" * 64,
        annotation_document=document
        or {"segments": [{"start_ms": 10, "end_ms": 900, "label": "prepare_dose"}]},
    )
    now = datetime(2026, 7, 30, tzinfo=UTC)
    return LeaseGrant(
        lease_until=now + timedelta(hours=2),
        heartbeat_interval_seconds=300,
        input_urls_expire_at=now + timedelta(hours=2),
        output_part_size_bytes=64 * 1024 * 1024,
        package_file_name="dataset-export.zip",
        label_schema={
            "label_schema_id": "00000000-0000-0000-0000-000000000209",
            "schema_key": "p0-primary-step",
            "schema_version": "v1",
            "schema_digest": "d" * 64,
            "labels": [
                {
                    "label_id": "prepare_dose",
                    "label_type": "PRIMARY_STEP",
                    "display_name": "准备药品",
                    "description": "合成测试标签",
                    "sort_order": 1,
                }
            ],
        },
        sessions=(
            ExportSession(
                capture_session_id=UUID("00000000-0000-0000-0000-000000000207"),
                split="PILOT",
                streams=(stream,),
            ),
        ),
    )


def test_builds_byte_identical_verified_package(tmp_path: Path) -> None:
    video = b"synthetic-video-bytes"
    grant = lease(video)
    source = tmp_path / "input.mp4"
    source.write_bytes(video)
    inputs = {grant.sessions[0].streams[0].video_path: source}
    builder = DatasetPackageBuilder()

    first = builder.build(export_job(), grant, inputs, tmp_path / "first.zip")
    second = builder.build(export_job(), grant, inputs, tmp_path / "second.zip")

    assert first.sha256 == second.sha256
    assert first.path.read_bytes() == second.path.read_bytes()
    with zipfile.ZipFile(first.path) as archive:
        names = set(archive.namelist())
        assert names == {file.path for file in first.files}
        manifest = json.loads(archive.read("manifest.jsonl"))
        assert manifest["streams"][0]["sha256"] == hashlib.sha256(video).hexdigest()
        assert archive.read("splits/pilot.txt").decode().strip() == str(
            grant.sessions[0].capture_session_id
        )
        checksums = archive.read("SHA256SUMS").decode()
        assert "manifest.jsonl" in checksums
        assert grant.sessions[0].streams[0].video_path in checksums


def test_rejects_missing_step_segments(tmp_path: Path) -> None:
    video = b"synthetic-video-bytes"
    grant = lease(video, document={"segments": []})
    source = tmp_path / "input.mp4"
    source.write_bytes(video)

    with pytest.raises(PackageInputError):
        DatasetPackageBuilder().build(
            export_job(),
            grant,
            {grant.sessions[0].streams[0].video_path: source},
            tmp_path / "invalid.zip",
        )
