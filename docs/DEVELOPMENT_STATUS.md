# Development Status: Settlement Autonomy

Last updated: 2026-10-23
Repository: https://github.com/bslsjdk/Villager-Genesis
Working branch: `feat/settlement-resource-jobs`
Minecraft: Java 1.21.1, NeoForge 21.1.264

This file is a continuity ledger. Keep implemented, CI-verified, in-game-verified, and planned work separate. Do not mark a feature complete merely because source code was committed.

## Project goals and non-negotiable constraints

- Build villagers with meaningful social roles, knowledge, learning, cooperation, resource logistics, construction, and settlement defense.
- Reuse vanilla professions and systems rather than duplicating vanilla professions.
- Consume real items from real containers; never conjure materials, weapons, armor, or ammunition.
- Do not load new chunks. Operate only on already-loaded chunks.
- Do not break player structures to acquire materials or make room.
- Keep PRs as drafts/open unless the user explicitly asks to merge them.
- State clearly whether work is merely committed, CI-verified, or tested in a running game.
- User primarily works from an Android phone and expects direct repository work rather than instructions requiring manual coding.
- A role or knowledge flag alone is not proof that a real executor/action exists.

## Repository / PR state

- Working branch: `feat/settlement-resource-jobs`
- Draft PR #14: https://github.com/bslsjdk/Villager-Genesis/pull/14
- Draft PR #13: https://github.com/bslsjdk/Villager-Genesis/pull/13
- Base branch mentioned for PR #14: `release/0.2-autonomous-settlement-loop`
- Do not merge either PR without explicit user authorization.

## Implemented foundations (source exists; distinguish from in-game verification)

- Knowledge prerequisite graph with cycle prevention.
- Villager memory records knowledge, action outcomes, and suggestions.
- Persistent social roles include FARMER, BUILDER, GATHERER, CRAFTER, MINER, LUMBERJACK, MAYOR, MASON, GUARD, SCOUT, CARETAKER. Assignment is currently deterministic from UUID, not a dynamic staffing allocator.
- Visible storage scanner reads ordinary chests, trapped chests, and barrels in loaded chunks only. Scan radius is bounded (maximum 24).
- BUILDER shelter construction uses 33 real oak planks; MASON shelter construction uses 33 real cobblestone. Placed blocks are registered and rollback is attempted if placement fails.
- Storage expansion is restricted to CRAFTER, CARETAKER, and MAYOR.
- MAYOR was added to initial role assignment.
- Autonomy service installs equipment, storage construction, shelter construction, and exploration goals once per live villager entity instance.
- Equipment goal can take one real weapon (sword/axe), shield, or armor piece from nearby real storage and equip an empty slot. It records basic-defense knowledge only after successful equipment transfer. Guards are eligible; a stable minority of ordinary residents can be eligible too.
- Latest completed CI known at time of writing: equipment implementation commit `cd4898644a73b4dfd10c4f839d1228c5b3493043` (verify exact SHA in GitHub if needed; run #40004787792 succeeded). Earlier mayor/storage role changes also passed CI.

## Combat work currently in progress / must be verified

- New `VillageGuardCombatGoal.java` was added to provide guard-only melee response to hostile `Monster` entities.
- Goal is installed at priority 2 in `VillageAutonomyService`.
- It prioritizes monsters targeting villagers, chases with a bounded post distance, uses vanilla `doHurtTarget`, and records successful combat outcomes in villager memory.
- Recent commits:
  - `33e2126745f9abf9a05899d33f349e15e95d8cf1`: add guard-only hostile mob melee response.
  - `2f9254cd11af36362c9d900ff2ef5d66feb6db90`: install combat goal.
  - `35c432ac1ad67041f4cfe9592f044be11fd72da8`: prioritize threats attacking residents and record combat outcomes, remove an unused import.
- CI run #40005033131 was in progress when this note was written; inspect Actions before further changes. The combat implementation is NOT yet confirmed CI-passing or in-game-tested.
- Equipment implementation's CI did pass at run #40004787792, but a later run at an earlier intermediate commit failed because the service referenced a missing class before the equipment file was committed. The later equipment commit passed; do not treat that historical failure as the current final equipment status.

## Explicitly unfinished work (preserve this backlog)

1. **Guard ranged combat with bows**: select/equip real bows from storage, acquire real arrows, consume ammunition, target hostile mobs, shoot using correct 1.21.1/NeoForge APIs, avoid friendly fire and excessive pursuit. Not implemented yet.
2. **Shield blocking**: actual block/raise-shield behavior when threatened, not merely holding a shield. Not implemented yet.
3. **Equipment wear and maintenance**: respect durability, detect damaged/broken equipment, use real repair materials or vanilla-compatible repair/crafting logic, and replace only from actual available supplies. Not implemented yet.
4. **Combat coordination**: multiple guards should avoid all piling onto one target when other villagers are threatened; define protection radius/posting and response priorities. Only rudimentary target preference currently exists.
5. **Ordinary resident self-defence**: maintain optional/stable learning/equipment behavior; ensure untrained residents can flee/seek guards instead of being forced into combat. Not implemented as a complete behavior.
6. **After-combat learning**: turn observed outcomes into useful defensive knowledge/strategy only when justified by actual events, not simply grant arbitrary skills. Currently only a successful-hit outcome is recorded.
7. **Combat testing**: add deterministic tests where feasible and check GitHub Actions. Actual in-game testing remains outstanding.
8. **Dynamic role/staffing system**: current UUID-based role assignment is static. Need future allocation that accounts for settlement needs, available population, skills, and profession rather than immutable random roles.
9. **Resource jobs and social learning**: extend real gathering/crafting/logistics, teacher-to-student knowledge transfer, career specialization and mobility; avoid duplicate vanilla profession systems.
10. **Settlement simulation**: food, housing, population, territory, diplomacy and other WorldBox-like settlement behavior remain incomplete. Do not claim implemented from role/knowledge metadata alone.
11. **Safety/performance regression checks**: every world scan and action must stay within loaded chunks, avoid modifying player builds, and not repeatedly rescan containers every tick. Check cooldowns, goal interruption, inventory races, and persistence.

## Next execution order

1. Check Actions for latest combat commits and fix compile/test failures before adding more features.
2. Inspect actual 1.21.1 mappings/API for ranged bow use and ammunition consumption; implement guard ranged combat with real stored bow/arrows.
3. Implement shield blocking and a safe resident flee/guard-seeking response.
4. Add equipment durability/maintenance and better coordinated threat assignment.
5. Add tests and keep CI results recorded; game behavior still needs a real in-game check.
6. Only then continue deeper learning, adaptive role allocation, and settlement-level simulation.

## Accuracy rules for progress reports

Use separate labels:
- **Committed**: code is in the branch.
- **CI passed**: GitHub Actions for the relevant commit completed successfully.
- **In-game verified**: actually tested in Minecraft; do not infer from CI.
- **Not implemented**: keep explicitly listed until code and validation exist.

Never say bow combat, shield blocking, maintenance, coordinated defense, or full after-combat learning are complete until they are implemented and validated.
