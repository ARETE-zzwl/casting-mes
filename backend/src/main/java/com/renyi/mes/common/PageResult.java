package com.renyi.mes.common;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

	public static <T> PageResult<T> of(List<T> items, int page, int size, long totalElements) {
		return new PageResult<>(items, page, size, totalElements,
			(int) Math.ceil((double) totalElements / size));
	}
}
