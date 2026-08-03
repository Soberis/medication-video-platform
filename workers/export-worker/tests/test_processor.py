from __future__ import annotations

import hashlib
import json
from contextlib import AbstractContextManager, nullcontext
from datetime import UTC, datetime, timedelta
from io import BytesIO
from pathlib import Path
from uuid import UUID
from zipfile import ZipFile

from medication_export_worker.api import (
    ExportSession,
    ExportStream,
    InputDigestMismatchError,
    LeaseGrant,
    OutputUploadError,
    PermanentDeliveryError,
    RetryableDeliveryError,
)
from medication_export_worker.contracts import (
    CURRENT_PROFILE_DIGEST,
    CURRENT_PROFILE_KEY,
    LEGACY_PROFILE_DIGEST,
    LEGACY_PROFILE_KEY,
    DatasetExportJob,
)
from medication_export_worker.package_builder import DatasetPackageBuilder
from medication_export_worker.processor import (
    DeadLetterReason,
    DeliveryDisposition,
    JobProcessor,
    SyntheticFirstAttemptFailureBuilder,
)

MESSAGE_ID = UUID("00000000-0000-0000-0000-000000000301")
EXPORT_ID = UUID("00000000-0000-0000-0000-000000000302")
PROJECT_ID = UUID("00000000-0000-0000-0000-000000000303")
DATASET_VERSION_ID = UUID("00000000-0000-0000-0000-000000000304")
CAPTURE_SESSION_ID = UUID("00000000-0000-0000-0000-000000000305")
CAMERA_STREAM_ID = UUID("00000000-0000-0000-0000-000000000306")
CLIP_ASSET_ID = UUID("00000000-0000-0000-0000-000000000307")
ANNOTATION_REVISION_ID = UUID("00000000-0000-0000-0000-000000000308")
VIDEO_PATH = f"videos/raw/{CAPTURE_SESSION_ID}/camera-0-{CLIP_ASSET_ID}.mp4"
LEASE_TOKEN = "a" * 43
SNAPSHOT_DIGEST = "b" * 64
DOCUMENT_DIGEST = "c" * 64
SOFTWARE_DIGEST = "sha256:" + "d" * 64


def job_body(
    *,
    attempt: int = 1,
    profile_key: str = CURRENT_PROFILE_KEY,
    profile_digest: str = CURRENT_PROFILE_DIGEST,
) -> bytes:
    return json.dumps(
        {
            "schema_version": "dataset-export-job.v1",
            "message_id": str(MESSAGE_ID),
            "export_id": str(EXPORT_ID),
            "job_type": "DATASET_EXPORT",
            "attempt": attempt,
            "lease_token": LEASE_TOKEN,
            "project_id": str(PROJECT_ID),
            "dataset_version_id": str(DATASET_VERSION_ID),
            "snapshot_digest": SNAPSHOT_DIGEST,
            "export_profile_key": profile_key,
            "export_profile_digest": profile_digest,
            "requested_at": "2026-07-31T00:00:00Z",
        },
        separators=(",", ":"),
    ).encode()


def lease_grant(video: bytes) -> LeaseGrant:
    return LeaseGrant(
        lease_until=datetime.now(UTC) + timedelta(hours=1),
        heartbeat_interval_seconds=300,
        input_urls_expire_at=datetime.now(UTC) + timedelta(hours=1),
        output_part_size_bytes=64 * 1024 * 1024,
        package_file_name="dataset-export.zip",
        label_schema={
            "label_schema_id": "00000000-0000-0000-0000-000000000309",
            "schema_key": "p0-primary-step",
            "schema_version": "v1",
            "schema_digest": "e" * 64,
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
                capture_session_id=CAPTURE_SESSION_ID,
                split="PILOT",
                streams=(
                    ExportStream(
                        camera_stream_id=CAMERA_STREAM_ID,
                        clip_asset_id=CLIP_ASSET_ID,
                        camera_index=0,
                        content_type="video/mp4",
                        size_bytes=len(video),
                        sha256=hashlib.sha256(video).hexdigest(),
                        input_url="https://storage.invalid/input",
                        video_path=VIDEO_PATH,
                        annotation_revision_id=ANNOTATION_REVISION_ID,
                        annotation_schema_version="annotation-document.v1",
                        annotation_document_sha256=DOCUMENT_DIGEST,
                        annotation_document={
                            "segments": [{"start_ms": 10, "end_ms": 900, "label": "prepare_dose"}]
                        },
                    ),
                ),
            ),
        ),
    )


