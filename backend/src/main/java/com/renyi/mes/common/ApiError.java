package com.renyi.mes.common;

import java.util.List;

public record ApiError(ErrorBody error) {

	public static ApiError of(String code, String message) {
		return new ApiError(new ErrorBody(code, message, List.of()));
	}

	public static ApiError of(String code, String message, List<FieldViolation> details) {
		return new ApiError(new ErrorBody(code, message, details));
	}

	public record ErrorBody(String code, String message, List<FieldViolation> details) {
	}

	public record FieldViolation(String field, String message) {
	}
}
