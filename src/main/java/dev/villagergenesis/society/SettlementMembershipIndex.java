package dev.villagergenesis.society;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic, settlement-scoped membership index.
 *
 * This is deliberately a pure in-memory domain primitive: the caller must load its mapping from
 * persistent world data and save successful changes. It does not discover settlements or infer
 * membership from proximity. A villager can belong to at most one settlement in a snapshot.
 */
public final class SettlementMembershipIndex {
    public enum ClaimResult {
        ADDED,
        ALREADY_MEMBER,
        CONFLICT
    }

    private final Map<UUID, UUID> settlementByResident;

    public SettlementMembershipIndex() {
        this(Map.of());
    }

    public SettlementMembershipIndex(Map<UUID, UUID> initialMembership) {
        Objects.requireNonNull(initialMembership, "initialMembership");
        Map<UUID, UUID> copy = new LinkedHashMap<>();
        initialMembership.forEach((resident, settlement) -> {
            Objects.requireNonNull(resident, "resident UUID");
            Objects.requireNonNull(settlement, "settlement UUID");
            copy.put(resident, settlement);
        });
        this.settlementByResident = Collections.unmodifiableMap(copy);
    }

    public Optional<UUID> settlementOf(UUID residentId) {
        Objects.requireNonNull(residentId, "residentId");
        return Optional.ofNullable(settlementByResident.get(residentId));
    }

    public Map<UUID, UUID> snapshot() {
        return settlementByResident;
    }

    public int residentCount(UUID settlementId) {
        Objects.requireNonNull(settlementId, "settlementId");
        int count = 0;
        for (UUID assigned : settlementByResident.values()) {
            if (settlementId.equals(assigned)) count++;
        }
        return count;
    }

    /**
     * Claims an unassigned resident. Existing membership is never silently overwritten.
     * Persist the returned index before treating the claim as durable.
     */
    public Claim claim(UUID residentId, UUID settlementId) {
        Objects.requireNonNull(residentId, "residentId");
        Objects.requireNonNull(settlementId, "settlementId");
        UUID existing = settlementByResident.get(residentId);
        if (existing != null) {
            return new Claim(this, existing.equals(settlementId)
                    ? ClaimResult.ALREADY_MEMBER : ClaimResult.CONFLICT);
        }
        Map<UUID, UUID> updated = new LinkedHashMap<>(settlementByResident);
        updated.put(residentId, settlementId);
        return new Claim(new SettlementMembershipIndex(updated), ClaimResult.ADDED);
    }

    /**
     * Explicitly transfers a resident only when the caller supplies the expected old settlement.
     * This compare-and-set style guard prevents stale survey results from moving residents.
     */
    public Claim transfer(UUID residentId, UUID expectedSettlementId, UUID newSettlementId) {
        Objects.requireNonNull(residentId, "residentId");
        Objects.requireNonNull(expectedSettlementId, "expectedSettlementId");
        Objects.requireNonNull(newSettlementId, "newSettlementId");
        UUID existing = settlementByResident.get(residentId);
        if (existing == null || !existing.equals(expectedSettlementId)) {
            return new Claim(this, ClaimResult.CONFLICT);
        }
        if (existing.equals(newSettlementId)) {
            return new Claim(this, ClaimResult.ALREADY_MEMBER);
        }
        Map<UUID, UUID> updated = new LinkedHashMap<>(settlementByResident);
        updated.put(residentId, newSettlementId);
        return new Claim(new SettlementMembershipIndex(updated), ClaimResult.ADDED);
    }

    public record Claim(SettlementMembershipIndex index, ClaimResult result) {
        public Claim {
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(result, "result");
        }
    }
}
