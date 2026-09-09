# Enthusia Express 1.1.0 — verification

Verified 2026-09-09 on Windows x86-64 with Azul Zulu Java 21.0.8 and Gradle 8.14.3. This rerun includes all three SQLite rollback recovery regressions.

## Results

| Check | Result |
| --- | --- |
| Included wrapper: `gradlew.bat --no-daemon --offline clean build verifyPaperCompatibility` | **PASS** — clean release build, baseline Paper 1.21 API |
| Automated tests with Paper 1.21 API | **61 passed**, 0 failed, 0 skipped |
| Automated tests with Paper 1.21.8 API | **61 passed**, 0 failed, 0 skipped |
| Automated tests with Paper 1.21.11 API | **61 passed**, 0 failed, 0 skipped |
| `verifyPaperCompatibility` | **PASS** — all 11 API configurations compiled |
| CombatLogX published API | **PASS** — reflection hook exercised with the real 11.7-SNAPSHOT API interfaces and core 2.9-SNAPSHOT |
| Shaded SQLite JDBC 3.50.3.0 | **PASS** — isolated classloader loads the driver from the distributable JAR, creates a real SQLite database and reads/writes it using its native Windows library |
| Plugin class bytecode | **PASS** — Java 21 (class version 65), `api-version: '1.21'` |

The compile matrix covers **1.21, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11**. Paper's Maven repository returned 404 for the 1.21.2 API metadata, so no 1.21.2 result is claimed. Compiling every source with each API verifies source/API compatibility. The three test runs exercise those API classpaths with mocked Bukkit players and inventories; they are not live Minecraft server sessions.

## Completed functionality

- Signed-book letters sent to known offline players; originals are retained.
- Admin-only targeted announcements and transactional broadcasts to all currently known players.
- Book opening from Letters and Announcements tabs, persistent per-recipient unread state and repeat reading.
- Paginated mailboxes, conditional package claims, typed expiration and configurable text retention.
- Granular permission declarations and service-level checks, configurable authoring limits, cooldowns, SQLite timeout and expiration cadence, and startup configuration validation.
- Shipping cancel refunds, inventory identity tracking, guarded click/drag actions, stale callback suppression, disconnected/blocked claim restoration and graceful shutdown completion draining.
- Stable Gradle wrapper with a verified distribution checksum, baseline Java/Paper target, bundled JDBC/native resources and a distinct non-installable plain JAR.
- PDC-authorized package-slot placement indicator with guarded cursor replacement, restoration after removal, and no return/persistence authority.
- Optional per-sender/recipient atomic outstanding limits for packages and unread letters, with independent mail types and lifecycle-aware release.
- Asynchronous join summaries for claimable packages and unread text, with exact-session revalidation before feedback.
- Configurable best-effort sounds after successful package send/claim and letter send/read transitions; rejected and failed transitions stay silent.

## What the tests exercise

**Database (20 tests):** WAL persistence across reopen; 250 concurrent inserts; 100 simultaneous claims with one winner; claims through two independent connections; 50 claim/expiration races; wrong-recipient rejection; exact return-cutoff behavior; RTS recipient reassignment; return claim versus purge; one-way return/purge with payload erasure; text read/retention without RTS; transactional broadcast rollback and independent unread state; stable pagination of 100 messages; shutdown draining; restoration preserving the original expiration timestamp; real SQLite writer contention; atomic outstanding limits across competing connections; disabled-limit multiplicity; type/recipient independence; read-letter release; resolved package states; and pending-summary semantics. Three recovery regressions force SQLite to roll back limited sends, announcements, and expiry; they verify the original failure survives and later transactions succeed.

**Combat/configuration (7 tests):** required/optional missing and disabled dependency behavior; safe/tagged players; missing methods; null managers; invocation failure; actual published API method compatibility; rejection of unsafe numeric or mistyped boolean configuration; and parsing/validation of the shipped default YAML.

**Books/mailboxes (16 tests):** signed-book copying; duplicate pending-send blocking; admin publishing permission; offline/combat/feature gates; oversized/unsigned rejection; broadcast recipient snapshot; atomic letter-limit routing; accepted, rejected and failed send feedback; book opening for both text categories; marking read without claiming; successful versus rejected read feedback; successful versus failed claim feedback; disconnect compensation; combat beginning during a read; and stale mailbox load after closure.

