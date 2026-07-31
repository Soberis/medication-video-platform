from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime
from typing import Any, cast
from uuid import NAMESPACE_URL, uuid5

import pika
import pytest

from medication_export_worker.consumer import (
    SAFE_SUMMARIES,
    DatasetExportConsumer,
    DeadLetterPublishError,
)
from medication_export_worker.processor import (
    DeadLetterReason,
    DeliveryDisposition,
    ProcessingOutcome,
)


@dataclass(frozen=True, slots=True)
class Delivery:
    delivery_tag: int


class StaticProcessor:
    def __init__(self, outcome: ProcessingOutcome) -> None:
        self.outcome = outcome
        self.bodies: list[bytes] = []

    def process(self, body: bytes) -> ProcessingOutcome:
        self.bodies.append(body)
        return self.outcome


class RecordingChannel:
    def __init__(self, publish_error: pika.exceptions.AMQPError | None = None) -> None:
        self.publish_error = publish_error
        self.acknowledged: list[int] = []
        self.nacked: list[tuple[int, bool]] = []
        self.published: list[dict[str, object]] = []

    def basic_ack(self, *, delivery_tag: int) -> None:
        self.acknowledged.append(delivery_tag)

    def basic_nack(self, *, delivery_tag: int, requeue: bool) -> None:
        self.nacked.append((delivery_tag, requeue))

    def basic_publish(self, **kwargs: object) -> None:
        self.published.append(kwargs)
        if self.publish_error is not None:
            raise self.publish_error


def consumer(processor: StaticProcessor) -> DatasetExportConsumer:
    return DatasetExportConsumer(
        "amqp://guest:guest@rabbitmq.invalid/%2F",
        "dataset-export-jobs",
        "dataset-export-dlx",
        "dataset-export-dead-letter",
        processor,
    )


def test_acknowledges_completed_processing() -> None:
    processor = StaticProcessor(ProcessingOutcome(DeliveryDisposition.ACK))
    channel = RecordingChannel()
    body = b'{"schema_version":"dataset-export-job.v1"}'

    consumer(processor)._on_message(channel, Delivery(17), None, body)

    assert processor.bodies == [body]
    assert channel.acknowledged == [17]
    assert channel.nacked == []
    assert channel.published == []


def test_requeues_transient_processing_failure() -> None:
    processor = StaticProcessor(ProcessingOutcome(DeliveryDisposition.REQUEUE))
    channel = RecordingChannel()

    consumer(processor)._on_message(channel, Delivery(18), None, b"job")

    assert channel.acknowledged == []
    assert channel.nacked == [(18, True)]
    assert channel.published == []


def test_publishes_sanitized_dead_letter_before_acknowledging_rejection() -> None:
    reason = DeadLetterReason.CONTRACT_INVALID
    processor = StaticProcessor(ProcessingOutcome(DeliveryDisposition.REJECT, reason))
    channel = RecordingChannel()
    body = b'{"lease_token":"must-not-be-copied"}'

    consumer(processor)._on_message(channel, Delivery(19), None, body)

    assert channel.acknowledged == [19]
    assert channel.nacked == []
    assert len(channel.published) == 1
    published = channel.published[0]
    assert published["exchange"] == "dataset-export-dlx"
    assert published["routing_key"] == "dataset-export-dead-letter"
    assert published["mandatory"] is True
    diagnostic: dict[str, object] = json.loads(cast(bytes, published["body"]))
    body_digest = hashlib.sha256(body).hexdigest()
    expected_id = uuid5(
        NAMESPACE_URL,
        f"dataset-export-dead-letter.v1:{reason.value}:{body_digest}",
    )
    observed_at = diagnostic.pop("observed_at")
    assert isinstance(observed_at, str)
    assert observed_at.endswith("Z")
    assert datetime.fromisoformat(observed_at.replace("Z", "+00:00")).tzinfo is not None
    assert diagnostic == {
        "schema_version": "dataset-export-dead-letter.v1",
        "dead_letter_id": str(expected_id),
        "reason_code": reason.value,
        "safe_summary": SAFE_SUMMARIES[reason],
        "body_sha256": body_digest,
        "body_size_bytes": len(body),
    }
    assert b"must-not-be-copied" not in cast(bytes, published["body"])
    properties = cast(Any, published["properties"])
    assert properties.content_type == "application/json"
    assert properties.delivery_mode == 2
    assert properties.message_id == str(expected_id)
    assert properties.type == "dataset-export-dead-letter.v1"


def test_requeues_when_sanitized_dead_letter_cannot_be_confirmed() -> None:
    processor = StaticProcessor(
        ProcessingOutcome(DeliveryDisposition.REJECT, DeadLetterReason.ACQUIRE_REJECTED)
    )
    channel = RecordingChannel(pika.exceptions.AMQPError("broker unavailable"))

    with pytest.raises(DeadLetterPublishError):
        consumer(processor)._on_message(channel, Delivery(20), None, b"job")

    assert channel.acknowledged == []
    assert channel.nacked == [(20, True)]
    assert len(channel.published) == 1


def test_rejected_outcome_without_reason_is_not_acknowledged() -> None:
    processor = StaticProcessor(ProcessingOutcome(DeliveryDisposition.REJECT))
    channel = RecordingChannel()

    with pytest.raises(RuntimeError, match="missing a dead-letter reason"):
        consumer(processor)._on_message(channel, Delivery(21), None, b"job")

    assert channel.acknowledged == []
    assert channel.nacked == []
    assert channel.published == []
