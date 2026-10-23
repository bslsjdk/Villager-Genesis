package dev.villagergenesis.knowledge;

import java.util.Set;

/**
 * A teachable unit of knowledge. Definitions describe prerequisites and urgency; they do not
 * imply that a villager can perform the associated Minecraft actions until an executor verifies it.
 */
public record KnowledgeDefinition(
        String id,
        String title,
        String description,
        Set<String> prerequisites,
        int survivalUrgency,
        int complexity
) {
    public KnowledgeDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Knowledge id must not be blank.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Knowledge title must not be blank.");
        }
        if (description == null) {
            description = "";
        }
        prerequisites = prerequisites == null ? Set.of() : Set.copyOf(prerequisites);
        if (survivalUrgency < 0 || survivalUrgency > 100) {
            throw new IllegalArgumentException("Survival urgency must be between 0 and 100.");
        }
        if (complexity < 1 || complexity > 100) {
            throw new IllegalArgumentException("Complexity must be between 1 and 100.");
        }
    }
}
