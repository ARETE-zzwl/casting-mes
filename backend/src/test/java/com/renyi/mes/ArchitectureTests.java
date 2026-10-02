package com.renyi.mes;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTests {

	private final ApplicationModules modules = ApplicationModules.of(MesBackendApplication.class);

	@Test
	void containsAllDeclaredModules() {
		assertThat(modules.stream().map(module -> module.getIdentifier().toString())).containsExactlyInAnyOrder(
			"assistant", "common", "configuration", "customerorder", "document", "engineering", "execution",
			"fulfillment", "identity", "integration", "inventory", "labor", "notification", "operations", "organization",
			"outsourcing", "piecework", "planning", "quality", "reporting", "resource", "sales", "simulation", "traceability", "workflow");
	}

	@Test
	void everyModuleDeclaresAnExplicitDependencyBoundary() throws Exception {
		String[] unrestricted = (String[]) org.springframework.modulith.ApplicationModule.class
			.getMethod("allowedDependencies").getDefaultValue();
		for (var module : modules) {
			String packageName = module.getBasePackage().getName();
			var declaration = Class.forName(packageName + ".package-info").getPackage()
				.getAnnotation(org.springframework.modulith.ApplicationModule.class);
			assertThat(declaration).as(packageName).isNotNull();
			assertThat(declaration.allowedDependencies()).as(packageName).doesNotContain(unrestricted);
		}
	}

	@Test
	void hasNoModuleDependencyViolations() {
		modules.verify();
	}
}
