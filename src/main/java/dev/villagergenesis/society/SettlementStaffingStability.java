package dev.villagergenesis.society;

import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Pure hysteresis gate for settlement staffing changes.
 *
 * A proposed target set must remain unchanged for a minimum duration and the settlement must
 * have waited out its post-application cooldown. Callers own persistence of State and must only
 * evaluate observations from a single, explicitly identified settlement.
 */
public final class SettlementStaffingStability {
    /** lastAppliedAt == -1 means this settlement has never applied a staffing change. */
    public record State(String observedFingerprint, long observedSince, long lastAppliedAt) {
        public State {
            if (observedFingerprint == null || observedFingerprint.isBlank()) {
                throw new IllegalArgumentException("Observed staffing fingerprint is required.");
            }
            if (observedSince < 0 || lastAppliedAt < -1) {
                throw new IllegalArgumentException("Game times must be non-negative, except the initial -1 marker.");
            }
        }

        public static State initial(String fingerprint, long now) {
            return new State(requireFingerprint(fingerprint), now, -1L);
        }
    }

    public record Decision(State state, boolean readyToApply, String reason) {
        public Decision {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private SettlementStaffingStability() {}

    /**
     * Observes a proposal. A changed proposal restarts the stability window and can never be
     * applied during that same observation, preventing one-frame survey noise from causing churn.
     */
    public static Decision observe(State state, String proposedFingerprint, long now,
                                   long minimumStableTicks, long cooldownTicks) {
        Objects.requireNonNull(state, "state");
        String proposed = requireFingerprint(proposedFingerprint);
        if (now < 0 || minimumStableTicks < 0 || cooldownTicks < 0) {
            throw new IllegalArgumentException("Times and durations must be non-negative.");
        }
        if (now < state.observedSince()
                || (state.lastAppliedAt() >= 0 && now < state.lastAppliedAt())) {
            throw new IllegalArgumentException("Game time cannot move backwards.");
        }

        if (!state.observedFingerprint().equals(proposed)) {
            return new Decision(new State(proposed, now, state.lastAppliedAt()), false,
                    "Proposal changed; stability window restarted.");
        }
        if (now - state.observedSince() < minimumStableTicks) {
            return new Decision(state, false, "Proposal has not remained stable long enough.");
        }
        if (state.lastAppliedAt() >= 0 && now - state.lastAppliedAt() < cooldownTicks) {
            return new Decision(state, false, "Settlement staffing cooldown is active.");
        }
        return new Decision(state, true, "Proposal is stable and cooldown has elapsed.");
    }

    /** Records a successfully applied change; do not call this before application succeeds. */
    public static State markApplied(State state, String appliedFingerprint, long now) {
        Objects.requireNonNull(state, "state");
        String applied = requireFingerprint(appliedFingerprint);
        if (!state.observedFingerprint().equals(applied)) {
            throw new IllegalArgumentException("Cannot apply a fingerprint that was not observed.");
        }
        if (now < state.observedSince() || now < 0
                || (state.lastAppliedAt() >= 0 && now < state.lastAppliedAt())) {
            throw new IllegalArgumentException("Game time cannot move backwards.");
        }
        return new State(applied, state.observedSince(), now);
    }

    /** Stable role-order encoding suitable for persistence and deterministic comparisons. */
    public static String fingerprint(Map<VillageRoleService.Role, Integer> targets) {
        Objects.requireNonNull(targets, "targets");
        StringJoiner joiner = new StringJoiner("|");
        for (VillageRoleService.Role role : VillageRoleService.Role.values()) {
            int count = targets.getOrDefault(role, 0);
            if (count < 0) throw new IllegalArgumentException("Staffing targets cannot be negative.");
            joiner.add(role.id() + "=" + count);
        }
        return joiner.toString();
    }

    private static String requireFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("Staffing fingerprint is required.");
        }
        return fingerprint;
    }
}
