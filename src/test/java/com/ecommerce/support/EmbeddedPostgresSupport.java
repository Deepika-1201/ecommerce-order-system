package com.ecommerce.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;

/** One embedded PostgreSQL per test JVM, so tests need no Docker. */
public final class EmbeddedPostgresSupport {

    private static EmbeddedPostgres postgres;

    private EmbeddedPostgresSupport() {
    }

    public static synchronized String jdbcUrl() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(EmbeddedPostgresSupport::stop));
        }
        return postgres.getJdbcUrl("postgres", "postgres");
    }

    private static void stop() {
        try {
            postgres.close();
        } catch (IOException ignored) {
            // best effort on JVM exit
        }
    }
}
