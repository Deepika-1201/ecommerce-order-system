package com.ecommerce.catalog.domain;

import com.ecommerce.platform.ApiException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CategoryRepository {

    private final JdbcClient jdbc;
    private final Clock clock;

    CategoryRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    List<Category> all() {
        return jdbc.sql("SELECT id, parent_id, name, slug FROM catalog.categories")
                .query((row, rowNumber) -> new Category(row.getObject("id", UUID.class),
                        row.getObject("parent_id", UUID.class), row.getString("name"), row.getString("slug")))
                .list();
    }

    /** Serializes tree changes, so two concurrent moves cannot build a cycle together. */
    void lockTree() {
        jdbc.sql("LOCK TABLE catalog.categories IN SHARE ROW EXCLUSIVE MODE").update();
    }

    void insert(Category category) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        uniqueSlug(() -> jdbc.sql("""
                        INSERT INTO catalog.categories (id, parent_id, name, slug, created_at, updated_at)
                        VALUES (:id, :parentId, :name, :slug, :now, :now)
                        """)
                .param("id", category.id())
                .param("parentId", category.parentId())
                .param("name", category.name())
                .param("slug", category.slug())
                .param("now", now)
                .update());
    }

    void update(Category category) {
        uniqueSlug(() -> jdbc.sql("""
                        UPDATE catalog.categories
                        SET parent_id = :parentId, name = :name, slug = :slug, updated_at = :now
                        WHERE id = :id
                        """)
                .param("parentId", category.parentId())
                .param("name", category.name())
                .param("slug", category.slug())
                .param("now", OffsetDateTime.now(clock))
                .param("id", category.id())
                .update());
    }

    private static void uniqueSlug(Runnable write) {
        try {
            write.run();
        } catch (DuplicateKeyException e) {
            if (e.getMostSpecificCause() instanceof PSQLException sql
                    && sql.getServerErrorMessage() instanceof ServerErrorMessage message
                    && "categories_slug_unique".equals(message.getConstraint())) {
                throw new ApiException(HttpStatus.CONFLICT, "category_slug_taken",
                        "Another category already uses this slug.");
            }
            throw e;
        }
    }
}
