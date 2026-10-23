package dev.villagergenesis.skills;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {
    @Test
    void startsWithInspectableFoundationSkills() {
        SkillRegistry registry = new SkillRegistry();

        assertTrue(registry.find("observe_environment").isPresent());
        assertTrue(registry.find("remember_location").isPresent());
        assertTrue(registry.find("test_blueprint").isPresent());
        assertEquals(4, registry.all().size());
    }

    @Test
    void rejectsBlankSkillIds() {
        SkillRegistry registry = new SkillRegistry();

        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new SkillRegistry.SkillDefinition(" ", "invalid")));
    }
}
