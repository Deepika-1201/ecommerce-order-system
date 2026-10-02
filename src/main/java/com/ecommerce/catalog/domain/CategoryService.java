package com.ecommerce.catalog.domain;

import com.ecommerce.platform.ApiException;
import com.ecommerce.platform.AuditEntry;
import com.ecommerce.platform.AuditLog;
import com.ecommerce.platform.Caller;
import com.ecommerce.shared.Ids;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The category tree: at most 4 levels, never a cycle (LLD §3.5). Every change is audited. */
@Service
public class CategoryService {

    private final CategoryRepository categories;
    private final AuditLog audit;

    CategoryService(CategoryRepository categories, AuditLog audit) {
        this.categories = categories;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public CategoryTree tree() {
        return new CategoryTree(categories.all());
    }

    @Transactional
    public Category create(Caller admin, String name, String slug, UUID parentId) {
        categories.lockTree();
        CategoryTree tree = tree();
        if (parentId != null) {
            tree.find(parentId).orElseThrow(CategoryService::unknownParent);
            if (tree.depth(parentId) >= CategoryTree.MAX_DEPTH) {
                throw tooDeep();
            }
        }
        Category category = new Category(Ids.newId(), parentId, name.strip(), slug);
        categories.insert(category);
        record(admin, "catalog.category.created", category, details("name", category.name(), "slug", slug,
                "parent_id", parentId));
        return category;
    }

    /** {@code null} leaves a field unchanged. */
    @Transactional
    public Category rename(Caller admin, UUID id, String name, String slug) {
        Category current = tree().find(id).orElseThrow(CategoryService::notFound);
        Category renamed = new Category(id, current.parentId(), name != null ? name.strip() : current.name(),
                slug != null ? slug : current.slug());
        categories.update(renamed);
        record(admin, "catalog.category.updated", renamed, details("name", renamed.name(), "slug", renamed.slug()));
        return renamed;
    }

    /** Moves a category, with its subtree, under another parent, or to the top for {@code null}. */
    @Transactional
    public Category move(Caller admin, UUID id, UUID newParentId) {
        categories.lockTree();
        CategoryTree tree = tree();
        Category current = tree.find(id).orElseThrow(CategoryService::notFound);
        if (newParentId != null) {
            tree.find(newParentId).orElseThrow(CategoryService::unknownParent);
        }
        switch (tree.placement(id, newParentId)) {
            case CYCLE -> throw new ApiException(HttpStatus.CONFLICT, "category_cycle",
                    "A category cannot move under itself or one of its descendants.");
            case TOO_DEEP -> throw tooDeep();
            case ALLOWED -> { }
        }
        Category moved = new Category(id, newParentId, current.name(), current.slug());
        categories.update(moved);
        record(admin, "catalog.category.moved", moved, details("parent_id", newParentId));
        return moved;
    }

    private void record(Caller admin, String action, Category category, Map<String, Object> details) {
        audit.record(new AuditEntry("staff", admin.subject(), action, "category", category.id().toString(), null,
                details));
    }

    private static Map<String, Object> details(Object... keysAndValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            details.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return details;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such category.");
    }

    private static ApiException unknownParent() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown_category", "No such parent category.");
    }

    private static ApiException tooDeep() {
        return new ApiException(HttpStatus.CONFLICT, "category_too_deep",
                "Categories are at most " + CategoryTree.MAX_DEPTH + " levels deep.");
    }
}
