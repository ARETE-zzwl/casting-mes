package com.renyi.mes;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTests {

	@LocalServerPort
	private int port;

	@Test
	void reportsApplicationAsUp() throws Exception {
		var request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + "/actuator/health"))
			.GET()
			.build();

		var response = HttpClient.newHttpClient()
			.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
		assertThat(response.headers().firstValue("X-Content-Type-Options"))
			.contains("nosniff");
		assertThat(response.headers().firstValue("X-Frame-Options"))
			.contains("DENY");
		assertThat(response.headers().firstValue("Content-Security-Policy"))
			.contains("default-src 'none'; frame-ancestors 'none'");
	}

	@Test
	void exposesDatabaseBackedReadinessAndProcessLiveness() throws Exception {
		var client = HttpClient.newHttpClient();
		var readiness = client.send(
			HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/actuator/health/readiness"))
				.GET()
				.build(),
			HttpResponse.BodyHandlers.ofString()
		);
		var liveness = client.send(
			HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/actuator/health/liveness"))
				.GET()
				.build(),
			HttpResponse.BodyHandlers.ofString()
		);

		assertThat(readiness.statusCode()).isEqualTo(200);
		assertThat(readiness.body()).contains("\"status\":\"UP\"");
		assertThat(liveness.statusCode()).isEqualTo(200);
		assertThat(liveness.body()).contains("\"status\":\"UP\"");
	}
}
