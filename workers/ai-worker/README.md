# AI Worker

Consumes `platform.ai.hand-pose.v1` and runs the offline MediaPipe Hand Landmarker. Configure exactly one of
`AI_WORKER_BEARER_TOKEN` (synthetic profiles only) or `AI_WORKER_BEARER_TOKEN_FILE`, plus `AI_HAND_POSE_MODEL_PATH`.
The model stays outside the repository; its SHA-256 must equal both `AI_HAND_POSE_MODEL_SHA256` and the queued job's
frozen `model_sha256`.

For the local approved model:

```powershell
$env:AI_HAND_POSE_MODEL_PATH='D:\medication-video-data\hand-pose\models\hand_landmarker.task'
$env:AI_HAND_POSE_MODEL_SHA256='fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1'
uv run ai-worker
```
