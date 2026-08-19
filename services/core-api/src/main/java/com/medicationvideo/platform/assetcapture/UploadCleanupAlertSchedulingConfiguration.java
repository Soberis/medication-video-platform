package com.medicationvideo.platform.assetcapture;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "platform.upload.cleanup-alert.enabled", havingValue = "true", matchIfMissing = true)
class UploadCleanupAlertSchedulingConfiguration {

}
