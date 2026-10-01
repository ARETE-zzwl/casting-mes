package com.renyi.mes.engineering;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class RouteCatalogTests {

	private final RouteCatalog catalog = new RouteCatalog();

	@Test
	void definesTheCompleteMidTemperatureWaxRoute() {
		assertThat(codesFor(RouteType.MID_TEMP_WAX)).containsExactly(
			"WAX_INJECTION", "WAX_REPAIR", "TREE_ASSEMBLY",
			"SHELL_BUILDING", "DEWAX", "POURING", "KNOCKOUT", "CUTTING",
			"SEMI_FINISHED_COUNT", "OPTIONAL_FINISHING", "FINAL_COUNT");
	}

	@Test
	void keepsLowTemperatureWaxOnTheManualShellRoute() {
		assertThat(codesFor(RouteType.LOW_TEMP_WAX)).containsExactly(
			"WAX_INJECTION", "WAX_REPAIR", "TREE_ASSEMBLY",
			"MANUAL_SHELL_BUILDING", "DEWAX", "POURING", "KNOCKOUT", "CUTTING",
			"SEMI_FINISHED_COUNT", "OPTIONAL_FINISHING", "FINAL_COUNT");
	}

	@Test
	void retainsTheOutsourcedSandCastingRoute() {
		assertThat(codesFor(RouteType.SAND_OUTSOURCE)).containsExactly(
			"OUTSOURCE_DISPATCH", "OUTSOURCE_PROGRESS", "INCOMING_INSPECTION");
	}

	private List<String> codesFor(RouteType routeType) {
		return catalog.operationsFor(routeType).stream().map(RouteCatalog.RouteOperation::code).toList();
	}
}
