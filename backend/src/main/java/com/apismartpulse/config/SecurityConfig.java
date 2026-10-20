package com.apismartpulse.config;

import com.apismartpulse.apikey.ApiKeyAuthFilter;
import com.apismartpulse.apikey.ApiKeyService;
import com.apismartpulse.auth.NeonIdentityConverter;
import com.apismartpulse.auth.NeonJwtDecoder;
import com.apismartpulse.common.Problems;
import com.apismartpulse.ratelimit.RateLimiters;
import com.apismartpulse.user.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Two stateless filter chains (SEC-010..015): the public API accepts only X-API-Key (no CORS); the
 * app API accepts only Bearer JWTs. Deny by default everywhere else.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  public static final String ISSUER = "apismartpulse";
  private static final String CSP =
      "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:;"
          + " font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self';"
          + " form-action 'self'";

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  private static SecretKey key(AppProperties props) {
    return new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  @Bean
  JwtEncoder jwtEncoder(AppProperties props) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key(props)));
  }

  @Bean
  JwtDecoder jwtDecoder(AppProperties props, Clock clock) {
    if (props.neonAuth()) return new NeonJwtDecoder(props.neonAuthUrl(), clock);
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(key(props)).macAlgorithm(MacAlgorithm.HS256).build();
    JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
    timestamps.setClock(clock);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(ISSUER)));
    return decoder;
  }

  @Bean
  @Order(1)
  SecurityFilterChain publicApi(
      HttpSecurity http, ApiKeyService keys, RateLimiters limiters, AppProperties props)
      throws Exception {
    http.securityMatcher("/api/public/**")
        .csrf(c -> c.disable())
        .cors(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(new ApiKeyAuthFilter(keys, limiters), AnonymousAuthenticationFilter.class)
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (req, res, ex) ->
                        Problems.write(
                            req,
                            res,
                            HttpStatus.UNAUTHORIZED,
                            "INVALID_API_KEY",
                            "Invalid API key",
                            "A valid X-API-Key header is required.")))
        .headers(SecurityConfig::headers);
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain appApi(
      HttpSecurity http, AppProperties props, UserRepository users, TransactionTemplate tx)
      throws Exception {
    JwtAuthenticationConverter local = new JwtAuthenticationConverter();
    local.setJwtGrantedAuthoritiesConverter(
        jwt -> List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
    Converter<Jwt, ? extends AbstractAuthenticationToken> converter =
        props.neonAuth() ? new NeonIdentityConverter(users, tx) : local;
    DefaultBearerTokenResolver bearer = new DefaultBearerTokenResolver();
    // Auth endpoints must work even if the browser still holds an expired access token.
    BearerTokenResolver resolver =
        req -> req.getRequestURI().startsWith("/api/v1/auth/") ? null : bearer.resolve(req);

    http.csrf(c -> c.disable()) // no cookie-authenticated writes except refresh/logout (SEC: CSRF)
        .cors(c -> {})
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/logout",
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.bearerTokenResolver(resolver)
                    .jwt(j -> j.jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint(SecurityConfig::bearerRejected))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) ->
                            Problems.write(
                                req,
                                res,
                                HttpStatus.UNAUTHORIZED,
                                "UNAUTHENTICATED",
                                "Authentication required",
                                "Log in to continue."))
                    .accessDeniedHandler(
                        (req, res, ex) ->
                            Problems.write(
                                req,
                                res,
                                HttpStatus.FORBIDDEN,
                                "FORBIDDEN",
                                "Forbidden",
                                "You don't have permission to do that.")))
        .headers(SecurityConfig::headers);
    return http.build();
  }

  /** 401 for missing/invalid tokens; deactivated or conflicting identities get a precise answer. */
  private static void bearerRejected(
      jakarta.servlet.http.HttpServletRequest req,
      jakarta.servlet.http.HttpServletResponse res,
      org.springframework.security.core.AuthenticationException ex)
      throws java.io.IOException {
    if (ex instanceof DisabledException) {
      Problems.write(
          req,
          res,
          HttpStatus.FORBIDDEN,
          "ACCOUNT_DISABLED",
          "Account deactivated",
          ex.getMessage());
    } else if (ex instanceof NeonIdentityConverter.IdentityRejected r
        && "ACCOUNT_LINK_CONFLICT".equals(r.code)) {
      Problems.write(
          req, res, HttpStatus.CONFLICT, r.code, "Account already exists", ex.getMessage());
    } else {
      Problems.write(
          req,
          res,
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Authentication required",
          "Log in to continue.");
    }
  }

  private static void headers(
      org.springframework.security.config.annotation.web.configurers.HeadersConfigurer<HttpSecurity>
          h) {
    h.contentSecurityPolicy(c -> c.policyDirectives(CSP))
        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
        .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=()"))
        .frameOptions(f -> f.deny());
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(AppProperties props) {
    CorsConfiguration c = new CorsConfiguration();
    c.setAllowedOrigins(props.corsAllowedOrigins());
    c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
    c.setAllowedHeaders(
        List.of(
            "Authorization",
            "Content-Type",
            "X-Requested-With",
            "X-Request-Id",
            "Idempotency-Key"));
    c.setExposedHeaders(List.of("X-Request-Id", "Retry-After", "Location"));
    c.setAllowCredentials(true);
    c.setMaxAge(600L);
    UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
    src.registerCorsConfiguration("/api/v1/**", c);
    return src;
  }
}
