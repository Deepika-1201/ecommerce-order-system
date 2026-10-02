package com.ecommerce.catalog.web;

import com.ecommerce.platform.CallerRole;
import com.ecommerce.platform.HttpAccessRules;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

@Configuration(proxyBeanMethods = false)
class CatalogWebConfiguration {

    @Bean
    HttpAccessRules catalogAccessRules() {
        return rules -> rules
                .requestMatchers(HttpMethod.GET, "/v1/categories", "/v1/products", "/v1/products/*").permitAll()
                .requestMatchers("/v1/admin/catalog/**").hasRole(CallerRole.ADMIN.name());
    }

    /** Body-hash ETags for public reads, so caches can revalidate them (ADR-012). */
    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> publicCatalogEtags() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration =
                new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/v1/categories", "/v1/products", "/v1/products/*");
        return registration;
    }
}
