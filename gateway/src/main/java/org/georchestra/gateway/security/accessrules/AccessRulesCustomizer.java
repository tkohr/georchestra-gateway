/*
 * Copyright (C) 2022 by the geOrchestra PSC
 *
 * This file is part of geOrchestra.
 *
 * geOrchestra is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * geOrchestra is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * geOrchestra.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.georchestra.gateway.security.accessrules;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.georchestra.gateway.model.GatewayConfigProperties;
import org.georchestra.gateway.model.RoleBasedAccessRule;
import org.georchestra.gateway.model.Service;
import org.georchestra.gateway.security.ServerHttpSecurityCustomizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity.AuthorizeExchangeSpec;
import org.springframework.security.config.web.server.ServerHttpSecurity.AuthorizeExchangeSpec.Access;

import com.google.common.annotations.VisibleForTesting;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link ServerHttpSecurityCustomizer} to apply {@link RoleBasedAccessRule ROLE
 * based access rules} at startup.
 * <p>
 * The access rules are configured as
 * {@link GatewayConfigProperties#getGlobalAccessRules() global rules}, and
 * overridden if needed on a per-service basis from
 * {@link GatewayConfigProperties#getServices()}.
 *
 * @see RoleBasedAccessRule
 * @see GatewayConfigProperties#getGlobalAccessRules()
 * @see Service#getAccessRules()
 */
@RequiredArgsConstructor
@Slf4j(topic = "org.georchestra.gateway.config.security.accessrules")
public class AccessRulesCustomizer implements ServerHttpSecurityCustomizer {

    private final @NonNull GatewayConfigProperties config;

    @Override
    public void customize(ServerHttpSecurity http) {
        log.info("Configuring proxied applications access rules...");

        AuthorizeExchangeSpec authorizeExchange = http.authorizeExchange();

        log.info("Applying global access rules...");
        apply(authorizeExchange, config.getGlobalAccessRules());

        config.getServices().forEach((name, service) -> {
            log.info("Applying access rules for backend service '{}'", name);
            apply(authorizeExchange, service.getAccessRules());
        });
    }

    private void apply(AuthorizeExchangeSpec authorizeExchange, List<RoleBasedAccessRule> accessRules) {
        if (accessRules == null || accessRules.isEmpty()) {
            log.info("No access rules found.");
            return;
        }
        for (RoleBasedAccessRule rule : accessRules) {
            apply(authorizeExchange, rule);
        }
    }

    @VisibleForTesting
    void apply(AuthorizeExchangeSpec authorizeExchange, RoleBasedAccessRule rule) {
        final List<String> antPatterns = resolveAntPatterns(rule);
        final boolean anonymous = rule.isAnonymous();
        final boolean authenticated = rule.isAuthenticated();
        final List<String> allowedRoles = rule.getAllowedRoles() == null ? List.of() : rule.getAllowedRoles();
        Access access = authorizeExchange(authorizeExchange, antPatterns);
        if (anonymous) {
            log.debug("Access rule: {} anonymous", antPatterns);
            permitAll(access);
        } else if (authenticated) {
            requireAuthenticatedUser(access);
        } else if (!allowedRoles.isEmpty()) {
            List<String> roles = resolveRoles(antPatterns, allowedRoles);
            hasAnyAuthority(access, roles);
        } else {
            log.warn(
                    "The following intercepted URL's don't have any access rule defined. Defaulting to 'authenticated': {}",
                    antPatterns);
            requireAuthenticatedUser(access);
        }
    }

    private List<String> resolveAntPatterns(RoleBasedAccessRule rule) {
        List<String> antPatterns = rule.getInterceptUrl();
        Objects.requireNonNull(antPatterns, "intercept-urls is null");
        antPatterns.forEach(Objects::requireNonNull);
        if (antPatterns.isEmpty())
            throw new IllegalArgumentException("No ant-pattern(s) defined for rule " + rule);
        antPatterns.forEach(Objects::requireNonNull);
        return antPatterns;
    }

    @VisibleForTesting
    Access authorizeExchange(AuthorizeExchangeSpec authorizeExchange, List<String> antPatterns) {
        return authorizeExchange.pathMatchers(antPatterns.toArray(String[]::new));
    }

    private List<String> resolveRoles(List<String> antPatterns, List<String> allowedRoles) {
        List<String> roles = allowedRoles.stream().map(this::ensureRolePrefix).collect(Collectors.toList());
        if (log.isDebugEnabled())
            log.debug("Access rule: {} has any role: {}", antPatterns, roles.stream().collect(Collectors.joining(",")));
        return roles;
    }

    @VisibleForTesting
    void requireAuthenticatedUser(Access access) {
        access.authenticated();
    }

    @VisibleForTesting
    void hasAnyAuthority(Access access, List<String> roles) {
        access.hasAnyAuthority(roles.toArray(String[]::new));
    }

    @VisibleForTesting
    void permitAll(Access access) {
        access.permitAll();
    }

    private String ensureRolePrefix(@NonNull String roleName) {
        return roleName.startsWith("ROLE_") ? roleName : ("ROLE_" + roleName);
    }
}