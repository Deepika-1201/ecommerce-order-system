package com.ecommerce.platform;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * A module's access rules for its own paths (LLD §3.2). The platform applies every module's rules, then denies
 * whatever no rule names.
 */
@FunctionalInterface
public interface HttpAccessRules {

    void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry rules);
}