class RecordingClient:
    def __init__(
        self,
        video: bytes,
        *,
        acquire_error: Exception | None = None,
        download_error: Exception | None = None,
        upload_error: Exception | None = None,
        result_error: Exception | None = None,
    ) -> None:
        self.grant = lease_grant(video)
        self.video = video
        self.acquire_error = acquire_error
        self.download_error = download_error
        self.upload_error = upload_error
        self.result_error = result_error
        self.acquired: list[DatasetExportJob] = []
        self.downloaded: list[ExportStream] = []
        self.uploaded_packages: list[bytes] = []
        self.results: list[dict[str, object]] = []

    def acquire(self, job: DatasetExportJob) -> LeaseGrant:
        self.acquired.append(job)
        if self.acquire_error is not None:
            raise self.acquire_error
        return self.grant

    def maintain_lease(
        self,
        job: DatasetExportJob,
        grant: LeaseGrant,
    ) -> AbstractContextManager[object]:
        del job, grant
        return nullcontext()

    def download_input(self, stream: ExportStream, target: Path) -> None:
        self.downloaded.append(stream)
        if self.download_error is not None:
            raise self.download_error
        target.write_bytes(self.video)

    def upload_package(self, job: DatasetExportJob, grant: LeaseGrant, package: Path) -> None:
        del job, grant
        if self.upload_error is not None:
            raise self.upload_error
        self.uploaded_packages.append(package.read_bytes())

    def submit_result(self, job: DatasetExportJob, result: dict[str, object]) -> None:
        del job
        self.results.append(result)
        if self.result_error is not None:
            raise self.result_error


def processor(client: RecordingClient, temp_dir: Path) -> JobProcessor:
    return JobProcessor(client, DatasetPackageBuilder(), SOFTWARE_DIGEST, temp_dir)


def test_processes_current_export_through_real_package_build(tmp_path: Path) -> None:
    video = b"synthetic-video-bytes"
    client = RecordingClient(video)

    outcome = processor(client, tmp_path).process(job_body())

    assert outcome.disposition is DeliveryDisposition.ACK
    assert outcome.dead_letter_reason is None
    assert outcome.export_id == EXPORT_ID
    assert client.acquired and client.downloaded == [client.grant.sessions[0].streams[0]]
    assert len(client.uploaded_packages) == 1
    assert len(client.results) == 1
    result = client.results[0]
    assert result["status"] == "SUCCEEDED"
    assert result["error"] is None
    assert result["software_digest"] == SOFTWARE_DIGEST
    assert result["files"]
    assert list(tmp_path.glob("dataset-export-*")) == []
    with ZipFile(BytesIO(client.uploaded_packages[0])) as archive:
        assert set(archive.namelist()) >= {
            "README.md",
            "manifest.jsonl",
            "action_segments.jsonl",
            "SHA256SUMS",
            VIDEO_PATH,
        }


def test_reports_input_digest_mismatch_without_requeueing_delivery(tmp_path: Path) -> None:
    client = RecordingClient(b"synthetic-video-bytes", download_error=InputDigestMismatchError())

    outcome = processor(client, tmp_path).process(job_body())

    assert outcome.disposition is DeliveryDisposition.ACK
    assert client.uploaded_packages == []
    assert client.results[0]["status"] == "FAILED"
    assert client.results[0]["error"] == {
        "error_class": "NON_RETRYABLE_INPUT",
        "error_code": "INPUT_DIGEST_MISMATCH",
        "safe_message": "Downloaded input does not match the frozen size and SHA-256",
        "retryable": False,
    }


