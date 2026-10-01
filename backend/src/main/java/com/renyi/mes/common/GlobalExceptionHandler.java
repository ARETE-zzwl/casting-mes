package com.renyi.mes.common;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.ErrorResponse;

@RestControllerAdvice
class GlobalExceptionHandler {
	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(DomainException.class)
	ResponseEntity<ApiError> handleDomainException(DomainException exception) {
		return ResponseEntity
			.status(exception.status())
			.body(ApiError.of(exception.code(), exception.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
		List<ApiError.FieldViolation> details = exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> new ApiError.FieldViolation(error.getField(), error.getDefaultMessage()))
			.toList();

		return ResponseEntity
			.unprocessableContent()
			.body(ApiError.of("VALIDATION_ERROR", "请求参数校验失败", details));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> handleUnreadableMessage() {
		return ResponseEntity
			.badRequest()
			.body(ApiError.of("INVALID_REQUEST", "请求内容格式不正确"));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ApiError> handleDataConflict() {
		return ResponseEntity
			.status(HttpStatus.CONFLICT)
			.body(ApiError.of("DATA_CONFLICT", "数据已存在或与当前状态冲突"));
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ResponseEntity<ApiError> handleConcurrentModification() {
		return ResponseEntity
			.status(HttpStatus.CONFLICT)
			.body(ApiError.of(
				"CONCURRENT_MODIFICATION",
				"数据已被其他用户更新，请刷新后重试"
			));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> handleUnexpectedFailure(Exception exception) {
		if (exception instanceof ErrorResponse error) {
			return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
				.body(ApiError.of("HTTP_REQUEST_ERROR", "请求无法处理，请检查地址、参数和请求方式"));
		}
		String errorId = UUID.randomUUID().toString();
		// Record code locations, but never exception messages, SQL values, or request bodies.
		log.error("API failure id={} type={} frames={}", errorId, exception.getClass().getName(),
			java.util.Arrays.toString(exception.getStackTrace()));
		return ResponseEntity
			.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.header("X-Error-Id", errorId)
			.body(ApiError.of("INTERNAL_ERROR", "服务处理失败，请稍后重试，参考号：" + errorId));
	}
}
