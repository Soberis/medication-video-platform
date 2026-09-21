"""
Example: Loading human_demonstration_hdf5.v2 demonstrations for imitation learning (PyTorch / NumPy).

Format specification:
  - demo_XXXXXX/samples/landmarks: shape (K, 2, 21, 3) where [:, 0] is LEFT hand, [:, 1] is RIGHT hand.
  - demo_XXXXXX/samples/hand_mask: shape (K, 2) where 1 indicates hand is detected, 0 otherwise.
  - demo_XXXXXX/samples/camera_number and frame_timestamp_ms: shape (K,); timestamps are local to each camera.
  - demo_XXXXXX/samples/label: shape (K,) string array of approved atomic step labels.
  - The sample key is (camera_number, frame_timestamp_ms); timestamps need not be unique across cameras.
"""

from pathlib import Path
import h5py
import numpy as np

try:
    import torch
    from torch.utils.data import Dataset
except ImportError:
    torch = None
    Dataset = object  # type: ignore[assignment,misc]


class HumanDemonstrationDataset(Dataset):
    """PyTorch Dataset (or NumPy demonstration container) for human-demonstration-hdf5.v2 files."""

    def __init__(self, hdf5_path: str | Path):
        self.hdf5_path = str(hdf5_path)
        with h5py.File(self.hdf5_path, "r") as f:
            schema = f.attrs.get("schema_version")
            if isinstance(schema, bytes):
                schema = schema.decode("utf-8")
            assert schema == "human-demonstration-hdf5.v2", (
                f"Expected schema_version human-demonstration-hdf5.v2, got {schema}"
            )
            self.demo_keys = sorted(list(f["data"].keys()))

    def __len__(self) -> int:
        return len(self.demo_keys)

    def __getitem__(self, idx: int) -> dict[str, object]:
        demo_key = self.demo_keys[idx]
        with h5py.File(self.hdf5_path, "r") as f:
            demo = f["data"][demo_key]
            samples = demo["samples"]

            raw_landmarks = samples["landmarks"][:]
            raw_mask = samples["hand_mask"][:]
            raw_scores = samples["handedness_score"][:]
            raw_timestamps = samples["frame_timestamp_ms"][:]
            if "camera_number" in samples:
                raw_camera_numbers = samples["camera_number"][:]
            else:
                # Read older single-camera v2 files where camera_number was a scalar attribute.
                camera_number = int(samples.attrs.get("camera_number", 0))
                raw_camera_numbers = np.full(len(raw_timestamps), camera_number, dtype=np.int32)

            if torch is not None:
                landmarks = torch.from_numpy(raw_landmarks)           # (K, 2, 21, 3) float32
                hand_mask = torch.from_numpy(raw_mask)                # (K, 2) int32
                scores = torch.from_numpy(raw_scores)                 # (K, 2) float64
                timestamps = torch.from_numpy(raw_timestamps)         # (K,) int64
                camera_numbers = torch.from_numpy(raw_camera_numbers)  # (K,) int32
            else:
                landmarks = raw_landmarks
                hand_mask = raw_mask
                scores = raw_scores
                timestamps = raw_timestamps
                camera_numbers = raw_camera_numbers
            labels = [
                lbl.decode("utf-8") if isinstance(lbl, bytes) else str(lbl)
                for lbl in samples["label"][:]
            ]

        return {
            "demo_id": demo_key,
            "landmarks": landmarks,
            "hand_mask": hand_mask,
            "handedness_score": scores,
            "camera_numbers": camera_numbers,
            "timestamps": timestamps,
            "labels": labels,
        }


if __name__ == "__main__":
    import sys

    if len(sys.argv) > 1:
        path = sys.argv[1]
        dataset = HumanDemonstrationDataset(path)
        print(f"Loaded {len(dataset)} demonstrations from {path}")
        sample = dataset[0]
        print(f"Demo 0 landmarks shape: {sample['landmarks'].shape}")
        print(f"Demo 0 hand_mask shape: {sample['hand_mask'].shape}")
        print(f"Demo 0 step count: {len(sample['timestamps'])}")
        print(f"Demo 0 camera numbers: {sample['camera_numbers']}")
        print(f"Demo 0 first 3 labels: {sample['labels'][:3]}")
    else:
        print("Usage: python load_demonstrations.py <path_to_v2_hdf5_file>")
