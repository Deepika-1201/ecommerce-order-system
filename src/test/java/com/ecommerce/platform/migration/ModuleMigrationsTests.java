package com.ecommerce.platform.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.support.IntegrationTest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;

class ModuleMigrationsTests extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void everyModuleOwnsASchemaWithItsOwnMigrationHistory() {
        for (String module : ModuleMigrations.MODULES) {
            Integer applied = jdbc.sql("SELECT count(*) FROM " + module + ".flyway_schema_history"
                            + " WHERE version = '1' AND success")
                    .query(Integer.class)
                    .single();
            String owner = jdbc.sql("SELECT obj_description(oid, 'pg_namespace') FROM pg_namespace WHERE nspname = ?")
                    .param(module)
                    .query(String.class)
                    .single();

            assertThat(applied).as(module).isEqualTo(1);
            assertThat(owner).as(module).startsWith("Owned by the " + module + " module");
        }
    }

    @Test
    void everyMigrationFolderBelongsToARegisteredModule() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*/*.sql");
        Set<String> folders = Arrays.stream(scripts)
                .map(ModuleMigrationsTests::folderOf)
                .collect(Collectors.toSet());

        assertThat(folders).containsExactlyInAnyOrderElementsOf(ModuleMigrations.MODULES);
    }

    private static String folderOf(Resource script) {
        try {
            String[] segments = script.getURL().getPath().split("/");
            return segments[segments.length - 2];
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
