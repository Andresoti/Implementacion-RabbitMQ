package cl.duoc.pedidos360;

import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import cl.duoc.pedidos360.repository.PedidoRepository;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Pedidos360BackendApplicationTests {
    private static final RSAKey KEY;
    private static final HttpServer JWKS;
    private static final String ISSUER;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("ephemeral-test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ISSUER = "http://127.0.0.1:" + JWKS.getAddress().getPort();
            JWKS.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            JWKS.start();
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.security.issuer", () -> ISSUER);
        registry.add("app.cors.allowed-origins", () -> "http://localhost:4200,http://localhost:4300");
    }
    @AfterAll static void stopJwks() { JWKS.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired PedidoRepository repository;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void reset() { jdbc.update("delete from outbox"); repository.deleteAll(); }

    static JWTClaimsSet.Builder claims(String scope) {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject("local-user")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("token_use", "access").claim("client_id", "local-test-client")
                .audience("local-test-api").claim("scope", scope);
    }
    static String token(JWTClaimsSet.Builder claims, RSAKey key) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                .keyID(KEY.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
    static String bearer(String scope) throws Exception { return "Bearer " + token(claims(scope), KEY); }

    @org.springframework.boot.test.web.server.LocalServerPort
    int httpPort;

    @Test
    void realHttpPreservesUnsupportedMediaTypeWithoutOpeningDirectErrorAccess() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + httpPort + "/api/pedidos"))
                .header("Authorization", bearer("pedidos/write"))
                .header("Content-Type", "text/plain")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("not-json")).build();
        Assertions.assertEquals(415, client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        var directError = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + httpPort + "/error"))
                .header("Authorization", bearer("pedidos/read")).GET().build();
        Assertions.assertEquals(403, client.send(directError,
                java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "client", "tokenUse", "audience", "missingAudience", "missingExpiry", "missingClient", "missingTokenUse"})
    void rejectsInvalidCognitoClaims(String invalid) throws Exception {
        var claims = claims("pedidos/read");
        switch (invalid) {
            case "issuer" -> claims.issuer("https://wrong.invalid");
            case "client" -> claims.claim("client_id", "wrong-client");
            case "tokenUse" -> claims.claim("token_use", "id");
            case "audience" -> claims.audience("wrong-api");
            case "missingAudience" -> claims.claim("aud", null);
            case "missingExpiry" -> claims.expirationTime(null);
            case "missingClient" -> claims.claim("client_id", null);
            case "missingTokenUse" -> claims.claim("token_use", null);
            default -> throw new IllegalArgumentException(invalid);
        }
        mvc.perform(get("/api/pedidos").header("Authorization", "Bearer " + token(claims, KEY)))
                .andExpect(status().isUnauthorized());
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> malformedClaimTypes() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("token_use", java.util.Map.of("value", "access")),
                org.junit.jupiter.params.provider.Arguments.of("client_id", java.util.List.of("local-test-client")),
                org.junit.jupiter.params.provider.Arguments.of("aud", java.util.Map.of("value", "local-test-api")),
                org.junit.jupiter.params.provider.Arguments.of("exp", "not-a-date"),
                org.junit.jupiter.params.provider.Arguments.of("nbf", java.util.Map.of("value", 1)),
                org.junit.jupiter.params.provider.Arguments.of("scope", 123),
                org.junit.jupiter.params.provider.Arguments.of("scope", java.util.Map.of("value", "pedidos/read")),
                org.junit.jupiter.params.provider.Arguments.of("scope", java.util.List.of("pedidos/read", 123)),
                org.junit.jupiter.params.provider.Arguments.of("scp", java.util.List.of("pedidos/read", 123)));
    }

    @ParameterizedTest
    @MethodSource("malformedClaimTypes")
    void malformedClaimTypesReturnSafeUnauthorized(String name, Object value) throws Exception {
        var malformed = claims("pedidos/read").claim(name, value);
        if (name.equals("scp")) malformed.claim("scope", null);
        mvc.perform(get("/api/pedidos").header("Authorization", "Bearer " + token(malformed, KEY)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"unauthorized\"}"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        Assertions.assertEquals(0, repository.count());
    }

    @Test void outboxFailureRollsBackTheOrder() throws Exception {
        jdbc.execute("ALTER TABLE outbox RENAME TO outbox_unavailable");
        try {
            mvc.perform(post("/api/pedidos").header("Authorization", bearer("pedidos/write"))
                    .contentType("application/json").content("{\"cliente\":\"Ana\",\"producto\":\"Book\",\"cantidad\":1}"))
                    .andExpect(status().isServiceUnavailable());
            Assertions.assertEquals(0, repository.count());
        } finally { jdbc.execute("ALTER TABLE outbox_unavailable RENAME TO outbox"); }
    }

    @Test void creationDurablyStagesBothDestinationsInSameTransaction() throws Exception {
        mvc.perform(post("/api/pedidos").header("Authorization", bearer("pedidos/write"))
                .contentType("application/json").content("{\"cliente\":\"Ana\",\"producto\":\"Book\",\"cantidad\":2}"))
                .andExpect(status().isCreated());
        Assertions.assertEquals(2, jdbc.queryForObject("select count(*) from outbox", Integer.class));
        Assertions.assertEquals(1, jdbc.queryForObject("select count(distinct event_id) from outbox", Integer.class));
        String payload=jdbc.queryForList("select payload from outbox",String.class).get(0);
        var event=tools.jackson.databind.json.JsonMapper.builder().build().readTree(payload);
        Assertions.assertEquals(6,event.size());
        Assertions.assertEquals("Ana",event.get("customer").stringValue());
        Assertions.assertEquals(2,event.get("quantity").intValue());
        Assertions.assertTrue(event.get("occurredAt").isString());
        Assertions.assertDoesNotThrow(()->java.time.Instant.parse(event.get("occurredAt").stringValue()));
    }

    @Test void createsServerControlledOrderAndListsPersistedSpanishContract() throws Exception {
        mvc.perform(post("/api/pedidos").header("Authorization", bearer("pedidos/write"))
                .contentType("application/json")
                .content("{\"id\":999,\"estado\":\"APPROVED\",\"cliente\":\"Ana\",\"producto\":\"Book\",\"cantidad\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber()).andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(999)))
                .andExpect(jsonPath("$.cliente").value("Ana")).andExpect(jsonPath("$.producto").value("Book"))
                .andExpect(jsonPath("$.cantidad").value(2)).andExpect(jsonPath("$.estado").value("CREATED"));
        Assertions.assertEquals(1, repository.count());
        var saved = repository.findAll().get(0);
        Assertions.assertEquals("CREATED", saved.getEstado());
        mvc.perform(get("/api/pedidos").header("Authorization", bearer("pedidos/read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(saved.getId()))
                .andExpect(jsonPath("$[0].cliente").value("Ana"))
                .andExpect(jsonPath("$[0].producto").value("Book"))
                .andExpect(jsonPath("$[0].cantidad").value(2))
                .andExpect(jsonPath("$[0].estado").value("CREATED"))
                .andExpect(jsonPath("$[0].length()").value(5));
    }

    static Stream<String> invalidOrders() {
        return Stream.of(
                "{}", "{\"cliente\":null,\"producto\":\"Book\",\"cantidad\":1}",
                "{\"cliente\":\" \",\"producto\":\"Book\",\"cantidad\":1}",
                "{\"cliente\":\"Ana\",\"producto\":null,\"cantidad\":1}",
                "{\"cliente\":\"Ana\",\"producto\":\" \",\"cantidad\":1}",
                "{\"cliente\":\"Ana\",\"producto\":\"Book\",\"cantidad\":null}",
                "{\"cliente\":\"Ana\",\"producto\":\"Book\",\"cantidad\":0}",
                "{\"cliente\":\"Ana\",\"producto\":\"Book\",\"cantidad\":-1}",
                "{\"cliente\":\"Ana\",\"producto\":\"Book\",\"cantidad\":10001}",
                "{\"cliente\":\"" + "A".repeat(101) + "\",\"producto\":\"Book\",\"cantidad\":1}",
                "{\"cliente\":\"Ana\",\"producto\":\"" + "B".repeat(201) + "\",\"cantidad\":1}");
    }
    @ParameterizedTest
    @MethodSource("invalidOrders")
    void rejectsInvalidOrdersWithoutPersistence(String body) throws Exception {
        mvc.perform(post("/api/pedidos").header("Authorization", bearer("pedidos/write"))
                .contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.length()").value(1));
        Assertions.assertEquals(0, repository.count());
    }

    static Stream<String> unsupportedTextOrders() {
        return Stream.of("cliente", "producto").flatMap(field ->
                Stream.of("\\u0000", "\\uD800", "\\uDC00").map(escape ->
                    "{\"cliente\":\"Ana\",\"producto\":\"Book\",\"cantidad\":1}"
                        .replace(field.equals("cliente") ? "Ana" : "Book", "prefix" + escape + "suffix")));
    }
    @ParameterizedTest @MethodSource("unsupportedTextOrders")
    void rejectsUnsupportedDatabaseTextBeforeOrderOrOutboxPersistence(String body) throws Exception {
        mvc.perform(post("/api/pedidos").header("Authorization", bearer("pedidos/write"))
                .contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"invalid_request\"}"));
        Assertions.assertEquals(0, repository.count());
        Assertions.assertEquals(0, jdbc.queryForObject("select count(*) from outbox", Integer.class));
    }

    @Test void acceptsValidSupplementaryUnicodeWithoutChangingPayload() throws Exception {
        mvc.perform(post("/api/pedidos").header("Authorization", bearer("pedidos/write"))
                .contentType("application/json").content("{\"cliente\":\"Ana \\uD83D\\uDE00\",\"producto\":\"Book\",\"cantidad\":1}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.cliente").value("Ana 😀"));
    }

    @Test void restoresBrowserSecurityHeaders() throws Exception {
        mvc.perform(get("/api/public/ping").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().exists("Strict-Transport-Security"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:4200", "http://localhost:4300"})
    void permitsConfiguredCorsWithoutCredentials(String origin) throws Exception {
        mvc.perform(options("/api/pedidos").header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,OPTIONS"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed", "wrongSignature", "expired"})
    void unauthorizedResponsesAreSafeAndStateless(String kind) throws Exception {
        var request = get("/api/pedidos");
        if (kind.equals("malformed")) request.header("Authorization", "Bearer not-a-jwt");
        if (kind.equals("wrongSignature")) request.header("Authorization", "Bearer " +
                token(claims("pedidos/read"), new RSAKeyGenerator(2048).generate()));
        if (kind.equals("expired")) request.header("Authorization", "Bearer " +
                token(claims("pedidos/read").expirationTime(Date.from(Instant.now().minusSeconds(300))), KEY));
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("{\"error\":\"unauthorized\"}"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(result -> Assertions.assertNull(result.getRequest().getSession(false)))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/h2-console/", "/api/public/other", "/unexpected"})
    void unlistedRoutesAreDeniedEvenWithValidToken(String path) throws Exception {
        mvc.perform(get(path).header("Authorization", bearer("pedidos/read pedidos/write")))
                .andExpect(status().isForbidden());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", "{\"cliente\":\"Ana\",\"producto\":\"Book\",\"cantidad\":1.5}",
            "{\"cliente\":\"Ana\",\"producto\":\"Book\",\"cantidad\":2147483648}",
            "{\"cliente\":\"Ana\",\"producto\":\"Book\",\"cantidad\":\"oops\"}"})
    void rejectsMalformedInputWithSafeError(String body) throws Exception {
        mvc.perform(post("/api/pedidos").header("Authorization", bearer("pedidos/write"))
                .contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"invalid_request\"}"));
        Assertions.assertEquals(0, repository.count());
    }

    @Test void storageFailuresDoNotExposeDatabaseDetails() throws Exception {
        jdbc.execute("ALTER TABLE pedido RENAME TO pedido_unavailable");
        try {
            mvc.perform(get("/api/pedidos").header("Authorization", bearer("pedidos/read")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().json("{\"error\":\"service_unavailable\"}"));
        } finally {
            jdbc.execute("ALTER TABLE pedido_unavailable RENAME TO pedido");
        }
    }

    @Test void acceptsBoundaryValues() throws Exception {
        mvc.perform(post("/api/pedidos").header("Authorization", bearer("pedidos/write"))
                .contentType("application/json").content("{\"cliente\":\"" + "A".repeat(100) +
                        "\",\"producto\":\"" + "B".repeat(200) + "\",\"cantidad\":10000}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.cantidad").value(10000));
        Assertions.assertEquals(1, repository.count());
    }

    @Test void returnsEmptyArrayForAuthorizedReader() throws Exception {
        mvc.perform(get("/api/pedidos").header("Authorization", bearer("pedidos/read")))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test void acceptsCognitoAccessTokenWithoutAudienceWhenNotConfigured() throws Exception {
        var decoder = new cl.duoc.pedidos360.common.config.SecurityConfig().jwtDecoder(ISSUER, "local-test-client", "");
        var decoded = decoder.decode(token(claims("pedidos/read").claim("aud", null), KEY));
        Assertions.assertEquals("local-user", decoded.getSubject());
    }

    @Test void rejectsUnlistedCorsOrigin() throws Exception {
        mvc.perform(options("/api/pedidos").header("Origin", "https://untrusted.invalid")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE", "PATCH"})
    void rejectsUnlistedCorsMethods(String method) throws Exception {
        mvc.perform(options("/api/pedidos").header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", method))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test void rejectsUnlistedCorsHeaders() throws Exception {
        mvc.perform(options("/api/pedidos").header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-Untrusted"))
                .andExpect(status().isForbidden());
    }

    @Test void publicPingIsDeliberatelyAnonymous() throws Exception {
        mvc.perform(get("/api/public/ping")).andExpect(status().isOk());
    }

    @Test void missingScopesDoNotGrantOrderAccess() throws Exception {
        mvc.perform(get("/api/pedidos").header("Authorization", bearer("")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/pedidos").header("Authorization", bearer("")))
                .andExpect(status().isForbidden());
    }

    @Test void suppliedIdCannotOverwriteAnExistingOrder() throws Exception {
        var existing = repository.saveAndFlush(new cl.duoc.pedidos360.model.Pedido(null, "Original", "Original", 1, "CREATED"));
        mvc.perform(post("/api/pedidos").header("Authorization", bearer("pedidos/write"))
                .contentType("application/json").content("{\"id\":" + existing.getId() +
                        ",\"estado\":\"APPROVED\",\"cliente\":\"Ana\",\"producto\":\"Book\",\"cantidad\":1}"))
                .andExpect(status().isCreated());
        Assertions.assertEquals(2, repository.count());
        Assertions.assertEquals("Original", repository.findById(existing.getId()).orElseThrow().getCliente());
    }

    @Test void writeRequiresWriteScope() throws Exception {
        mvc.perform(post("/api/pedidos").header("Authorization", bearer("pedidos/read"))
                .contentType("application/json").content("{\"cliente\":\"Ana\",\"producto\":\"Book\",\"cantidad\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test void readRequiresReadScope() throws Exception {
        mvc.perform(get("/api/pedidos").header("Authorization", bearer("pedidos/write")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("{\"error\":\"forbidden\"}"));
    }
}
