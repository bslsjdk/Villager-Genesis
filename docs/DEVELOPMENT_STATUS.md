# Development Status: Settlement Autonomy

Last updated: 2026-10-23
Repository: https://github.com/bslsjdk/Villager-Genesis
Working branch: feat/settlement-resource-jobs
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

- Working branch: feat/settlement-resource-jobs
- Draft PR #14: https://github.com/bslsjdk/Villager-Genesis/pull/14
- Draft PR #13: https://github.com/bslsjdk/Villager-Genesis/pull/13
- Base branch mentioned for PR #14: release/0.2-autonomous-settlement-loop
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
- Equipment goal takes real weapons, shields, or armor from nearby real storage and equips an empty slot. Guards can now also equip a real bow from storage. It records basic-defense knowledge only after successful equipment transfer. Guards are eligible; a stable minority of ordinary residents can be eligible too.
- Guard combat goal prioritizes hostile mobs targeting villagers, uses melee combat at close range, and records successful outcomes.
- New ranged-combat code attempts to take one real arrow from nearby scanned storage per shot, consumes it before spawning the projectile, and fires only at hostile Monster targets. This code is committed but its compilation/API compatibility and behavior still need validation.

## Recent commits and validation

- 35c432ac1ad67041f4cfe9592f044be11fd72da8: prioritize threats attacking residents and record guard combat outcomes. GitHub Actions Build run #273 (40005041368) completed successfully.
- b7abf184790d97f636dc7f6ca860d000f0253220: allow guards to equip real bows from village storage.
- 96351d82c1b5722f68451d13c4417e7f8f7670fd: add guard bow attacks using real stored arrows.
- At the time of this update, the GitHub connector has not yet returned a workflow run/status for commit 96351d82c1b5722f68451d13c4417e7f8f7670fd. Treat ranged-combat compilation as pending, not passed.
- No Minecraft in-game test has been performed for guard combat or ranged combat.

## Explicitly unfinished work (preserve this backlog)

1. Ranged combat validation: confirm the 1.21.1 ArrowItem/projectile API compiles; test trajectory, aim, arrow consumption, and missing-ammunition behavior.
2. Shield blocking: actual block/raise-shield behavior when threatened, not merely holding a shield. Not implemented yet.
3. Equipment wear and maintenance: respect durability, detect damaged/broken equipment, use real repair materials or vanilla-compatible repair/crafting logic, and replace only from actual available supplies.
4. Combat coordination: multiple guards should avoid all piling onto one target when other villagers are threatened; define protection radius/posting and response priorities. Only rudimentary target preference currently exists.
5. Ordinary resident self-defence: maintain optional/stable learning/equipment behavior; ensure untrained residents can flee/seek guards instead of being forced into combat. Not implemented as a complete behavior.
6. After-combat learning: turn observed outcomes into useful defensive knowledge/strategy only when justified by actual events, not simply grant arbitrary skills. Currently only successful attack outcomes are recorded.
7. Combat testing: add deterministic tests where feasible and check GitHub Actions. Actual in-game testing remains outstanding.
8. Dynamic role/staffing system: current UUID-based role assignment is static. Need future allocation that accounts for settlement needs, available population, skills, and profession rather than immutable random roles.
9. Resource jobs and social learning: extend real gathering/crafting/logistics, teacher-to-student knowledge transfer, career specialization and mobility; avoid duplicate vanilla profession systems.
10. Settlement simulation: food, housing, population, territory, diplomacy and other WorldBox-like settlement behavior remain incomplete. Do not claim implemented from role/knowledge metadata alone.
11. Safety/performance regression checks: every world scan and action must stay within loaded chunks, avoid modifying player builds, and not repeatedly rescan containers every tick. Check cooldowns, goal interruption, inventory races, and persistence.

## Next execution order

1. Check CI for commit 96351d82c1b5722f68451d13c4417e7f8f7670fd; fix compilation/test failures before adding more combat behavior.
2. Validate ranged combat, including real arrow consumption and correct projectile API.
3. Implement shield blocking and a safe resident flee/guard-seeking response.
4. Add equipment durability/maintenance and better coordinated threat assignment.
5. Add tests and keep CI results recorded; game behavior still needs a real in-game check.
6. Only then continue deeper learning, adaptive role allocation, and settlement-level simulation.

## Accuracy rules for progress reports

Use separate labels:
- Committed: code is in the branch.
- CI passed: GitHub Actions for the relevant commit completed successfully.
- In-game verified: actually tested in Minecraft; do not infer from CI.
- Not implemented: keep explicitly listed until code and validation exist.

Never say shield blocking, maintenance, coordinated defense, complete resident self-defence, or full after-combat learning are complete until implemented and validated.
