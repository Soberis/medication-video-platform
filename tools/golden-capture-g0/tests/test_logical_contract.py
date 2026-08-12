from __future__ import annotations

import json
from dataclasses import asdict
from fractions import Fraction
from pathlib import Path
from typing import Any, cast

from conftest import GoldenData
from jsonschema import Draft202012Validator

from golden_capture_g0.model import FrameSlot, SyncProfile
from golden_capture_g0.parquet_artifact import canonical_schema
from golden_capture_g0.sync import build_frame_slots


def test_synchronized_frame_contract_matches_parquet_logical_columns(
    golden_data: GoldenData,
) -> None:
    contract = _contract()
    Draft202012Validator.check_schema(contract)
    properties = cast(dict[str, Any], contract["properties"])

    assert list(properties) == canonical_schema().names
    assert set(cast(list[str], contract["required"])) == set(canonical_schema().names)
    sync_error = cast(dict[str, Any], properties["sync_error_ns"])
    assert "mapped_capture_time(source_frame)" in str(sync_error["description"])

    capture = golden_data.captures["case_d_local_gap_and_invalid"]
    slots = build_frame_slots(
        capture,
        golden_data.streams[capture.name],
        SyncProfile(Fraction(30), 20_000_000),
    )
    validator = Draft202012Validator(contract)
    for slot in slots:
        validator.validate(_logical_row(slot))


def test_contract_rejects_ai_conclusion_for_missing_slot(golden_data: GoldenData) -> None:
    contract = _contract()
    capture = golden_data.captures["case_b_missing_routes"]
    slots = build_frame_slots(
        capture,
        golden_data.streams[capture.name],
        SyncProfile(Fraction(30), 20_000_000),
    )
    missing = next(slot for slot in slots if slot.frame_state == "MISSING")
    row = _logical_row(missing)
    row["hand_detection_state"] = "NO_DETECTION"

    assert list(Draft202012Validator(contract).iter_errors(row))


def _contract() -> dict[str, Any]:
    repository_root = Path(__file__).resolve().parents[3]
    path = repository_root / "contracts" / "schemas" / "synchronized-frame-v1.schema.json"
    return cast(dict[str, Any], json.loads(path.read_text(encoding="utf-8")))


def _logical_row(slot: FrameSlot) -> dict[str, Any]:
    row = asdict(slot)
    row["model_sha256"] = slot.model_sha256.hex() if slot.model_sha256 else None
    row["profile_sha256"] = slot.profile_sha256.hex() if slot.profile_sha256 else None
    row["artifact_created_at"] = slot.artifact_created_at.isoformat().replace("+00:00", "Z")
    return row
