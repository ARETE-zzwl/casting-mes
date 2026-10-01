package com.renyi.mes.planning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.renyi.mes.common.DomainException;
import com.renyi.mes.planning.TaskStatus;
import org.junit.jupiter.api.Test;

class ProductionTaskEntityTests {

	@Test
	void completesOnlyWhenReportedQuantityReachesPlan() {
		ProductionTaskEntity task = taskWithPlan("10");
		task.assign("W001");
		task.start("W001", Instant.now());

		task.report(new BigDecimal("6"), BigDecimal.ZERO, "W001", Instant.now());
		assertThat(task.status()).isEqualTo(TaskStatus.IN_PROGRESS);
		assertThat(task.goodQuantity()).isEqualByComparingTo("6");

		task.report(new BigDecimal("3"), BigDecimal.ONE, "W001", Instant.now());
		assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);
		assertThat(task.goodQuantity()).isEqualByComparingTo("9");
		assertThat(task.scrapQuantity()).isEqualByComparingTo("1");
	}

	@Test
	void rejectsNegativeOrExcessiveReportQuantity() {
		ProductionTaskEntity task = taskWithPlan("10");
		task.assign("W001");
		task.start("W001", Instant.now());

		assertThatThrownBy(() ->
			task.report(new BigDecimal("-1"), new BigDecimal("2"), "W001", Instant.now())
		).isInstanceOf(DomainException.class)
			.hasMessage("合格数和报废数不能为负数");

		assertThatThrownBy(() ->
			task.report(new BigDecimal("11"), BigDecimal.ZERO, "W001", Instant.now())
		).isInstanceOf(DomainException.class)
			.hasMessage("累计报工数量不能超过任务计划数量");
	}

	@Test
	void rejectsOperationByUnassignedWorker() {
		ProductionTaskEntity task = taskWithPlan("10");
		task.assign("W001");

		assertThatThrownBy(() -> task.start("W002", Instant.now()))
			.isInstanceOf(DomainException.class)
			.hasMessage("任务只能由被分派人员操作");
	}

	private static ProductionTaskEntity taskWithPlan(String quantity) {
		return new ProductionTaskEntity(
			UUID.randomUUID(),
			"TK-TEST",
			UUID.randomUUID(),
			1,
			"WAX_INJECTION",
			"射蜡",
			new BigDecimal(quantity),
			BigDecimal.ZERO,
			BigDecimal.ZERO,
			TaskStatus.READY,
			Instant.now()
		);
	}
}
