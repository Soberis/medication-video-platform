package com.medicationvideo.platform.assetcapture;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record CameraSetRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String layoutVersion,
		@NotEmpty @Size(max = 64) List<@Valid CameraSetMemberRequest> members) {

	CameraSetRequest {
		members = members == null ? null : List.copyOf(members);
	}
}
