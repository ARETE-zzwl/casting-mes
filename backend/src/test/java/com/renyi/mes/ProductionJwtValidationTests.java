package com.renyi.mes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:jwt-validation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "mes.web.allowed-origins=http://localhost:5174",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.invalid",
    "spring.security.oauth2.resourceserver.jwt.audiences=casting-mes"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProductionJwtValidationTests {
    private static final RSAKey KEY;
    private static final HttpServer JWKS;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/keys", exchange -> {
                byte[] response = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
            });
            JWKS.start();
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/keys");
    }

    @AfterAll static void stop() { JWKS.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void bind() {
        jdbc.update("delete from trusted_identity_binding");
        jdbc.update("insert into trusted_identity_binding(issuer, subject, employee_code) values ('https://issuer.invalid', 'warehouse', 'K001')");
    }

    @Test
    void validSignatureIssuerAudienceAndBindingAreRequiredTogether() throws Exception {
        expect(token(KEY, "https://issuer.invalid", "casting-mes", Instant.now().plusSeconds(300)), 200);
        expect(token(KEY, "https://issuer.invalid", "another-application", Instant.now().plusSeconds(300)), 401);
        expect(token(KEY, "https://wrong-issuer.invalid", "casting-mes", Instant.now().plusSeconds(300)), 401);
        expect(token(KEY, "https://issuer.invalid", "casting-mes", Instant.now().minusSeconds(120)), 401);
        var attackerKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        expect(token(attackerKey, "https://issuer.invalid", "casting-mes", Instant.now().plusSeconds(300)), 401);
    }

    private void expect(String token, int expected) throws Exception {
        mvc.perform(get("/api/inventory/balances").param("viewerCode", "K001").header("Authorization", "Bearer " + token))
            .andExpect(status().is(expected));
    }

    private String token(RSAKey key, String issuer, String audience, Instant expires) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
            new JWTClaimsSet.Builder().issuer(issuer).subject("warehouse").audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(300))).expirationTime(Date.from(expires)).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
