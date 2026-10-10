# Verification

Verified on 2026-10-10:

- JDK 25 / Gradle 9.6.0: both production JARs built successfully.
- 19 automated tests passed, including session replay, cross-player packet rejection and admin permission revocation.
- Isolated Paper 26.3 build 145: Claims 1.0.0 loaded and enabled, `/version Claims` returned the expected version, and the server stopped cleanly. Compilation uses Paper API build 169.
- A visual Minecraft client pass and the full two-player acceptance checklist below have not been performed.

## Automated

Run `./gradlew build` with JDK 25. Unit tests use temporary files and mocked Paper events; they do not alter an existing server.

The build compiles the plugin against Paper 26.3 and the mod against the actual unobfuscated Minecraft 26.3 client plus Fabric API. Local smoke testing starts an isolated Paper instance with only Claims, bound to localhost on a separate port.

## In-game acceptance checklist

These checks require Minecraft clients and should be performed before a production rollout. Compilation and unit tests do not substitute for this multiplayer/UI pass.

1. Join with two players, one with the Fabric mod and one without. Check homes, claims and admin menus in both modes, including GUI scaling, tooltips and keyboard focus. Force `/claims chest`, then restore `/claims auto`.
2. Give one player `claims.homes.2` and `claims.claims.2`. Save two homes and claim two chunks. Confirm a third is denied. Test `0`, higher/lower limits, denied nodes and inherited LuckPerms groups.
3. Claim a chunk. Verify owner/trusted/stranger interactions, multi-block placement at the edge, containers, both halves of a double chest, armor stands, hanging entities, cushions, buckets and animals.
4. Detonate TNT/creepers both inside and outside the boundary. Claimed blocks and non-player entities should survive. Player damage and wilderness block damage should remain normal.
5. Try outside pistons, water/lava, hoppers, dispensers, fire, trees and projectiles across boundaries. Check normal automation across adjacent chunks with the same owner.
6. Save a home, move away, teleport, then test movement/damage cancellation, unsafe destinations, removed trust, deleted homes, vehicles, missing worlds and cooldowns.
7. Browse an offline player's homes/claims as admin. Test confirmations and removing admin permission while a menu is open. Building bypass should require its separate permission.
8. Restart Paper and verify claims, trust and homes remain. Confirm the console has no Claims errors.
9. Attempt shift-clicks, double-clicks, hotbar swaps, drags and dropping menu items. No GUI items should be obtainable. Replayed or cross-player native menu actions must do nothing.
10. Confirm normal joining without the mod, and that the mod can join a server without Claims without errors.