def test_reports_transient_output_failure_for_core_api_retry_policy(tmp_path: Path) -> None:
    client = RecordingClient(b"synthetic-video-bytes", upload_error=OutputUploadError())

    outcome = processor(client, tmp_path).process(job_body())

    assert outcome.disposition is DeliveryDisposition.ACK
    assert client.results[0]["status"] == "FAILED"
    assert client.results[0]["error"] == {
        "error_class": "RETRYABLE_TRANSIENT",
        "error_code": "OUTPUT_UPLOAD_FAILED",
        "safe_message": "Dataset package could not be uploaded to object storage",
        "retryable": True,
    }


def test_synthetic_first_attempt_failure_builder_allows_the_second_attempt_to_succeed(
    tmp_path: Path,
) -> None:
    client = RecordingClient(b"synthetic-video-bytes")
    processor = JobProcessor(
        client,
        SyntheticFirstAttemptFailureBuilder(DatasetPackageBuilder()),
        SOFTWARE_DIGEST,
        tmp_path,
    )

    first = processor.process(job_body(attempt=1))
    second = processor.process(job_body(attempt=2))

    assert first.disposition is DeliveryDisposition.ACK
    assert client.results[0]["status"] == "FAILED"
    assert client.results[0]["error"] == {
        "error_class": "RETRYABLE_RESOURCE",
        "error_code": "PACKAGE_BUILD_FAILED",
        "safe_message": "Dataset package could not be built on Worker temporary storage",
        "retryable": True,
    }
    assert len(client.uploaded_packages) == 1
    assert second.disposition is DeliveryDisposition.ACK
    assert client.results[1]["status"] == "SUCCEEDED"


def test_synthetic_lease_loss_skips_first_result_after_upload_and_allows_second_attempt(
    tmp_path: Path,
) -> None:
    client = RecordingClient(b"synthetic-video-bytes")
    processor = JobProcessor(
        client,
        DatasetPackageBuilder(),
        SOFTWARE_DIGEST,
        tmp_path,
        synthetic_lease_loss_first_attempt=True,
    )

    first = processor.process(job_body(attempt=1))
    assert first.disposition is DeliveryDisposition.ACK
    assert client.acquired[0].attempt == 1
    assert len(client.uploaded_packages) == 1
    assert client.results == []

    second = processor.process(job_body(attempt=2))

    assert len(client.uploaded_packages) == 2
    assert second.disposition is DeliveryDisposition.ACK
    assert len(client.results) == 1
    assert client.results[0]["attempt"] == 2
    assert client.results[0]["status"] == "SUCCEEDED"
    assert list(tmp_path.glob("dataset-export-*")) == []


def test_requeues_when_result_submission_is_temporarily_unavailable(tmp_path: Path) -> None:
    client = RecordingClient(
        b"synthetic-video-bytes",
        result_error=RetryableDeliveryError(),
    )

    outcome = processor(client, tmp_path).process(job_body())

    assert outcome.disposition is DeliveryDisposition.REQUEUE
    assert outcome.dead_letter_reason is None
    assert len(client.results) == 1


def test_rejects_legacy_profile_without_contacting_core_api(tmp_path: Path) -> None:
    client = RecordingClient(b"synthetic-video-bytes")

    outcome = processor(client, tmp_path).process(
        job_body(profile_key=LEGACY_PROFILE_KEY, profile_digest=LEGACY_PROFILE_DIGEST)
    )

    assert outcome.disposition is DeliveryDisposition.REJECT
    assert outcome.dead_letter_reason is DeadLetterReason.PROFILE_UNSUPPORTED
    assert client.acquired == []


def test_rejects_permanent_lease_failure_without_running_the_builder(tmp_path: Path) -> None:
    client = RecordingClient(
        b"synthetic-video-bytes",
        acquire_error=PermanentDeliveryError(),
    )

    outcome = processor(client, tmp_path).process(job_body())

    assert outcome.disposition is DeliveryDisposition.REJECT
    assert outcome.dead_letter_reason is DeadLetterReason.ACQUIRE_REJECTED
    assert client.downloaded == []
    assert client.results == []
