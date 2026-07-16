from __future__ import annotations

import hashlib
import json
from types import SimpleNamespace
from typing import Any, cast
from uuid import UUID

import pika
import pytest

from medication_media_worker.consumer import DeadLetterPublishError, MediaJobConsumer
from medication_media_worker.processor import (
    DeadLetterReason,
    DeliveryDisposition,
    JobProcessor,
    ProcessingOutcome,
)


class FakeProcessor:
    def __init__(self, outcome: ProcessingOutcome) -> None:
        self.outcome = outcome

    def process(self, body: bytes) -> ProcessingOutcome:
        del body
        return self.outcome


class FakeChannel:
    def __init__(self, *, publish_error: bool = False) -> None:
        self.publish_error = publish_error
        self.published: dict[str, Any] | None = None
        self.acked: list[int] = []
        self.nacked: list[tuple[int, bool]] = []

    def basic_publish(self, **kwargs: Any) -> None:
        if self.publish_error:
            raise pika.exceptions.AMQPError("synthetic publish failure")
        self.published = kwargs

    def basic_ack(self, *, delivery_tag: int) -> None:
        self.acked.append(delivery_tag)

    def basic_nack(self, *, delivery_tag: int, requeue: bool) -> None:
        self.nacked.append((delivery_tag, requeue))


def rejected_outcome() -> ProcessingOutcome:
    return ProcessingOutcome(
        DeliveryDisposition.REJECT,
        DeadLetterReason.ACQUIRE_REJECTED,
        UUID("10000000-0000-0000-0000-000000000001"),
        UUID("20000000-0000-0000-0000-000000000002"),
        1,
    )


def consumer(processor: FakeProcessor) -> MediaJobConsumer:
    return MediaJobConsumer(
        "amqp://local.invalid",
        "platform.media.probe.v1",
        "platform.dead-letter.v1",
        "media.dead-letter.v1",
        cast(JobProcessor, processor),
    )


def test_publishes_only_sanitized_diagnostic_before_ack(job_body: bytes) -> None:
    channel = FakeChannel()

    consumer(FakeProcessor(rejected_outcome()))._on_message(
        channel,
        SimpleNamespace(delivery_tag=7),
        None,
        job_body,
    )

    assert channel.acked == [7]
    assert channel.nacked == []
    assert channel.published is not None
    assert channel.published["exchange"] == "platform.dead-letter.v1"
    assert channel.published["routing_key"] == "media.dead-letter.v1"
    assert channel.published["mandatory"] is True
    properties = channel.published["properties"]
    assert properties.delivery_mode == 2
    assert properties.content_type == "application/json"

    diagnostic = json.loads(channel.published["body"])
    assert set(diagnostic) == {
        "schema_version",
        "dead_letter_id",
        "observed_at",
        "reason_code",
        "safe_summary",
        "body_sha256",
        "body_size_bytes",
        "message_id",
        "job_id",
        "attempt",
    }
    assert diagnostic["reason_code"] == "MEDIA_JOB_ACQUIRE_REJECTED"
    assert diagnostic["body_sha256"] == hashlib.sha256(job_body).hexdigest()
    assert diagnostic["body_size_bytes"] == len(job_body)
    serialized = channel.published["body"].decode()
    assert "lease_token" not in serialized
    assert "AAAAAAAA" not in serialized
    assert "input_sha256" not in serialized


def test_requeues_original_when_sanitized_publish_is_not_confirmed(job_body: bytes) -> None:
    channel = FakeChannel(publish_error=True)

    with pytest.raises(DeadLetterPublishError):
        consumer(FakeProcessor(rejected_outcome()))._on_message(
            channel,
            SimpleNamespace(delivery_tag=9),
            None,
            job_body,
        )

    assert channel.acked == []
    assert channel.nacked == [(9, True)]
