from __future__ import annotations

import hashlib
import json
import logging
from datetime import UTC, datetime
from typing import Any
from uuid import NAMESPACE_URL, uuid5

import pika

from medication_media_worker.processor import (
    DeadLetterReason,
    DeliveryDisposition,
    JobProcessor,
    ProcessingOutcome,
)

LOGGER = logging.getLogger(__name__)

SAFE_SUMMARIES = {
    DeadLetterReason.CONTRACT_INVALID: "Message failed media job contract validation",
    DeadLetterReason.ACQUIRE_REJECTED: "Worker authorization or lease acquisition was rejected",
    DeadLetterReason.LEASE_REJECTED: "Current media job lease was rejected",
    DeadLetterReason.RESULT_REJECTED: "Media job result submission was rejected",
}


class DeadLetterPublishError(RuntimeError):
    """Sanitized diagnostic was not confirmed; supervisor restart is required."""


class MediaJobConsumer:
    def __init__(
        self,
        rabbitmq_url: str,
        queue_name: str,
        dead_letter_exchange: str,
        dead_letter_routing_key: str,
        processor: JobProcessor,
    ) -> None:
        self._rabbitmq_url = rabbitmq_url
        self._queue_name = queue_name
        self._dead_letter_exchange = dead_letter_exchange
        self._dead_letter_routing_key = dead_letter_routing_key
        self._processor = processor

    def run(self) -> None:
        parameters = pika.URLParameters(self._rabbitmq_url)
        parameters.heartbeat = 60
        parameters.blocked_connection_timeout = 30
        connection = pika.BlockingConnection(parameters)
        try:
            channel = connection.channel()
            channel.confirm_delivery()
            channel.basic_qos(prefetch_count=1)
            channel.basic_consume(
                queue=self._queue_name,
                on_message_callback=self._on_message,
                auto_ack=False,
            )
            LOGGER.info("Media Worker is consuming queue=%s", self._queue_name)
            channel.start_consuming()
        finally:
            if connection.is_open:
                connection.close()

    def _on_message(self, channel: Any, method: Any, properties: Any, body: bytes) -> None:
        del properties
        delivery_tag = method.delivery_tag
        outcome = self._processor.process(body)
        if outcome.disposition is DeliveryDisposition.ACK:
            channel.basic_ack(delivery_tag=delivery_tag)
        elif outcome.disposition is DeliveryDisposition.REQUEUE:
            channel.basic_nack(delivery_tag=delivery_tag, requeue=True)
        else:
            self._publish_sanitized_dead_letter(channel, delivery_tag, body, outcome)

    def _publish_sanitized_dead_letter(
        self,
        channel: Any,
        delivery_tag: int,
        body: bytes,
        outcome: ProcessingOutcome,
    ) -> None:
        reason = outcome.dead_letter_reason
        if reason is None:
            raise RuntimeError("Rejected delivery is missing a dead-letter reason")

        body_digest = hashlib.sha256(body).hexdigest()
        dead_letter_id = uuid5(
            NAMESPACE_URL,
            f"media-dead-letter.v1:{reason.value}:{body_digest}",
        )
        diagnostic: dict[str, object] = {
            "schema_version": "media-dead-letter.v1",
            "dead_letter_id": str(dead_letter_id),
            "observed_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            "reason_code": reason.value,
            "safe_summary": SAFE_SUMMARIES[reason],
            "body_sha256": body_digest,
            "body_size_bytes": len(body),
        }
        has_job_identity = (
            outcome.message_id is not None
            and outcome.job_id is not None
            and outcome.attempt is not None
        )
        if has_job_identity:
            diagnostic |= {
                "message_id": str(outcome.message_id),
                "job_id": str(outcome.job_id),
                "attempt": outcome.attempt,
            }
        diagnostic_body = json.dumps(
            diagnostic,
            separators=(",", ":"),
            sort_keys=True,
        ).encode()
        properties = pika.BasicProperties(
            content_type="application/json",
            delivery_mode=2,
            message_id=str(dead_letter_id),
            type="media-dead-letter.v1",
        )
        try:
            channel.basic_publish(
                exchange=self._dead_letter_exchange,
                routing_key=self._dead_letter_routing_key,
                body=diagnostic_body,
                properties=properties,
                mandatory=True,
            )
        except pika.exceptions.AMQPError as exc:
            LOGGER.error(
                "Sanitized dead-letter publish failed reason=%s job_id=%s",
                reason.value,
                outcome.job_id,
            )
            channel.basic_nack(delivery_tag=delivery_tag, requeue=True)
            raise DeadLetterPublishError(
                "Sanitized dead-letter publish was not confirmed"
            ) from exc

        LOGGER.warning(
            "Published sanitized dead-letter diagnostic reason=%s job_id=%s",
            reason.value,
            outcome.job_id,
        )
        channel.basic_ack(delivery_tag=delivery_tag)
