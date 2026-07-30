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


def test_builds_byte_identical_v2_golden_package(tmp_path: Path) -> None:
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
    video_path = (
        "videos/raw/00000000-0000-0000-0000-000000000207/"
        "camera-0-00000000-0000-0000-0000-000000000206.mp4"
    )
    assert grant.sessions[0].streams[0].video_path == video_path
    video_sha256 = hashlib.sha256(video).hexdigest()
    golden_files = {
        "README.md": (
            "# P0 Step Dataset\n\n"
            "Internal MODEL_TRAINING export. This package contains frozen raw ClipAsset bytes "
            "and independently reviewed primary Step segments. It does not imply privacy release "
            "or external sharing approval.\n\n"
            "- Dataset version: 00000000-0000-0000-0000-000000000204\n"
            f"- Snapshot SHA-256: {'b' * 64}\n"
            "- Export profile: p0-dataset-export-v2\n"
            "- Export profile SHA-256: "
            "5f86b48ccd02ef8ea0a05c967b85017bb9cd5de59b8f466b7bb93dd552fdcb96\n"
            "- Split unit: CAPTURE_SESSION\n"
            "- Input policy: RAW_CLIP_ASSET_V1\n"
        ).encode(),
        "label_schema.json": (
            '{"label_schema_id":"00000000-0000-0000-0000-000000000209",'
            '"labels":[{"description":"合成测试标签","display_name":"准备药品",'
            '"label_id":"prepare_dose","label_type":"PRIMARY_STEP","sort_order":1}],'
            f'"schema_digest":"{'d' * 64}","schema_key":"p0-primary-step",'
            '"schema_version":"v1"}\n'
        ).encode(),
        "manifest.jsonl": (
            '{"capture_session_id":"00000000-0000-0000-0000-000000000207",'
            '"split":"pilot","streams":[{'
            f'"annotation_document_sha256":"{'c' * 64}",'
            '"annotation_revision_id":"00000000-0000-0000-0000-000000000208",'
            '"camera_index":0,'
            '"camera_stream_id":"00000000-0000-0000-0000-000000000205",'
            '"clip_asset_id":"00000000-0000-0000-0000-000000000206",'
            '"content_type":"video/mp4",'
            f'"sha256":"{video_sha256}","size_bytes":21,"video_path":"{video_path}"'
            "}]}\n"
        ).encode(),
        "action_segments.jsonl": (
            f'{{"annotation_document_sha256":"{'c' * 64}",'
            '"annotation_revision_id":"00000000-0000-0000-0000-000000000208",'
            '"annotation_schema_version":"annotation-document.v1",'
            '"camera_stream_id":"00000000-0000-0000-0000-000000000205",'
            '"capture_session_id":"00000000-0000-0000-0000-000000000207",'
            '"clip_asset_id":"00000000-0000-0000-0000-000000000206",'
            '"segments":[{"end_ms":900,"label":"prepare_dose","start_ms":10}]}\n'
        ).encode(),
        "splits/holdout.txt": b"",
        "splits/pilot.txt": b"00000000-0000-0000-0000-000000000207\n",
        video_path: video,
    }
    golden_files["SHA256SUMS"] = "".join(
        f"{hashlib.sha256(content).hexdigest()}  {path}\n"
        for path, content in sorted(golden_files.items())
    ).encode()

    with zipfile.ZipFile(first.path) as archive:
        names = set(archive.namelist())
        assert names == set(golden_files)
        assert {file.path for file in first.files} == set(golden_files)
        for path, expected_content in golden_files.items():
            assert archive.read(path) == expected_content

        manifest = json.loads(golden_files["manifest.jsonl"])
        assert manifest["streams"][0]["sha256"] == hashlib.sha256(video).hexdigest()


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
