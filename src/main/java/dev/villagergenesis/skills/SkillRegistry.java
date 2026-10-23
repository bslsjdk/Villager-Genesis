package dev.villagergenesis.skills;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for reusable, inspectable skills. A skill describes an action; it does not claim that
 * the action is executable until a world adapter and verified executor are supplied.
 */
public final class SkillRegistry {
    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();

    public SkillRegistry() {
        register(new SkillDefinition("observe_environment", "Record a bounded observation of the nearby world."));
        register(new SkillDefinition("remember_location", "Store a location with its dimension and context."));
        register(new SkillDefinition("share_knowledge", "Transfer a verified fact or skill to another agent."));
        register(new SkillDefinition("test_blueprint", "Run a controlled test and record its measured outcome."));
    }

    public void register(SkillDefinition definition) {
        if (definition == null || definition.id().isBlank()) {
            throw new IllegalArgumentException("A skill must have a non-empty id.");
        }
        skills.put(definition.id(), definition);
    }

    public Optional<SkillDefinition> find(String id) {
        return Optional.ofNullable(skills.get(id));
    }

    public Collection<SkillDefinition> all() {
        return Collections.unmodifiableCollection(skills.values());
    }

    public record SkillDefinition(String id, String description) {}
}
