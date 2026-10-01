package com.renyi.mes;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTests {

	private final ApplicationModules modules = ApplicationModules.of(MesBackendApplication.class);

	@Test
	void containsInitialBusinessModules() {
		assertThat(modules.getModuleByName("customerorder")).isPresent();
		assertThat(modules.getModuleByName("engineering")).isPresent();
		assertThat(modules.getModuleByName("planning")).isPresent();
		assertThat(modules.getModuleByName("execution")).isPresent();
		assertThat(modules.getModuleByName("traceability")).isPresent();
		assertThat(modules.getModuleByName("quality")).isPresent();
		assertThat(modules.getModuleByName("inventory")).isPresent();
		assertThat(modules.getModuleByName("piecework")).isPresent();
		assertThat(modules.getModuleByName("outsourcing")).isPresent();
		assertThat(modules.getModuleByName("reporting")).isPresent();
		assertThat(modules.getModuleByName("workflow")).isPresent();
		assertThat(modules.getModuleByName("configuration")).isPresent();
		assertThat(modules.getModuleByName("organization")).isPresent();
		assertThat(modules.getModuleByName("resource")).isPresent();
		assertThat(modules.getModuleByName("notification")).isPresent();
		assertThat(modules.getModuleByName("integration")).isPresent();
		assertThat(modules.getModuleByName("operations")).isPresent();
		assertThat(modules.getModuleByName("identity")).isPresent();
		assertThat(modules.getModuleByName("fulfillment")).isPresent();
	}

	@Test
	void hasNoModuleDependencyViolations() {
		modules.verify();
	}
}
