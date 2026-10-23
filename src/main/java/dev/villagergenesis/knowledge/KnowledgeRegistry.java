package dev.villagergenesis.knowledge;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Central catalog ordered from inherited village knowledge to advanced practices. */
public final class KnowledgeRegistry {
    private final Map<String, KnowledgeDefinition> definitions = new LinkedHashMap<>();

    public KnowledgeRegistry() {
        register(new KnowledgeDefinition("basic_shelter", "Basic shelter",
                "Build and maintain shelter using established village practices.", Set.of(), 90, 8));
        register(new KnowledgeDefinition("basic_tools", "Basic tools",
                "Use and replace common tools for gathering and farming.", Set.of(), 75, 10));
        register(new KnowledgeDefinition("basic_crafting", "Basic crafting",
                "Craft common items from available materials.", Set.of("basic_tools"), 72, 12));
        register(new KnowledgeDefinition("basic_food_production", "Food production",
                "Plant, tend, harvest, and replant food crops.", Set.of("basic_tools"), 95, 12));
        register(new KnowledgeDefinition("basic_equipment", "Basic equipment",
                "Use available equipment for work and protection.", Set.of("basic_tools"), 90, 12));
        register(new KnowledgeDefinition("village_cooperation", "Village cooperation",
                "Coordinate shared work and distribute resources.", Set.of(), 82, 14));

        register(new KnowledgeDefinition("hostile_awareness", "Hostile awareness",
                "Recognize nearby hostile threats and identify safer routes or places to retreat.",
                Set.of(), 100, 10));
        register(new KnowledgeDefinition("safe_light", "Safe lighting",
                "Use available light sources to reduce dangerous dark areas around important spaces.",
                Set.of("basic_tools", "basic_crafting"), 95, 12));
        register(new KnowledgeDefinition("basic_defense", "Basic defense",
                "Use defensive positioning, barriers, and available equipment to survive attacks.",
                Set.of("basic_shelter", "hostile_awareness", "basic_equipment"), 100, 18));
        register(new KnowledgeDefinition("emergency_grouping", "Emergency grouping",
                "Coordinate retreat and regrouping instead of leaving threatened villagers isolated.",
                Set.of("hostile_awareness", "village_cooperation"), 92, 22));
        register(new KnowledgeDefinition("food_reserves", "Food reserves",
                "Maintain accessible food stores and reserve seed stock against shortages.",
                Set.of("basic_shelter", "basic_food_production"), 96, 16));
        register(new KnowledgeDefinition("equipment_care", "Equipment care",
                "Choose, preserve, and replace useful equipment based on task and materials.",
                Set.of("basic_defense"), 76, 28));
        register(new KnowledgeDefinition("perimeter_patrol", "Perimeter patrol",
                "Organize patrols and report dangerous gaps around a settlement.",
                Set.of("safe_light", "basic_defense", "emergency_grouping"), 72, 35));
        register(new KnowledgeDefinition("housing_expansion", "Housing expansion",
                "Expand housing when population and safety conditions justify it.",
                Set.of("basic_shelter", "village_cooperation"), 62, 30));
        register(new KnowledgeDefinition("resource_specialization", "Resource specialization",
                "Assign residents to gathering and production roles based on settlement needs.",
                Set.of("basic_tools", "basic_food_production", "village_cooperation"), 70, 26));
        register(new KnowledgeDefinition("advanced_materials", "Advanced materials",
                "Develop more advanced materials from reliable basic production.", 
                Set.of("basic_tools", "basic_crafting", "resource_specialization"), 45, 55));
        register(new KnowledgeDefinition("redstone_experimentation", "Redstone experimentation",
                "Test automation ideas and keep only designs with verified results.",
                Set.of("basic_tools", "basic_crafting", "advanced_materials"), 25, 75));
    }

    public void register(KnowledgeDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Knowledge definition must not be null.");
        }
        for (String prerequisite : definition.prerequisites()) {
            if (prerequisite.equals(definition.id())) {
                throw new IllegalArgumentException("Knowledge cannot depend on itself: " + prerequisite);
            }
        }
        definitions.put(definition.id(), definition);
    }

    public Optional<KnowledgeDefinition> find(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public Collection<KnowledgeDefinition> all() {
        return Collections.unmodifiableCollection(definitions.values());
    }
}
