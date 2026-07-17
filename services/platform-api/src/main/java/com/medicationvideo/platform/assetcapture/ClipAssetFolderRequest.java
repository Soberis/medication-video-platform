package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

record ClipAssetFolderRequest(@NotNull UUID folderId) {
}
