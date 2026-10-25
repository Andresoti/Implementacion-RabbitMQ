package cl.duoc.pedidos360.common;

import cl.duoc.pedidos360.common.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityConfigurationTests {
    private final SecurityConfig configuration = new SecurityConfig();

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.com", "http://localhost:4200,*"})
    void rejectsWildcardCorsConfiguration(String origins) {
        assertThrows(IllegalArgumentException.class, () -> configuration.corsConfigurationSource(origins));
    }

    @Test void refusesBlankIssuer() {
        assertThrows(IllegalArgumentException.class, () -> configuration.jwtDecoder("", "client", ""));
    }

    @Test void refusesBlankClientId() {
        assertThrows(IllegalArgumentException.class, () -> configuration.jwtDecoder("https://issuer.invalid", " ", ""));
    }
}