**Inventory/lifecycle/artifact (15 tests):** nested item totals and depth rejection; stacked-container multiplication and integer overflow; cyclic container data rejected at a 10,000-level limit without call-stack recursion; shulker depth boundaries and empty slots; bundle metadata recognition; placeholder PDC identity, cursor replacement, restoration scheduling, close exclusion and overflow handling; blocked shift/number/double clicks and top-inventory drags; package acceptance/rejection/failure sound ordering and compensation; main-thread shutdown callback draining including nested compensation; isolated loading and real SQL execution from the installable shaded JAR, native-resource presence, metadata and absence of bundled Paper/CombatLogX classes.

**Join/sound configuration (3 tests):** mixed and zero pending summaries; disabled notification; stale disconnected session suppression; valid sound playback; global disable; and malformed cosmetic configuration degradation.

## Recursion audit

The mutually recursive container traversal was replaced by an iterative traversal using explicit stack frames. Depth limits, nested item multipliers and checked integer arithmetic are preserved. The existing `mail.max-recursive-container-depth` configuration key remains supported for compatibility; it now limits iterative nesting.

The Java compiler resolved calls, constructors and method references across all production and test Java source files; the resulting project call graph contains no cycles. The audit first detected both mutually recursive methods in the previous implementation. Callback and inventory-close paths were also inspected. This audit covers this project's Java source, not internal implementations of Paper, CombatLogX, Java or SQLite dependencies, or the archived original source ZIP. The audit logs accompany the verification artifacts.

## Practical limits

No Paper server or game client was launched, and no live CombatLogX installation was available. Actual combat-tag events, grey-pane appearance/click feel, audible sound choices, client-side book rendering, real ItemStack/PDC/container round trips, join timing, server restart with player inventories, and plugin interactions remain **unverified in a live environment**. The tests use real SQLite and real API dependencies, with mocks for Bukkit runtime objects. Windows SQLite native loading passed in this rerun; the earlier Linux run remains historical evidence, and other platforms were not rerun.

Inventory files and SQLite cannot commit as one transaction. A forced process kill or power loss between an inventory mutation and its SQL commit can still lose or duplicate items. Normal shutdown and the tested concurrency paths are protected, but this release does not claim crash-atomic, exactly-once delivery. Keep matching world/player/plugin backups, and avoid downgrading item payloads created on newer Minecraft versions.

Before production rollout, run the included manual checks on a staging copy: inspect/place/remove the package indicator; attempt shift, number-key, drag and double-click theft; close/cancel with cargo and a full inventory; send/claim a package and hear both sounds; exercise both outstanding limits before and after lifecycle resolution; join with mixed pending mail and after restart; send/open a signed letter and hear both sounds; verify announcements remain unrestricted; disable notifications and sounds; try an invalid sound; exercise CombatLogX before and during mail use; and shorten expiration to observe return and purge. These checks are explicitly not marked passed here.

Some supported Bukkit methods emit deprecation notes, and Mockito emits a JVM instrumentation/class-sharing warning. These are warnings; the final Gradle build has no failed task or test. No Gradle script deprecation warning remains in the final matrix build.

## Build environment notes

This rerun used a workspace-local Azul Zulu Java 21 JDK and Gradle cache. A temporary drive mapping accommodated the Windows sandbox. No environment workaround is embedded in the plugin. Java 21 and repository network access for uncached dependencies are normal build prerequisites; Paper and CombatLogX dependencies use upstream snapshot repositories.

## Artifacts and provenance

Install `EnthusiaExpress-1.1.0.jar` (SHA-256 `3f082d19ea940e181164d9b00e79ba42f392eee0f8d5db63be25156d86d663c9`). The source, wrapper, tests and configuration are in this repository. Build outputs are generated under `build/`; the release JAR is `build/libs/EnthusiaExpress-1.1.0.jar`. The accompanying `verification-summary.json` records the API matrix, test counts and JAR checksum. JUnit XML and reports are generated under `build/` by the executed verification commands.

Original source archive SHA-256: `2e63bfa89d279903a982489a1617b95f85b6bdda782e77e287a6071ead7f4492`.

The Java target and plugin metadata follow [Paper's Java requirements](https://docs.papermc.io/paper/getting-started/) and [plugin.yml documentation](https://docs.papermc.io/paper/dev/plugin-yml/). The combat hook matches [CombatLogX's documented API](https://github.com/SirBlobman/CombatLogX/blob/main/api/README.MD) and [ICombatManager.isInCombat(Player)](https://github.com/SirBlobman/CombatLogX/blob/main/api/src/main/java/com/github/sirblobman/combatlogx/api/manager/ICombatManager.java). The documentation still lists 11.6-SNAPSHOT, while the [published repository metadata](https://nexus.sirblobman.xyz/public/com/github/sirblobman/combatlogx/api/maven-metadata.xml) resolved to 11.7-SNAPSHOT for these tests.
