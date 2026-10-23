package dev.villagergenesis.society;

import java.util.UUID;

/**
 * Domain model for a settlement. World discovery, persistence, membership, and economy are
 * intentionally kept outside this value object so each layer can be tested independently.
 */
public record VillageSociety(
        UUID id,
        String name,
        int knownResidents,
        long lastUpdatedGameTime
) {
    public VillageSociety {
        if (id == null) {
            throw new IllegalArgumentException("Settlement id cannot be null.");
        }
        name = name == null || name.isBlank() ? "Unnamed Settlement" : name;
        if (knownResidents < 0) {
            throw new IllegalArgumentException("Resident count cannot be negative.");
        }
    }
}
