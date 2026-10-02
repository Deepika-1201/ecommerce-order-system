package com.ecommerce.platform.security;

import com.ecommerce.platform.CallerRole;
import com.ecommerce.platform.HttpAccessRules;
import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

/**
 * The service is an OAuth 2.0 resource server (LLD §3.2): Boot builds the JWT decoder from {@code ecom.security.*};
 * this class adds the role mapping, the module rules and deny-by-default.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, List<HttpAccessRules> moduleRules,
            SecurityProblemHandler problems) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .oauth2ResourceServer(server -> server
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(SecurityConfiguration::authenticate)))
                .authorizeHttpRequests(rules -> {
                    rules.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll();
                    // The management port is internal (LLD §1.3); /livez and /readyz are the probes on the main port.
                    rules.requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)).permitAll();
                    rules.requestMatchers(EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER,
                            HealthEndpoint.class)).permitAll();
                    rules.requestMatchers(EndpointRequest.to("openapi")).permitAll();
                    moduleRules.forEach(module -> module.configure(rules));
                    rules.anyRequest().denyAll();
                });
        return http.build();
    }

    /** Every token must name its user; Boot adds this to the signature, time, issuer and audience checks. */
    @Bean
    OAuth2TokenValidator<Jwt> subjectRequired() {
        return new JwtClaimValidator<String>(JwtClaimNames.SUB, subject -> subject != null && !subject.isBlank());
    }

    @Bean
    JwkSetUriJwtDecoderBuilderCustomizer signingKeyTimeouts() {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(1));
        requests.setReadTimeout(Duration.ofSeconds(2));
        return builder -> builder.restOperations(new RestTemplate(requests));
    }

    static JwtAuthenticationToken authenticate(Jwt jwt) {
        Collection<GrantedAuthority> authorities = realmRoles(jwt).stream()
                .map(CallerRole::fromTokenRole)
                .flatMap(Optional::stream)
                .distinct()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(role.authority()))
                .toList();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    /** Keycloak puts realm roles in {@code realm_access.roles}. */
    private static List<String> realmRoles(Jwt jwt) {
        if (!(jwt.getClaims().get("realm_access") instanceof Map<?, ?> realmAccess)
                || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
