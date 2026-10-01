package com.renyi.mes.execution.web;

import java.util.List;
import java.util.UUID;

import com.renyi.mes.execution.HandoffExceptionApplication;
import com.renyi.mes.execution.HandoffExceptionApplication.ExceptionView;
import com.renyi.mes.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/handoff-exceptions")
class HandoffExceptionController {
	private final HandoffExceptionApplication exceptions;
	HandoffExceptionController(HandoffExceptionApplication exceptions) { this.exceptions = exceptions; }
	@GetMapping List<ExceptionView> list(@RequestParam(defaultValue = "false") boolean includeResolved) { return exceptions.list(includeResolved); }
	@GetMapping("/query") PageResult<ExceptionView> query(
			@RequestParam(defaultValue = "false") boolean includeResolved,
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String resolutionStatus,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return exceptions.search(includeResolved, keyword, resolutionStatus, page, size);
	}
	@PostMapping("/{sourceType}/{id}/assign") ExceptionView assign(@PathVariable String sourceType, @PathVariable UUID id, @Valid @RequestBody AssignRequest request) {
		return exceptions.assign(sourceType, id, request.ownerCode(), request.assignedBy());
	}
	@PostMapping("/{sourceType}/{id}/resolve") ExceptionView resolve(@PathVariable String sourceType, @PathVariable UUID id, @Valid @RequestBody ResolveRequest request) {
		return exceptions.resolve(sourceType, id, request.resolutionNote(), request.resolvedBy());
	}
	record AssignRequest(@NotBlank @Size(max = 64) String ownerCode, @NotBlank @Size(max = 64) String assignedBy) { }
	record ResolveRequest(@NotBlank @Size(max = 1000) String resolutionNote, @NotBlank @Size(max = 64) String resolvedBy) { }
}
