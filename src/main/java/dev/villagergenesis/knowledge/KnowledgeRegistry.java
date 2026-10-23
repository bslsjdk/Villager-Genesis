package dev.villagergenesis.knowledge;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Central catalog of teachable knowledge, ordered from immediate survival to advanced practice. */
public final class KnowledgeRegistry {
    private final Map<String, KnowledgeDefinition> definitions = new LinkedHashMap<>();

    public KnowledgeRegistry() {
        register(new KnowledgeDefinition(
                "safe_shelter", "Safe shelter",
                "Recognize a defensible place to rest and seek protection before nightfall.",
                Set.of(), 100, 8));
        register(new KnowledgeDefinition(
                "hostile_awareness", "Hostile awareness",
                "Recognize nearby hostile threats and identify safer routes or places to retreat.",
                Set.of(), 100, 10));
        register(new KnowledgeDefinition(
                "safe_light", "Safe lighting",
                "Use available light sources to reduce dangerous dark areas around important spaces.",
                Set.of(), 95, 12));
        register(new KnowledgeDefinition(
                "basic_defense", "Basic defense",
                "Use simple defensive positioning, barriers, and available equipment to survive attacks.",
                Set.of("safe_shelter", "hostile_awareness"), 100, 18));
        register(new KnowledgeDefinition(
                "emergency_grouping", "Emergency grouping",
                "Coordinate retreat and regrouping instead of leaving threatened villagers isolated.",
                Set.of("hostile_awareness"), 92, 22));
        register(new KnowledgeDefinition(
                "food_reserves", "Food reserves",
                "Maintain accessible food stores so emergencies do not immediately become starvation crises.",
                Set.of("safe_shelter"), 82, 16));
        register(new KnowledgeDefinition(
                "equipment_care", "Equipment care",
                "Choose, preserve, and replace useful equipment based on the task and available materials.",
                Set.of("basic_defense"), 76, 28));
        register(new KnowledgeDefinition(
                "perimeter_patrol", "Perimeter patrol",
                "Organize repeatable patrols and report dangerous gaps around a settlement.",
                Set.of("safe_light", "basic_defense", "emergency_grouping"), 72, 35));
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
