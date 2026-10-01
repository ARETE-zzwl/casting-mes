package com.renyi.mes.assistant;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-assistant")
class MesAiAssistantController {
	private final MesAiAssistantApplication assistant;
	MesAiAssistantController(MesAiAssistantApplication assistant) { this.assistant = assistant; }

	@PostMapping("/handoff/{sourceType}/{id}") MesAiAssistantApplication.AdviceView handoff(@PathVariable String sourceType, @PathVariable UUID id, @Valid @RequestBody OperatorRequest request) {
		return assistant.handoffAdvice(sourceType, id, request.operatorCode());
	}
	@PostMapping("/schedule-risk") MesAiAssistantApplication.AdviceView schedule(@Valid @RequestBody ScheduleRequest request) {
		return assistant.scheduleRisk(request.lineCode(), request.operatorCode());
	}
	@PostMapping("/furnace/{id}") MesAiAssistantApplication.AdviceView furnace(@PathVariable UUID id, @Valid @RequestBody OperatorRequest request) {
		return assistant.furnaceReview(id, request.operatorCode());
	}
	@PostMapping("/sop-draft") MesAiAssistantApplication.SopDraftView sop(@Valid @RequestBody SopRequest request) {
		return assistant.sopDraft(new MesAiAssistantApplication.SopDraftCommand(request.operationCode(), request.operationName(), request.engineeringRequirements(), request.routeType(), request.operatorCode()));
	}

	record OperatorRequest(@NotBlank @Size(max = 64) String operatorCode) { }
	record ScheduleRequest(@NotBlank @Size(max = 32) String lineCode, @NotBlank @Size(max = 64) String operatorCode) { }
	record SopRequest(@NotBlank @Size(max = 64) String operationCode, @NotBlank @Size(max = 120) String operationName,
			@Size(max = 2000) String engineeringRequirements, @Size(max = 32) String routeType, @NotBlank @Size(max = 64) String operatorCode) { }
}
