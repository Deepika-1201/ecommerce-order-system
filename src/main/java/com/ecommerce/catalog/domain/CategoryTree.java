package com.ecommerce.catalog.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The whole category tree in memory: there are a few hundred categories at most. */
public final class CategoryTree {

    public static final int MAX_DEPTH = 4;

    private final Map<UUID, Category> byId = new HashMap<>();
    private final Map<UUID, List<Category>> children = new HashMap<>();

    public CategoryTree(List<Category> categories) {
        for (Category category : categories) {
            byId.put(category.id(), category);
            children.computeIfAbsent(category.parentId(), parent -> new ArrayList<>()).add(category);
        }
        children.values().forEach(siblings -> siblings.sort(Comparator.comparing(Category::name)));
    }

    public Optional<Category> find(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<Category> findBySlug(String slug) {
        return byId.values().stream().filter(category -> category.slug().equals(slug)).findFirst();
    }

    /** The children of a category, or the roots for {@code null}; sorted by name. */
    public List<Category> childrenOf(UUID parentId) {
        return children.getOrDefault(parentId, List.of());
    }

    /** 1 for a root category. */
    public int depth(UUID id) {
        int depth = 0;
        for (UUID current = id; current != null; current = byId.get(current).parentId()) {
            depth++;
        }
        return depth;
    }

    /** 1 for a category without children. */
    public int height(UUID id) {
        return 1 + childrenOf(id).stream().mapToInt(child -> height(child.id())).max().orElse(0);
    }

    /** The category and all its descendants. */
    public Set<UUID> subtree(UUID id) {
        Set<UUID> ids = new HashSet<>();
        Deque<UUID> pending = new ArrayDeque<>(List.of(id));
        while (!pending.isEmpty()) {
            UUID current = pending.pop();
            if (ids.add(current)) {
                childrenOf(current).forEach(child -> pending.push(child.id()));
            }
        }
        return ids;
    }

    /** Whether {@code id} could be placed under {@code newParentId} without a cycle or going too deep. */
    public Placement placement(UUID id, UUID newParentId) {
        if (newParentId != null && subtree(id).contains(newParentId)) {
            return Placement.CYCLE;
        }
        int parentDepth = newParentId == null ? 0 : depth(newParentId);
        return parentDepth + height(id) > MAX_DEPTH ? Placement.TOO_DEEP : Placement.ALLOWED;
    }

    public enum Placement {
        ALLOWED,
        CYCLE,
        TOO_DEEP
    }
}
