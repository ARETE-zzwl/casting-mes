package com.renyi.mes.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	@Bean
	@Profile("prod")
	SecurityFilterChain productionSecurity(HttpSecurity http, LocalSessionCredentials credentials) throws Exception {
		return authenticatedSecurity(http, credentials, true)
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness").permitAll()
				.requestMatchers("/api/simulations/**").denyAll()
				.requestMatchers("/api/auth/config", "/api/auth/csrf", "/api/auth/login").permitAll()
				.anyRequest().authenticated()
			)
			.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
			.build();
	}

	@Bean
	@Profile("secure & !prod")
	SecurityFilterChain sessionSecurity(HttpSecurity http, LocalSessionCredentials credentials) throws Exception {
		return authenticatedSecurity(http, credentials, false)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness",
					"/api/auth/config", "/api/auth/csrf", "/api/auth/login").permitAll()
				.requestMatchers("/api/simulations/**").denyAll()
				.anyRequest().authenticated()).build();
	}

	private HttpSecurity authenticatedSecurity(HttpSecurity http, LocalSessionCredentials credentials, boolean bearer) throws Exception {
		var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		return commonSecurity(http)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
			.csrf(csrf -> {
				csrf.csrfTokenRepository(repository).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler());
				if (bearer) csrf.ignoringRequestMatchers(request -> {
					String value = request.getHeader("Authorization");
					return value != null && value.startsWith("Bearer ");
				});
			})
			.exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> response.sendError(401)))
			.addFilterBefore(new LocalSessionFilter(credentials), AnonymousAuthenticationFilter.class);
	}

	@Bean
	@Profile("!prod & !secure")
	SecurityFilterChain developmentSecurity(HttpSecurity http) throws Exception {
		return commonSecurity(http)
			.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
			.build();
	}

	private HttpSecurity commonSecurity(HttpSecurity http) throws Exception {
		return http
			.csrf(csrf -> csrf.disable())
			.cors(Customizer.withDefaults())
			.sessionManagement(session ->
				session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.headers(headers -> headers.contentSecurityPolicy(policy ->
				policy.policyDirectives("default-src 'none'; frame-ancestors 'none'")));
	}
}
