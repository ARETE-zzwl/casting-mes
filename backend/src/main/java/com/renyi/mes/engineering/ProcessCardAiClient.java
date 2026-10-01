package com.renyi.mes.engineering;

import java.util.Map;

public interface ProcessCardAiClient {

	Suggestion generate(Prompt prompt);

	String completeJson(String systemPrompt, String userPrompt);

	record Prompt(String productCode, String productName, String routeType, String specification, String material,
			String currentEngineeringParameters, Map<String, String> currentOperationParameters,
			String sourceEngineeringParameters, Map<String, String> sourceOperationParameters) { }

	record Suggestion(String engineeringParameters, Map<String, String> operationParameters, String note) { }
}
