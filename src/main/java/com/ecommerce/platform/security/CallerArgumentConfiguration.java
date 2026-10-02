package com.ecommerce.platform.security;

import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.Caller;
import com.ecommerce.platform.CallerRole;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Lets controllers take a {@link Caller} parameter instead of Spring Security types. */
@Configuration(proxyBeanMethods = false)
class CallerArgumentConfiguration implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerArgumentResolver());
    }

    static final class CallerArgumentResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == Caller.class;
        }

        @Override
        public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                NativeWebRequest request, WebDataBinderFactory binders) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (!(authentication instanceof JwtAuthenticationToken token)) {
                // Only reachable if an access rule lets anonymous requests into a handler that needs a caller.
                throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "A valid access token is required.");
            }
            Jwt jwt = token.getToken();
            Set<CallerRole> roles = token.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(authority -> authority != null && authority.startsWith("ROLE_"))
                    .flatMap(authority -> CallerRole.fromTokenRole(authority.substring("ROLE_".length())).stream())
                    .collect(Collectors.toSet());
            String name = jwt.getClaimAsString("name");
            return new Caller(jwt.getSubject(), roles, jwt.getClaimAsString("email"),
                    name != null ? name : jwt.getClaimAsString("preferred_username"));
        }
    }
}
