from __future__ import annotations

import logging

from medication_ai_worker.api import CoreApiClient
from medication_ai_worker.config import Settings
from medication_ai_worker.consumer import AiConsumer
from medication_ai_worker.processor import Processor


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    settings = Settings.from_environment()
    client = CoreApiClient(
        settings.platform_api_base_url, settings.read_bearer_token, settings.worker_id
    )
    try:
        AiConsumer(
            settings.rabbitmq_url,
            Processor(client, settings),
            settings.job_queues,
        ).run()
    finally:
        client.close()


if __name__ == "__main__":
    main()
