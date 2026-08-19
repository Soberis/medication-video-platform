package com.medicationvideo.platform.iamproject;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PlatformAuthenticationController.class)
class PlatformAuthenticationExceptionHandler {

	@ExceptionHandler(PlatformAuthenticationException.class)
	ResponseEntity<ProjectApiErrorResponse> denied() {
		return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码不正确。");
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ProjectApiErrorResponse> invalidRequest() {
		return error(HttpStatus.BAD_REQUEST, "INVALID_LOGIN_REQUEST", "请输入有效的账号和密码。");
	}

	private static ResponseEntity<ProjectApiErrorResponse> error(HttpStatus status, String errorCode,
			String errorMessage) {
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.cacheControl(CacheControl.noStore())
			.body(new ProjectApiErrorResponse(errorCode, errorMessage, false, UUID.randomUUID().toString(), null));
	}

}
