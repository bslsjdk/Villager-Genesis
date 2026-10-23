package dev.villagergenesis.society;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/** Village leadership without an age clock; leadership changes when the current leader is ineligible. */
public final class VillageLeadership {
    public record Candidate(UUID id, boolean alive, boolean present, int leadershipScore) {
        public Candidate {
            if (id == null) throw new IllegalArgumentException("Candidate id cannot be null.");
        }
        public boolean eligible() { return alive && present; }
    }

    private UUID leaderId;

    public Optional<UUID> leaderId() { return Optional.ofNullable(leaderId); }

    /**
     * Keep a living, present leader. Otherwise select the eligible resident with the highest
     * score; UUID ordering breaks ties deterministically so servers do not elect randomly each tick.
     */
    public Optional<UUID> updateLeader(Collection<Candidate> residents) {
        if (residents == null || residents.isEmpty()) {
            leaderId = null;
            return Optional.empty();
        }
        boolean incumbentEligible = residents.stream()
                .anyMatch(c -> c.id().equals(leaderId) && c.eligible());
        if (incumbentEligible) return Optional.of(leaderId);

        leaderId = residents.stream()
                .filter(Candidate::eligible)
                .max(Comparator.comparingInt(Candidate::leadershipScore)
                        .thenComparing(Candidate::id, Comparator.reverseOrder()))
                .map(Candidate::id)
                .orElse(null);
        return Optional.ofNullable(leaderId);
    }
}
