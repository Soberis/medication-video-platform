from __future__ import annotations

import logging

from medication_media_worker.api import CoreApiClient
from medication_media_worker.config import Settings
from medication_media_worker.consumer import MediaJobConsumer
from medication_media_worker.probe import MediaProber
from medication_media_worker.processor import JobProcessor


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
    try:
        prober = MediaProber(
            settings.ffprobe_path,
            settings.ffmpeg_path,
            settings.probe_timeout_seconds,
        )
        processor = JobProcessor(client, prober, settings.software_digest, settings.temp_dir)
        MediaJobConsumer(settings.rabbitmq_url, settings.queue_name, processor).run()
    finally:
        client.close()


if __name__ == "__main__":
    main()
