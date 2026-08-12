from __future__ import annotations

from collections import Counter
from fractions import Fraction

from conftest import GoldenData

from golden_capture_g0.media import sha256_file
from golden_capture_g0.model import SyncProfile
from golden_capture_g0.sync import (
    build_frame_slots,
    input_manifest_sha256,
    logical_content_sha256,
)

PROFILE_30HZ_20MS = SyncProfile(Fraction(30), 20_000_000)


def test_case_a_complete_matrix_is_not_driven_by_manifest_order(golden_data: GoldenData) -> None:
    capture = golden_data.captures["case_a_complete_three_camera"]
    streams = golden_data.streams[capture.name]
    slots = build_frame_slots(capture, streams, PROFILE_30HZ_20MS)

    assert [stream.camera_number for stream in capture.streams] == [2, 1, 3]
    assert [(slot.sync_frame_index, slot.camera_number) for slot in slots] == sorted(
        (slot.sync_frame_index, slot.camera_number) for slot in slots
    )
    assert {slot.frame_state for slot in slots} == {"PRESENT"}


def test_case_b_missing_routes_keep_camera_numbers_and_slots(golden_data: GoldenData) -> None:
    capture = golden_data.captures["case_b_missing_routes"]
    slots = build_frame_slots(capture, golden_data.streams[capture.name], PROFILE_30HZ_20MS)

    assert capture.expected_camera_numbers == (1, 2, 3, 4, 5)
    assert sorted(golden_data.streams[capture.name]) == [1, 3, 5]
    missing_routes = [slot for slot in slots if slot.camera_number in {2, 4}]
    assert missing_routes
    assert {slot.frame_state for slot in missing_routes} == {"MISSING"}
    assert {slot.frame_reason_code for slot in missing_routes} == {"CAMERA_STREAM_MISSING"}
    assert {slot.camera_number for slot in slots} == {1, 2, 3, 4, 5}


def test_case_c_uses_pts_not_frame_array_index(golden_data: GoldenData) -> None:
    capture = golden_data.captures["case_c_different_fps"]
    streams = golden_data.streams[capture.name]
    slots = build_frame_slots(capture, streams, PROFILE_30HZ_20MS)
    camera_two = [slot for slot in slots if slot.camera_number == 2]

    assert streams[1].spec.source_rate == Fraction(30)
    assert streams[2].spec.source_rate == Fraction(25)
    assert all(slot.frame_state == "PRESENT" for slot in camera_two)
    assert any(slot.source_frame_index != slot.sync_frame_index for slot in camera_two)
    assert any(slot.sync_error_ns != 0 for slot in camera_two)


def test_tolerance_is_inclusive_and_excess_error_is_missing(golden_data: GoldenData) -> None:
    capture = golden_data.captures["case_c_different_fps"]
    streams = golden_data.streams[capture.name]
    strict_slots = build_frame_slots(capture, streams, SyncProfile(Fraction(30), 15_000_000))
    half_frame_slots = build_frame_slots(capture, streams, PROFILE_30HZ_20MS)

    assert any(
        slot.camera_number == 2
        and slot.frame_state == "MISSING"
        and slot.frame_reason_code == "NO_FRAME_WITHIN_TOLERANCE"
        for slot in strict_slots
    )
    assert all(
        slot.frame_state == "PRESENT" for slot in half_frame_slots if slot.camera_number == 2
    )
    assert any(
        slot.camera_number == 2 and abs(slot.sync_error_ns or 0) == 20_000_000
        for slot in half_frame_slots
    )


def test_nearest_tie_breaker_selects_earlier_pts(golden_data: GoldenData) -> None:
    capture = golden_data.captures["case_c_different_fps"]
    slots = build_frame_slots(
        capture,
        golden_data.streams[capture.name],
        SyncProfile(Fraction(10), 20_000_000),
    )
    tied = next(
        slot
        for slot in slots
        if slot.camera_number == 2 and slot.capture_timestamp_ns == 100_000_000
    )

    assert tied.frame_state == "PRESENT"
    assert tied.source_pts_ns == 80_000_000
    assert tied.sync_error_ns == -20_000_000


