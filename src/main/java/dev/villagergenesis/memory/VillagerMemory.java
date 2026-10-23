package dev.villagergenesis.memory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.core.BlockPos;
import dev.villagergenesis.knowledge.KnowledgeBaseline;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Small persistent memory attached to a villager through NeoForge's entity persistent data.
 *
 * Keep this representation compact. Rich episodic memories and learned skills will be layered
 * on later, rather than serializing a large narrative every tick.
 */
public final class VillagerMemory {
    public static final int SCHEMA_VERSION = 1;

    private static final String ROOT = "villager_genesis";
    private static final String SCHEMA = "schema";
    private static final String FIRST_SEEN = "first_seen";
    private static final String LAST_SEEN = "last_seen";
    private static final String OBSERVATIONS = "observations";
    private static final String POSITION_CHANGES = "position_changes";
    private static final String LAST_X = "last_x";
    private static final String LAST_Y = "last_y";
    private static final String LAST_Z = "last_z";
    private static final String LAST_DIMENSION = "last_dimension";
    private static final String CURRENT_GOAL = "current_goal";
    private static final String KNOWN_KNOWLEDGE = "known_knowledge";
    private static final String SUGGESTED_GOAL = "suggested_goal";
    private static final String SUGGESTED_GOAL_SCORE = "suggested_goal_score";
    private static final String SUGGESTED_GOAL_REASON = "suggested_goal_reason";
    private static final String SUGGESTED_GOAL_AT = "suggested_goal_at";

    private final CompoundTag data;

    VillagerMemory(CompoundTag data) {
        this.data = data;
    }

    public static VillagerMemory forVillager(Villager villager) {
        CompoundTag entityData = villager.getPersistentData();
        CompoundTag memory;
        if (entityData.contains(ROOT, Tag.TAG_COMPOUND)) {
            memory = entityData.getCompound(ROOT);
        } else {
            memory = new CompoundTag();
            entityData.put(ROOT, memory);
        }

        if (!memory.contains(SCHEMA)) {
            memory.putInt(SCHEMA, SCHEMA_VERSION);
            memory.putLong(FIRST_SEEN, villager.level().getGameTime());
            memory.putLong(LAST_SEEN, villager.level().getGameTime());
            memory.putInt(OBSERVATIONS, 0);
            memory.putInt(POSITION_CHANGES, 0);
            BlockPos pos = villager.blockPosition();
            memory.putInt(LAST_X, pos.getX());
            memory.putInt(LAST_Y, pos.getY());
            memory.putInt(LAST_Z, pos.getZ());
            memory.putString(LAST_DIMENSION, villager.level().dimension().location().toString());
            memory.putString(CURRENT_GOAL, "observe");
        }

        if (!memory.contains(KNOWN_KNOWLEDGE, Tag.TAG_LIST)) {
            ListTag knowledge = new ListTag();
            for (String id : KnowledgeBaseline.existingVillageKnowledge()) {
                knowledge.add(StringTag.valueOf(id));
            }
            memory.put(KNOWN_KNOWLEDGE, knowledge);
        }

        return new VillagerMemory(memory);
    }

    /** Record one scheduled observation. Call on the logical server only. */
    public void observe(Villager villager) {
        BlockPos pos = villager.blockPosition();
        boolean moved = pos.getX() != data.getInt(LAST_X)
                || pos.getY() != data.getInt(LAST_Y)
                || pos.getZ() != data.getInt(LAST_Z);
        if (moved) {
            data.putInt(POSITION_CHANGES, data.getInt(POSITION_CHANGES) + 1);
        }

        data.putInt(LAST_X, pos.getX());
        data.putInt(LAST_Y, pos.getY());
        data.putInt(LAST_Z, pos.getZ());
        data.putString(LAST_DIMENSION, villager.level().dimension().location().toString());
        data.putLong(LAST_SEEN, villager.level().getGameTime());
        data.putInt(OBSERVATIONS, data.getInt(OBSERVATIONS) + 1);
    }

    public long firstSeen() {
        return data.getLong(FIRST_SEEN);
    }

    public long lastSeen() {
        return data.getLong(LAST_SEEN);
    }

    public int observations() {
        return data.getInt(OBSERVATIONS);
    }

    public int positionChanges() {
        return data.getInt(POSITION_CHANGES);
    }

    /** Knowledge IDs currently stored on this villager. */
    public Set<String> knownKnowledge() {
        Set<String> result = new LinkedHashSet<>();
        ListTag knowledge = data.getList(KNOWN_KNOWLEDGE, Tag.TAG_STRING);
        for (int i = 0; i < knowledge.size(); i++) {
            result.add(knowledge.getString(i));
        }
        return Set.copyOf(result);
    }

    /** Add knowledge after it has been taught or verified through practical experience. */
    public void learnKnowledge(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            throw new IllegalArgumentException("Knowledge id must not be blank.");
        }
        ListTag knowledge = data.getList(KNOWN_KNOWLEDGE, Tag.TAG_STRING);
        for (int i = 0; i < knowledge.size(); i++) {
            if (knowledgeId.equals(knowledge.getString(i))) {
                return;
            }
        }
        knowledge.add(StringTag.valueOf(knowledgeId));
        data.put(KNOWN_KNOWLEDGE, knowledge);
    }

    public String currentGoal() {
        return data.getString(CURRENT_GOAL);
    }

    public void setCurrentGoal(String goal) {
        data.putString(CURRENT_GOAL, goal);
    }

    /** Persist a planner proposal separately from any executor-owned active goal. */
    public void recordSuggestion(String goal, int score, String reason, long gameTime) {
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("Suggested goal must not be blank.");
        data.putString(SUGGESTED_GOAL, goal);
        data.putInt(SUGGESTED_GOAL_SCORE, Math.max(-100000, Math.min(100000, score)));
        data.putString(SUGGESTED_GOAL_REASON, reason == null ? "" : reason.substring(0, Math.min(256, reason.length())));
        data.putLong(SUGGESTED_GOAL_AT, Math.max(0L, gameTime));
    }

    public String suggestedGoal() { return data.getString(SUGGESTED_GOAL); }
    public int suggestedGoalScore() { return data.getInt(SUGGESTED_GOAL_SCORE); }
    public String suggestedGoalReason() { return data.getString(SUGGESTED_GOAL_REASON); }
    public long suggestedGoalAt() { return data.getLong(SUGGESTED_GOAL_AT); }

    public Snapshot snapshot() {
        return new Snapshot(
                firstSeen(),
                lastSeen(),
                observations(),
                positionChanges(),
                data.getString(LAST_DIMENSION),
                currentGoal()
        );
    }

    public record Snapshot(
            long firstSeen,
            long lastSeen,
            int observations,
            int positionChanges,
            String lastDimension,
            String currentGoal
    ) {}
}
