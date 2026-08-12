from __future__ import annotations

from collections import Counter
from dataclasses import replace
from fractions import Fraction

import pytest
from conftest import GoldenData

from golden_capture_g0.media import sha256_file
from golden_capture_g0.model import SyncProfile
from golden_capture_g0.sync import (
    SynchronizationUnavailableError,
    build_frame_slots,
    input_manifest_sha256,
    logical_content_sha256,
    map_capture_to_source_time,
    map_source_to_capture_time,
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


def test_case_f_applies_cross_camera_offset_before_nearest_mapping(
    golden_data: GoldenData,
) -> None:
    capture = golden_data.captures["case_f_cross_camera_offset"]
    streams = golden_data.streams[capture.name]
    slots = build_frame_slots(capture, streams, PROFILE_30HZ_20MS)
    camera_two_start = next(
        slot for slot in slots if slot.camera_number == 2 and slot.capture_timestamp_ns == 0
    )
    first_camera_two_mapping = next(
        slot
        for slot in slots
        if slot.camera_number == 2 and slot.capture_timestamp_ns == 66_666_667
    )

    assert streams[1].frames[0].source_pts_ns == streams[2].frames[0].source_pts_ns == 0
    assert camera_two_start.frame_state == "MISSING"
    assert camera_two_start.frame_reason_code == "NO_FRAME_WITHIN_TOLERANCE"
    assert first_camera_two_mapping.frame_state == "PRESENT"
    assert first_camera_two_mapping.source_pts_ns == 0
    assert first_camera_two_mapping.sync_error_ns == 13_333_333
    assert first_camera_two_mapping.sync_error_ns != (
        first_camera_two_mapping.source_pts_ns - first_camera_two_mapping.capture_timestamp_ns
    )


def test_case_g_inverse_maps_target_and_forward_maps_drift_error(
    golden_data: GoldenData,
) -> None:
    capture = golden_data.captures["case_g_affine_drift"]
    streams = golden_data.streams[capture.name]
    mapping = streams[2].spec.time_mapping
    assert mapping is not None
    slots = build_frame_slots(capture, streams, PROFILE_30HZ_20MS)
    camera_two_at_one_second = next(
        slot
        for slot in slots
        if slot.camera_number == 2 and slot.capture_timestamp_ns == 1_000_000_000
    )

    assert map_capture_to_source_time(1_000_000_000, mapping) == Fraction(1_000_000_000_000, 1001)
    assert camera_two_at_one_second.source_pts_ns == 1_000_000_000
    assert map_source_to_capture_time(camera_two_at_one_second.source_pts_ns, mapping) == (
        1_001_000_000
    )
    assert camera_two_at_one_second.sync_error_ns == 1_000_000


def test_sync_rejects_stream_without_trusted_capture_mapping(
    golden_data: GoldenData,
) -> None:
    capture = golden_data.captures["case_f_cross_camera_offset"]
    original_streams = golden_data.streams[capture.name]
    stream = original_streams[2]
    mapping = stream.spec.time_mapping
    assert mapping is not None

    for unavailable_mapping in (None, replace(mapping, trusted=False)):
        streams = dict(original_streams)
        streams[2] = replace(stream, spec=replace(stream.spec, time_mapping=unavailable_mapping))
        with pytest.raises(SynchronizationUnavailableError) as error:
            build_frame_slots(capture, streams, PROFILE_30HZ_20MS)

        assert error.value.code == "TRUSTED_STREAM_TO_CAPTURE_MAPPING_REQUIRED"
        assert error.value.camera_number == 2


def test_single_anchor_cannot_claim_measured_drift(golden_data: GoldenData) -> None:
    capture = golden_data.captures["case_f_cross_camera_offset"]
    mapping = golden_data.streams[capture.name][2].spec.time_mapping
    assert mapping is not None

    with pytest.raises(ValueError, match="drift_ppm requires at least two anchors"):
        replace(mapping, drift_ppm=Fraction(1), anchor_count=1)


def test_time_mapping_is_part_of_frozen_input_manifest(golden_data: GoldenData) -> None:
    capture = golden_data.captures["case_f_cross_camera_offset"]
    streams = dict(golden_data.streams[capture.name])
    changed = dict(streams)
    original = changed[2]
    mapping = original.spec.time_mapping
    assert mapping is not None
    changed[2] = replace(
        original,
        spec=replace(
            original.spec,
            time_mapping=replace(mapping, anchor_capture_ns=mapping.anchor_capture_ns + 1),
        ),
    )

    assert input_manifest_sha256(capture, streams) != input_manifest_sha256(capture, changed)


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
