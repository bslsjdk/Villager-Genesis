# Development Status: Settlement Autonomy

Last updated: 2026-10-23
Repository: https://github.com/bslsjdk/Villager-Genesis
Working branch: feat/settlement-resource-jobs
Minecraft: Java 1.21.1, NeoForge 21.1.264

This is the continuity ledger. Keep committed, CI-verified, in-game-verified, and planned work separate.

## Hard constraints
- Use real items from real inventories; never create virtual equipment, materials, or ammunition.
- Do not load new chunks. Scan only already-loaded chunks.
- Do not break player structures to gather resources or create space.
- Reuse vanilla professions and systems where possible.
- Keep PRs as drafts/open unless the user explicitly authorizes merging.
- A role/knowledge flag is not proof that an executable behavior exists.
- User primarily works from an Android phone and needs direct repository work, not manual coding instructions.

## Repository and PRs
- Working branch: feat/settlement-resource-jobs
- Draft PR #14: https://github.com/bslsjdk/Villager-Genesis/pull/14
- Draft PR #13: https://github.com/bslsjdk/Villager-Genesis/pull/13
- PR #14 base noted as release/0.2-autonomous-settlement-loop
- Do not merge either PR without explicit authorization.

## Existing foundations (source exists; not all in-game verified)
- Knowledge prerequisite graph with cycle prevention; villager memory records knowledge, outcomes, and suggestions.
- Roles include FARMER, BUILDER, GATHERER, CRAFTER, MINER, LUMBERJACK, MAYOR, MASON, GUARD, SCOUT, CARETAKER. Assignment is still UUID-based, not demand-based staffing.
- Visible storage scanning covers ordinary chests, trapped chests, and barrels in loaded chunks, with bounded radius (maximum 24).
- BUILDER shelter construction consumes 33 real oak planks; MASON shelter construction consumes 33 real cobblestone. Placement is validated and rollback attempted on failure.
- Storage expansion is limited to CRAFTER, CARETAKER, MAYOR.
- Autonomy service installs equipment, storage/shelter construction, exploration, and guard combat goals per live villager instance.
- Equipment goal takes real weapons, shields, or armor from storage. Guards may now also equip a real bow; ordinary resident self-defence remains optional.
- Guard melee goal prioritizes hostile monsters attacking villagers and records successful attack outcomes.

## Latest combat work
- 35c432ac1ad67041f4cfe9592f044be11fd72da8: threat prioritization and melee outcome recording. GitHub Actions Build run #273 / 40005041368 passed.
- b7abf184790d97f636dc7f6ca860d000f0253220: allow guards to equip bows from real storage.
- 96351d82c1b5722f68451d13c4417e7f8f7670fd: ranged attacks using real stored arrows; CI run #281 / 40005431735 was in progress when last checked.
- eb738f8839d9f027a513004208d7023038e18c66: fix no-ammunition behavior so a guard does not freeze at range and can fall back to melee. CI status for this latest commit has not yet appeared in the connector results.
- Ranged code scans bounded nearby storage, checks the chunk is loaded, removes one real arrow before creating a projectile, and only targets hostile Monster entities. Compilation/API compatibility and projectile behavior are not yet verified for the latest commit.
- No guard combat or ranged combat has been tested in a running Minecraft world.

## Unfinished work that must not be forgotten
1. Validate ranged combat against 1.21.1 APIs; test trajectory, aiming, actual arrow consumption, no-ammo fallback, and whether bows behave correctly in-game.
2. Implement real shield raising/blocking, not merely equipping a shield.
3. Implement durability-aware equipment maintenance, repair with real supplies, and replacement only from real storage.
4. Coordinate guards so they protect different threatened residents instead of dogpiling one target; define defense radius and post behavior.
5. Complete ordinary resident safety behavior: optional self-defence plus fleeing/seeking guards for residents who do not fight.
6. Improve after-combat learning based on actual outcomes, not arbitrary skill grants. Current behavior records successful attack outcomes only.
7. Add deterministic tests where feasible, inspect CI, and keep actual in-game verification separate.
8. Replace static UUID role assignment with staffing based on population, skills, vanilla profession, and settlement needs.
9. Implement real gathering, crafting, logistics, mentorship/knowledge transfer, and career mobility without duplicating vanilla systems.
10. Settlement simulation (food, housing, population, territory, diplomacy, etc.) remains incomplete; do not claim otherwise based on metadata alone.
11. Regression-check scan frequency, cooldowns, goal interruption, inventory races, persistence, loaded-chunk safety, and player-build protection.

## Next execution order
1. Check CI for eb738f8839d9f027a513004208d7023038e18c66; fix compile/test failures before adding more combat behavior.
2. Validate ranged combat and real arrow accounting.
3. Implement shield blocking and resident flee/guard-seeking behavior.
4. Add equipment durability/maintenance and coordinated threat assignment.
5. Add tests; in-game verification still requires a real Minecraft test.
6. Continue dynamic staffing, learning, resource jobs, and settlement-level simulation.

## Status language
- Committed: source is on the branch.
- CI passed: GitHub Actions completed successfully for the relevant commit.
- In-game verified: tested in Minecraft; never infer this from CI.
- Not implemented: keep listed until code and validation exist.
