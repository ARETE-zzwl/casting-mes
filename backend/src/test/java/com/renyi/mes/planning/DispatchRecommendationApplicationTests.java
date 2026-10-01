package com.renyi.mes.planning;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DispatchRecommendationApplicationTests {

	@Test
	void recommendsFinishingOperatorsForSemiFinishedCounting() {
		assertThat(DispatchRecommendationApplication.rolesForOperation("SEMI_FINISHED_COUNT"))
			.contains("FINISHING_OPERATOR");
	}
}
