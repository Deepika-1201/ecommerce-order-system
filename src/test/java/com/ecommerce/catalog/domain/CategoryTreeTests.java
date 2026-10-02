package com.ecommerce.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.catalog.domain.CategoryTree.Placement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryTreeTests {

    // fashion > men > shirts > formal, and home at the top
    private final Category fashion = category(null, "Fashion");
    private final Category men = category(fashion.id(), "Men");
    private final Category women = category(fashion.id(), "Women");
    private final Category shirts = category(men.id(), "Shirts");
    private final Category formal = category(shirts.id(), "Formal");
    private final Category home = category(null, "Home");
    private final CategoryTree tree = new CategoryTree(List.of(formal, shirts, women, men, home, fashion));

    @Test
    void depthCountsFromTheTop() {
        assertThat(tree.depth(fashion.id())).isEqualTo(1);
        assertThat(tree.depth(formal.id())).isEqualTo(4);
    }

    @Test
    void heightCountsTheDeepestBranch() {
        assertThat(tree.height(formal.id())).isEqualTo(1);
        assertThat(tree.height(fashion.id())).isEqualTo(4);
    }

    @Test
    void aSubtreeHoldsTheCategoryAndAllItsDescendants() {
        assertThat(tree.subtree(men.id())).containsExactlyInAnyOrder(men.id(), shirts.id(), formal.id());
        assertThat(tree.subtree(home.id())).containsExactly(home.id());
    }

    @Test
    void childrenAndRootsAreSortedByName() {
        assertThat(tree.childrenOf(null)).containsExactly(fashion, home);
        assertThat(tree.childrenOf(fashion.id())).containsExactly(men, women);
    }

    @Test
    void aCategoryCannotMoveUnderItselfOrItsDescendants() {
        assertThat(tree.placement(men.id(), men.id())).isEqualTo(Placement.CYCLE);
        assertThat(tree.placement(men.id(), formal.id())).isEqualTo(Placement.CYCLE);
    }

    @Test
    void aMoveMayNotMakeTheTreeDeeperThanFourLevels() {
        assertThat(tree.placement(shirts.id(), women.id())).as("shirts and formal become levels 3 and 4")
                .isEqualTo(Placement.ALLOWED);
        assertThat(tree.placement(men.id(), home.id())).as("formal stays at level 4").isEqualTo(Placement.ALLOWED);
        assertThat(tree.placement(men.id(), women.id())).as("formal would be level 5").isEqualTo(Placement.TOO_DEEP);
        assertThat(tree.placement(home.id(), formal.id())).isEqualTo(Placement.TOO_DEEP);
        assertThat(tree.placement(formal.id(), null)).isEqualTo(Placement.ALLOWED);
    }

    @Test
    void slugsFindCategories() {
        assertThat(tree.findBySlug("shirts")).contains(shirts);
        assertThat(tree.findBySlug("toys")).isEmpty();
    }

    private static Category category(UUID parentId, String name) {
        return new Category(UUID.randomUUID(), parentId, name, name.toLowerCase());
    }
}
