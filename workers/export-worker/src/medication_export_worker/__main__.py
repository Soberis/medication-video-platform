from __future__ import annotations

import logging

from medication_export_worker.api import CoreApiClient
from medication_export_worker.config import Settings
from medication_export_worker.consumer import DatasetExportConsumer
from medication_export_worker.package_builder import DatasetPackageBuilder
from medication_export_worker.processor import JobProcessor


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    settings = Settings.from_environment()
    client = CoreApiClient(
        settings.platform_api_base_url,
        settings.worker_bearer_token,
        settings.worker_id,
    )
    processor = JobProcessor(
        client,
        DatasetPackageBuilder(),
        settings.software_digest,
        settings.temp_dir,
    )
    consumer = DatasetExportConsumer(
        settings.rabbitmq_url,
        settings.queue_name,
        settings.dead_letter_exchange,
        settings.dead_letter_routing_key,
        processor,
    )
    try:
        consumer.run()
    finally:
        client.close()


if __name__ == "__main__":
    main()
