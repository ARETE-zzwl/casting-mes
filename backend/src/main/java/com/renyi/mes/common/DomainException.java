package com.renyi.mes.common;

import org.springframework.http.HttpStatus;

public final class DomainException extends RuntimeException {

	private final String code;
	private final HttpStatus status;

	private DomainException(String code, String message, HttpStatus status) {
		super(message);
		this.code = code;
		this.status = status;
	}

	public static DomainException badRequest(String code, String message) {
		return new DomainException(code, message, HttpStatus.BAD_REQUEST);
	}

	public static DomainException notFound(String code, String message) {
		return new DomainException(code, message, HttpStatus.NOT_FOUND);
	}

	public static DomainException conflict(String code, String message) {
		return new DomainException(code, message, HttpStatus.CONFLICT);
	}

	public static DomainException forbidden(String code, String message) {
		return new DomainException(code, message, HttpStatus.FORBIDDEN);
	}

	public static DomainException unauthorized(String code, String message) {
		return new DomainException(code, message, HttpStatus.UNAUTHORIZED);
	}

	public String code() {
		return code;
	}

	public HttpStatus status() {
		return status;
	}
}
