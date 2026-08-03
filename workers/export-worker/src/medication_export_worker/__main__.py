from __future__ import annotations

import logging

from medication_export_worker.api import CoreApiClient
from medication_export_worker.config import Settings
from medication_export_worker.consumer import DatasetExportConsumer
from medication_export_worker.package_builder import DatasetPackageBuilder
from medication_export_worker.processor import (
    JobProcessor,
    PackageBuilder,
    SyntheticFirstAttemptFailureBuilder,
)


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    settings = Settings.from_environment()
    client = CoreApiClient(
        settings.platform_api_base_url,
        settings.read_worker_bearer_token,
        settings.worker_id,
    )
    builder: PackageBuilder = DatasetPackageBuilder()
    if settings.synthetic_fail_first_attempt:
        builder = SyntheticFirstAttemptFailureBuilder(builder)
    processor = JobProcessor(
        client,
        builder,
        settings.software_digest,
        settings.temp_dir,
        settings.synthetic_lease_loss_first_attempt,
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
