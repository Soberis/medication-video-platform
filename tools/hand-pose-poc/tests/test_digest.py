from __future__ import annotations

import hashlib
from pathlib import Path

from hand_pose_poc.digest import sha256_file


def test_model_digest_is_sha256_of_exact_bytes(tmp_path: Path) -> None:
    model = tmp_path / "hand_landmarker.task"
    model.write_bytes(b"synthetic-model-bytes")

    assert sha256_file(model) == hashlib.sha256(b"synthetic-model-bytes").hexdigest()
