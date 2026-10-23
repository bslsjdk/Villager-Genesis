# Villager Genesis

**An emergent civilization simulation for Minecraft Java Edition 1.21.1 (NeoForge).**

Villagers should be participants in a living world, not decorative NPCs waiting for the player. The long-term goal is to let them build societies, develop practical technologies, exchange knowledge, organize communities, and create their own history through memory, planning, cooperation, and experience.

> The player is not the center of civilization.

## Current development status

This repository is the foundation of a long-term project. The first milestone focuses on a safe, observable simulation layer before it starts overriding vanilla villager behavior.

- [x] NeoForge 1.21.1 / Java 21 project scaffold
- [x] Persistent per-villager observation memory
- [x] Throttled server-side memory updates
- [x] Initial goal-planning and skill-registry abstractions
- [x] Automated Gradle build on the current development branch (GitHub Actions)
- [ ] Verified in-game behavior (still required; CI is not a substitute)
- [x] Initial autonomous task execution for resource gathering, basic crafting, tool equipping, farming, food delivery, rest, and shelter construction
- [x] Initial social roles and role-specific work goals
- [ ] Complete settlement-wide coordination, shared resource accounting, and robust dynamic staffing
- [ ] Knowledge transfer and practical technology development
- [ ] Inter-village relations, diplomacy, laws, and conflict
- [ ] Engineering sandbox and verified redstone blueprints

## Design principles

1. **Vanilla-world-first:** use ordinary Minecraft blocks, items, villagers, and world mechanics wherever practical. NeoForge is the implementation platform.
2. **Memory is persistent:** individual experience belongs to the villager and must survive save/reload.
3. **Plans are explicit:** goals, preconditions, actions, costs, outcomes, and failure reasons should be inspectable.
4. **Learn from outcomes:** successful skills can be reused under matching conditions; failures should update the plan rather than disappear into a log.
5. **No fake technology:** a technology is not considered developed just because a counter increased. It must be built and tested in the world.
6. **Scalable simulation:** process nearby loaded entities directly and use carefully reconciled abstract simulation for distant settlements later.
7. **Measure before claiming:** each milestone needs reproducible tests, logs, and in-game verification.

## Build

Requirements: a 64-bit JDK 21 and Gradle 8.12 or a compatible Gradle installation.

```bash
gradle build
```

The GitHub Actions workflow also builds the project on pushes and pull requests. The resulting mod JAR is placed in `build/libs/`.

## Scope of the first milestone

The current memory layer records when a villager was first observed, the last observed position and dimension, elapsed observation count, and movement observations. Updates are throttled rather than performing planning work on every entity tick.

**Important:** the project now includes experimental server-side villager goals that gather real drops, craft basic items from carried materials, plant/harvest tracked crops, deliver food to nearby storage, rest without a bed, and build mod-owned shelters from real blocks/items. This is still an incomplete prototype, not a finished autonomous civilization. The latest CI build has passed, but actual in-game behavior, goal interactions, long-running settlement stability, and save/reload behavior still require verification. A successful build must never be presented as proof that these behaviors work in-game.

## License

All Rights Reserved for now. Do not redistribute or relicense without checking the repository owner’s intent.

## Founding and shelter prototype

- Residents can rest on safe ground at night when no nearby bed is available; this does not create a vanilla bed-sleep state.
- A resident carrying at least nine real plank blocks can attempt to build a small open-front starter shelter without needing a chest, crafting table, wool, or bed.
- The larger registered shelter remains a separate construction path that consumes real stored materials. Both construction systems avoid replacing non-air blocks and use the structure registry for ownership metadata.
- These behaviors are experimental and have not yet been confirmed in a live Minecraft world.
