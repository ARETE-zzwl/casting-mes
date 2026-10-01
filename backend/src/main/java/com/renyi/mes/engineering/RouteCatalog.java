package com.renyi.mes.engineering;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class RouteCatalog {

	public List<RouteOperation> operationsFor(RouteType routeType) {
		return switch (routeType) {
			case MID_TEMP_WAX -> waxOperations("SHELL_BUILDING", "制壳");
			case LOW_TEMP_WAX -> waxOperations("MANUAL_SHELL_BUILDING", "人工制壳");
			case SAND_OUTSOURCE -> List.of(
				new RouteOperation("OUTSOURCE_DISPATCH", "外协发出"),
				new RouteOperation("OUTSOURCE_PROGRESS", "外协进度"),
				new RouteOperation("INCOMING_INSPECTION", "来料检验")
			);
		};
	}

	private static List<RouteOperation> waxOperations(String shellCode, String shellName) {
		return List.of(
			new RouteOperation("WAX_INJECTION", "射蜡"),
			new RouteOperation("WAX_REPAIR", "修蜡"),
			new RouteOperation("TREE_ASSEMBLY", "组树"),
			new RouteOperation(shellCode, shellName),
			new RouteOperation("DEWAX", "脱蜡"),
			new RouteOperation("POURING", "浇筑"),
			new RouteOperation("KNOCKOUT", "脱壳"),
			new RouteOperation("CUTTING", "分割"),
			new RouteOperation("SEMI_FINISHED_COUNT", "半成品清点"),
			new RouteOperation("OPTIONAL_FINISHING", "后处理"),
			new RouteOperation("FINAL_COUNT", "成品清点")
		);
	}

	public record RouteOperation(String code, String name) {
	}
}
