package dev.villagergenesis.knowledge;

import java.util.Set;

/**
 * Defines knowledge a pre-existing village can inherit instead of having to rediscover it.
 * A world adapter should apply this baseline to established residents during initialization.
 */
public final class KnowledgeBaseline {
    private static final Set<String> EXISTING_VILLAGE_BASELINE = Set.of(
            "basic_shelter",
            "basic_tools",
            "basic_crafting",
            "basic_food_production",
            "basic_equipment",
            "village_cooperation"
    );

    private KnowledgeBaseline() {}

    public static Set<String> existingVillageKnowledge() {
        return EXISTING_VILLAGE_BASELINE;
    }

    public static boolean isBaseline(String knowledgeId) {
        return EXISTING_VILLAGE_BASELINE.contains(knowledgeId);
    }
}
