package dev.villagergenesis.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBaselineTest {
    @Test
    void existingVillagesStartWithPracticalFoundations() {
        var baseline = KnowledgeBaseline.existingVillageKnowledge();

        assertTrue(baseline.contains("basic_shelter"));
        assertTrue(baseline.contains("basic_tools"));
        assertTrue(baseline.contains("basic_crafting"));
        assertTrue(baseline.contains("basic_equipment"));
        assertTrue(baseline.contains("village_cooperation"));
    }

    @Test
    void expansionAndAdvancedKnowledgeBuildOnTheBaseline() {
        KnowledgeRegistry registry = new KnowledgeRegistry();

        assertTrue(registry.find("housing_expansion").orElseThrow()
                .prerequisites().contains("village_cooperation"));
        assertTrue(registry.find("resource_specialization").isPresent());
        assertTrue(registry.find("redstone_experimentation").orElseThrow()
                .prerequisites().contains("advanced_materials"));
    }

    @Test
    void baselineCannotBeMutatedByCallers() {
        assertThrows(UnsupportedOperationException.class,
                () -> KnowledgeBaseline.existingVillageKnowledge().clear());
    }
}
