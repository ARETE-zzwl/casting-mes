package com.renyi.mes.engineering.web;

import java.util.List;
import java.util.UUID;

import com.renyi.mes.engineering.ProcessCardTemplateApplication;
import com.renyi.mes.engineering.ProcessCardTemplateApplication.TemplateView;
import com.renyi.mes.engineering.ProcessCardAiApplication;
import com.renyi.mes.engineering.ProcessCardAiApplication.SuggestionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/process-card-templates")
class ProcessCardTemplateController {
	private final ProcessCardTemplateApplication templates;
	private final ProcessCardAiApplication ai;
	ProcessCardTemplateController(ProcessCardTemplateApplication templates, ProcessCardAiApplication ai) {
		this.templates = templates;
		this.ai = ai;
	}

	@GetMapping
	List<TemplateView> list(@RequestParam(required = false) UUID productId,
			@RequestParam(defaultValue = "false") boolean publishedOnly) { return templates.list(productId, publishedOnly); }

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	TemplateView create(@Valid @RequestBody CreateRequest request) {
		return templates.create(new ProcessCardTemplateApplication.CreateCommand(request.productId(), request.version(),
			request.engineeringParameters(), request.operationParameters(), request.createdBy()));
	}

	@PostMapping("/{id}/publish")
	TemplateView publish(@PathVariable UUID id, @Valid @RequestBody PublishRequest request) { return templates.publish(id, request.publishedBy()); }

	@PostMapping("/ai-suggestion")
	SuggestionView suggest(@Valid @RequestBody AiSuggestionRequest request) {
		return ai.suggest(new ProcessCardAiApplication.SuggestionCommand(request.productId(), request.sourceTemplateId(),
			request.currentEngineeringParameters(), request.currentOperationParameters(), request.operatorCode()));
	}

	record CreateRequest(@NotNull UUID productId, @NotBlank @Size(max = 64) String version,
			@NotBlank @Size(max = 2000) String engineeringParameters, @NotBlank @Size(max = 10000) String operationParameters,
			@NotBlank @Size(max = 64) String createdBy) { }
	record PublishRequest(@NotBlank @Size(max = 64) String publishedBy) { }
	record AiSuggestionRequest(@NotNull UUID productId, UUID sourceTemplateId,
			@Size(max = 2000) String currentEngineeringParameters, @Size(max = 10000) String currentOperationParameters,
			@NotBlank @Size(max = 64) String operatorCode) { }
}
