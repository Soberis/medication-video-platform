package com.medicationvideo.platform.aicandidate;

import java.util.List;

record HandPoseCandidateResponse(HandPoseJobResponse job, List<HandPoseCandidate> candidates) {
}
