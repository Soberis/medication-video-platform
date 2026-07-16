from __future__ import annotations

import logging
from typing import Any

import pika

from medication_media_worker.processor import DeliveryDisposition, JobProcessor

LOGGER = logging.getLogger(__name__)


class MediaJobConsumer:
    def __init__(self, rabbitmq_url: str, queue_name: str, processor: JobProcessor) -> None:
        self._rabbitmq_url = rabbitmq_url
        self._queue_name = queue_name
        self._processor = processor

    def run(self) -> None:
        parameters = pika.URLParameters(self._rabbitmq_url)
        parameters.heartbeat = 60
        parameters.blocked_connection_timeout = 30
        connection = pika.BlockingConnection(parameters)
        try:
            channel = connection.channel()
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
        disposition = self._processor.process(body)
        if disposition is DeliveryDisposition.ACK:
            channel.basic_ack(delivery_tag=delivery_tag)
        elif disposition is DeliveryDisposition.REQUEUE:
            channel.basic_nack(delivery_tag=delivery_tag, requeue=True)
        else:
            channel.basic_reject(delivery_tag=delivery_tag, requeue=False)
