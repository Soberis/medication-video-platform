from __future__ import annotations

import hashlib
import json
from types import SimpleNamespace
from typing import Any, cast
from uuid import UUID

import pika
import pytest

from medication_ai_worker.config import ACTION_QUEUE, HAND_POSE_QUEUE
from medication_ai_worker.consumer import AiConsumer, DeadLetterPublishError
from medication_ai_worker.processor import (
    DeadLetterReason,
    Disposition,
    ProcessingOutcome,
    Processor,
)


class _FakeProcessor:
    def __init__(self, outcome: ProcessingOutcome) -> None:
        self.outcome = outcome

    def process_delivery(self, body: bytes) -> ProcessingOutcome:
        del body
        return self.outcome


class _FakeChannel:
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


class _FakeConsumeChannel(_FakeChannel):
    def __init__(self) -> None:
        super().__init__()
        self.confirmed = False
        self.prefetch: int | None = None
        self.subscribed: list[str] = []
        self.consuming = False

    def confirm_delivery(self) -> None:
        self.confirmed = True

    def basic_qos(self, *, prefetch_count: int) -> None:
        self.prefetch = prefetch_count

    def basic_consume(self, *, queue: str, on_message_callback: Any, auto_ack: bool) -> None:
        del on_message_callback, auto_ack
        self.subscribed.append(queue)

    def start_consuming(self) -> None:
        self.consuming = True


class _FakeConnection:
    def __init__(self, channel: _FakeConsumeChannel) -> None:
        self.is_open = True
        self._channel = channel

    def channel(self) -> _FakeConsumeChannel:
        return self._channel

    def close(self) -> None:
        self.is_open = False


def _consumer(outcome: ProcessingOutcome) -> AiConsumer:
    return AiConsumer("amqp://local.invalid", cast(Processor, _FakeProcessor(outcome)))


def test_reconnects_after_broker_connection_interruption(monkeypatch: pytest.MonkeyPatch) -> None:
    sleep_calls: list[float] = []
    consumer = AiConsumer(
        "amqp://local.invalid",
        cast(Processor, _FakeProcessor(ProcessingOutcome(Disposition.ACK))),
        sleep=sleep_calls.append,
    )
    outcomes: list[BaseException] = [
        pika.exceptions.StreamLostError("synthetic connection loss"),
        KeyboardInterrupt(),
    ]

    def consume_once() -> None:
        raise outcomes.pop(0)

    monkeypatch.setattr(consumer, "_consume_once", consume_once)

    with pytest.raises(KeyboardInterrupt):
        consumer.run()

    assert sleep_calls == [2.0]


def test_publishes_only_sanitized_diagnostic_before_ack() -> None:
    message_id = UUID("10000000-0000-0000-0000-000000000001")
    job_id = UUID("20000000-0000-0000-0000-000000000002")
    outcome = ProcessingOutcome(
        Disposition.REJECT,
        DeadLetterReason.ACQUIRE_REJECTED,
        message_id,
        job_id,
        1,
    )
    body = json.dumps(
        {
            "lease_token": "A" * 43,
            "input_url": "https://signed.example/input?token=secret",
            "model_sha256": "b" * 64,
        }
    ).encode()
    channel = _FakeChannel()

    _consumer(outcome)._on_message(channel, SimpleNamespace(delivery_tag=7), None, body)

    assert channel.acked == [7]
    assert channel.nacked == []
    assert channel.published is not None
    assert channel.published["exchange"] == "platform.dead-letter.v1"
    assert channel.published["routing_key"] == "ai.dead-letter.v1"
    assert channel.published["mandatory"] is True
    diagnostic = json.loads(channel.published["body"])
    assert diagnostic["body_sha256"] == hashlib.sha256(body).hexdigest()
    assert diagnostic["message_id"] == str(message_id)
    assert diagnostic["job_id"] == str(job_id)
    serialized = channel.published["body"].decode()
    assert "lease_token" not in serialized
    assert "signed.example" not in serialized
    assert "secret" not in serialized
    assert "Bearer" not in serialized


def test_requeues_original_when_sanitized_publish_is_not_confirmed() -> None:
    outcome = ProcessingOutcome(Disposition.REJECT, DeadLetterReason.CONTRACT_INVALID)
    channel = _FakeChannel(publish_error=True)
    body = b'{"lease_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}'

    with pytest.raises(DeadLetterPublishError):
        _consumer(outcome)._on_message(channel, SimpleNamespace(delivery_tag=9), None, body)

    assert channel.acked == []
    assert channel.nacked == [(9, True)]


def test_consumes_every_configured_queue_on_one_channel(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    channel = _FakeConsumeChannel()
    connection = _FakeConnection(channel)
    monkeypatch.setattr(pika, "BlockingConnection", lambda _parameters: connection)
    consumer = AiConsumer(
        "amqp://local.invalid",
        cast(Processor, _FakeProcessor(ProcessingOutcome(Disposition.ACK))),
        (HAND_POSE_QUEUE, ACTION_QUEUE),
    )

    consumer._consume_once()

    assert channel.subscribed == [HAND_POSE_QUEUE, ACTION_QUEUE]
    assert channel.prefetch == 1
    assert channel.confirmed is True
    assert channel.consuming is True
    assert connection.is_open is False


def test_defaults_to_the_hand_pose_queue_when_none_is_declared(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    channel = _FakeConsumeChannel()
    monkeypatch.setattr(pika, "BlockingConnection", lambda _parameters: _FakeConnection(channel))

    AiConsumer(
        "amqp://local.invalid",
        cast(Processor, _FakeProcessor(ProcessingOutcome(Disposition.ACK))),
    )._consume_once()

    assert channel.subscribed == [HAND_POSE_QUEUE]


def test_rejects_an_empty_queue_configuration() -> None:
    with pytest.raises(ValueError, match="at least one job queue"):
        AiConsumer(
            "amqp://local.invalid",
            cast(Processor, _FakeProcessor(ProcessingOutcome(Disposition.ACK))),
            (),
        )