def test_case_d_keeps_missing_invalid_not_run_and_no_detection_distinct(
    golden_data: GoldenData,
) -> None:
    capture = golden_data.captures["case_d_local_gap_and_invalid"]
    slots = build_frame_slots(capture, golden_data.streams[capture.name], PROFILE_30HZ_20MS)
    states = Counter(slot.frame_state for slot in slots)
    invalid = [slot for slot in slots if slot.frame_state == "INVALID"]
    gap_missing = [
        slot
        for slot in slots
        if slot.camera_number == 3 and slot.frame_reason_code == "NO_FRAME_WITHIN_TOLERANCE"
    ]
    no_detection = [slot for slot in slots if slot.hand_detection_state == "NO_DETECTION"]

    assert states["INVALID"] >= 1
    assert states["MISSING"] >= 1
    assert invalid and all(
        slot.frame_reason_code == "SYNTHETIC_DECODE_VALIDATION_FAILED" for slot in invalid
    )
    assert gap_missing
    assert all(slot.hand_detection_state == "NOT_RUN" for slot in invalid + gap_missing)
    assert no_detection and all(slot.frame_state == "PRESENT" for slot in no_detection)
    assert any(
        slot.hand_detection_state == "NOT_RUN" and slot.frame_state == "PRESENT" for slot in slots
    )


def test_case_e_has_stable_vfr_and_nonzero_raw_pts(golden_data: GoldenData) -> None:
    capture = golden_data.captures["case_e_vfr_nonzero_pts"]
    cfr = golden_data.streams[capture.name][1]
    vfr = golden_data.streams[capture.name][2]
    raw_vfr_deltas = {
        current.raw_pts - previous.raw_pts
        for previous, current in zip(vfr.frames, vfr.frames[1:], strict=False)
    }
    raw_cfr_deltas = {
        current.raw_pts - previous.raw_pts
        for previous, current in zip(cfr.frames, cfr.frames[1:], strict=False)
    }
    normalized_vfr_deltas = {
        current.source_pts_ns - previous.source_pts_ns
        for previous, current in zip(vfr.frames, vfr.frames[1:], strict=False)
    }

    assert vfr.pts_origin > 0
    assert len(raw_cfr_deltas) == 1
    assert len(raw_vfr_deltas) > 1
    assert len(normalized_vfr_deltas) > 1
    assert vfr.frames[0].source_pts_ns == 0


def test_frozen_input_and_profile_are_order_independent_and_reproducible(
    golden_data: GoldenData,
) -> None:
    capture = golden_data.captures["case_b_missing_routes"]
    streams = golden_data.streams[capture.name]
    reversed_streams = dict(reversed(list(streams.items())))
    first = build_frame_slots(capture, streams, PROFILE_30HZ_20MS)
    second = build_frame_slots(capture, reversed_streams, PROFILE_30HZ_20MS)
    hashes_before = {camera: sha256_file(stream.path) for camera, stream in streams.items()}

    assert input_manifest_sha256(capture, streams) == input_manifest_sha256(
        capture, reversed_streams
    )
    assert logical_content_sha256(first) == logical_content_sha256(second)
    assert first == second
    assert hashes_before == {camera: sha256_file(stream.path) for camera, stream in streams.items()}


def test_present_slots_trace_source_pts_and_explicit_video_identity(
    golden_data: GoldenData,
) -> None:
    capture = golden_data.captures["case_a_complete_three_camera"]
    streams = golden_data.streams[capture.name]
    slots = build_frame_slots(capture, streams, PROFILE_30HZ_20MS)

    for slot in slots:
        source = streams[slot.camera_number]
        frame = source.frames[slot.source_frame_index or 0]
        assert slot.video_id == source.spec.video_id
        assert slot.source_pts_ns == frame.source_pts_ns
        assert frame.raw_pts >= source.pts_origin
