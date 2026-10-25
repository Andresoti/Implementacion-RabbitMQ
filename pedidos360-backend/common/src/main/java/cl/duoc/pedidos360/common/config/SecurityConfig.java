package cl.duoc.pedidos360.common.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.util.Assert;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${app.security.issuer}") String issuer,
            @Value("${app.security.client-id}") String clientId,
            @Value("${app.security.audience:}") String audience) {
        Assert.hasText(issuer, "JWT issuer is required");
        Assert.hasText(clientId, "JWT client ID is required");
        var decoder = NimbusJwtDecoder
                .withJwkSetUri(issuer + "/.well-known/jwks.json").build();
        OAuth2TokenValidator<Jwt> cognito = jwt -> {
            // Match Spring's scope/scp precedence before authority conversion.
            Object scope = jwt.getClaims().get("scope");
            if (scope == null) scope = jwt.getClaims().get("scp");
            boolean validScope = scope == null || scope instanceof String
                    || (scope instanceof java.util.Collection<?> values
                        && values.stream().allMatch(String.class::isInstance));
            if (validScope && jwt.getExpiresAt() != null && "access".equals(jwt.getClaimAsString("token_use"))
                    && clientId.equals(jwt.getClaimAsString("client_id"))
                    && (audience.isBlank() || (jwt.getAudience() != null && jwt.getAudience().contains(audience)))) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid access token", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), cognito));
        return decoder;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
        };
        AccessDeniedHandler forbidden = (request, response, exception) -> {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"forbidden\"}");
        };
        http
            .sessionManagement(session -> session.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsSource))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/public/ping").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/pedidos", "/api/results").hasAuthority("SCOPE_pedidos/read")
                .requestMatchers(HttpMethod.POST, "/api/pedidos").hasAuthority("SCOPE_pedidos/write")
                .requestMatchers(HttpMethod.DELETE, "/api/rabbitmq/queues/*", "/api/rabbitmq/exchanges/*", "/api/rabbitmq/bindings").hasAuthority("SCOPE_rabbitmq/admin")
                .requestMatchers(HttpMethod.POST, "/api/rabbitmq/queues", "/api/rabbitmq/exchanges", "/api/rabbitmq/bindings").hasAuthority("SCOPE_rabbitmq/admin")
                .anyRequest().denyAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden)
                    .jwt(Customizer.withDefaults()))
            .headers(headers -> headers.contentSecurityPolicy(csp ->
                    csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")));

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:4200}") String origins) {
        Assert.isTrue(!origins.contains("*"), "CORS origins must be explicit");
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(origins.split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty()).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration admin = new CorsConfiguration(configuration);
        admin.setAllowedMethods(List.of("POST", "DELETE", "OPTIONS"));
        source.registerCorsConfiguration("/api/rabbitmq/**", admin);
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
