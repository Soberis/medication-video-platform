package com.medicationvideo.platform.iamproject;

import jakarta.validation.constraints.NotNull;

record ProjectMembershipRequest(@NotNull ProjectRole role) {
}
