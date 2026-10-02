package com.ecommerce.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Stands in for Keycloak in tests (LLD §3.2): serves a JWK set over HTTP and signs tokens with the matching key, so
 * tests run the production decoder and validators.
 */
public final class TestIdentityProvider {

    public static final String ISSUER = "https://idp.test/realms/ecommerce";
    public static final String AUDIENCE = "ecommerce-api";

    private static final RSAKey SIGNING_KEY = newKey("test-signing-key");
    // Same key id, different key: what a forger would produce.
    private static final RSAKey FORGED_KEY = newKey("test-signing-key");

    private static HttpServer server;

    private TestIdentityProvider() {
    }

    public static synchronized String jwkSetUri() {
        if (server == null) {
            byte[] jwkSet = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start the test identity provider", e);
            }
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwkSet.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(jwkSet);
                }
            });
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        }
        return "http://localhost:" + server.getAddress().getPort() + "/jwks";
    }

    /** A valid token for a customer. */
    public static String customer(String subject) {
        return token(subject).roles("customer").sign();
    }

    /** A valid token for an admin. */
    public static String admin(String subject) {
        return token(subject).roles("admin").sign();
    }

    public static Token token(String subject) {
        return new Token(subject);
    }

    private static RSAKey newKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A token to sign; valid by default, with setters to break each check. */
    public static final class Token {

        private final String subject;
        private List<String> roles = List.of();
        private String email;
        private String name;
        private String issuer = ISSUER;
        private String audience = AUDIENCE;
        private Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));
        private Instant notBefore;
        private RSAKey key = SIGNING_KEY;

        private Token(String subject) {
            this.subject = subject;
            this.email = subject == null ? null : subject + "@example.test";
            this.name = subject;
        }

        public Token roles(String... roles) {
            this.roles = List.of(roles);
            return this;
        }

        public Token email(String email) {
            this.email = email;
            return this;
        }

        public Token name(String name) {
            this.name = name;
            return this;
        }

        public Token issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Token audience(String audience) {
            this.audience = audience;
            return this;
        }

        public Token expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Token notBefore(Instant notBefore) {
            this.notBefore = notBefore;
            return this;
        }

        public Token forged() {
            this.key = FORGED_KEY;
            return this;
        }

        public String sign() {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),
                    claims());
            try {
                jwt.sign(new RSASSASigner(key));
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
            return jwt.serialize();
        }

        /** The same claims with {@code alg: none}. */
        public String unsigned() {
            return new PlainJWT(claims()).serialize();
        }

        private JWTClaimsSet claims() {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .issueTime(new Date())
                    .expirationTime(Date.from(expiresAt))
                    .claim("realm_access", Map.of("roles", roles));
            if (subject != null) {
                claims.subject(subject);
            }
            if (notBefore != null) {
                claims.notBeforeTime(Date.from(notBefore));
            }
            if (email != null) {
                claims.claim("email", email);
            }
            if (name != null) {
                claims.claim("name", name);
            }
            return claims.build();
        }
    }
}
