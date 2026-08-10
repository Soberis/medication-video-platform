from __future__ import annotations

import logging
from typing import Any

import pika

from medication_ai_worker.processor import Disposition, Processor

LOGGER = logging.getLogger(__name__)


class AiConsumer:
    def __init__(self, rabbitmq_url: str, processor: Processor) -> None:
        self._rabbitmq_url = rabbitmq_url
        self._processor = processor

    def run(self) -> None:
        parameters = pika.URLParameters(self._rabbitmq_url)
        parameters.heartbeat = 60
        connection = pika.BlockingConnection(parameters)
        try:
            channel = connection.channel()
            channel.basic_qos(prefetch_count=1)
            channel.basic_consume(
                queue="platform.ai.hand-pose.v1",
                on_message_callback=self._on_message,
                auto_ack=False,
            )
            LOGGER.info("AI Worker is consuming queue=platform.ai.hand-pose.v1")
            channel.start_consuming()
        finally:
            if connection.is_open:
                connection.close()

    def _on_message(self, channel: Any, method: Any, _properties: Any, body: bytes) -> None:
        disposition = self._processor.process(body)
        if disposition is Disposition.ACK:
            channel.basic_ack(method.delivery_tag)
        elif disposition is Disposition.REQUEUE:
            channel.basic_nack(method.delivery_tag, requeue=True)
        else:
            channel.basic_reject(method.delivery_tag, requeue=False)
