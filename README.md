# Enthusia Express 1.1.0

A Java 21 plugin for Paper 1.21.x. Packages and letters go to offline players; administrators can publish announcements to online or offline players.

See [`MAIL_FEATURES.md`](MAIL_FEATURES.md) for the complete administrator and implementation guide to the shipping indicator, outstanding-mail limits, join notifications, sounds, state transitions and failure handling.

## Commands

| Command | Behavior | Permission (in addition to `enthusiaexpress.use`) |
| --- | --- | --- |
| `/mail` or `/mail inbox [packages\|letters\|announcements]` | Open the mailbox. Arrows change pages; tabs change categories. | `enthusiaexpress.inbox` |
| `/mail send <player>` | Open a shipping inventory for a known offline player. Put one packed shulker box or bundle in the center and confirm. | `enthusiaexpress.packages.send` |
| Click a package | Claim it into an empty inventory slot. | `enthusiaexpress.packages.claim` |
| `/mail letter <player>` | Send a copy of the signed book in your main hand to a known offline player. | `enthusiaexpress.letters.send` |
| `/mail announce <player>` | Send a signed-book announcement to one known player, including online players. | `enthusiaexpress.admin.announce` |
| `/mail announce all` | Send an announcement to a snapshot of all known players, including those currently online. | `enthusiaexpress.admin.announce` |

Write and sign a book with Minecraft's normal book editor, hold it in your main hand, then send it. The original remains yours; text mail has no item fee. Click a letter or announcement to open the book. Reading clears its unread flag, and it can be read again until text retention expires. Broadcast unread state is independent for every recipient. Future first-time players are not included in past broadcasts. `all` is reserved as the broadcast target.

The general use, inbox, package and letter permissions default to everyone. Announcement publishing defaults to operators. `enthusiaexpress.admin` grants the announcement permission. Permissions are checked again inside services; there is no combat bypass permission. Commands require a player because authoring uses a held book. Recipient lookup uses the server's cached player profiles and does not perform a blocking network lookup.

## Build

Set `JAVA_HOME` to a Java 21 JDK. The included Gradle 8.14.3 wrapper checks the distribution's SHA-256.

```sh
sh ./gradlew clean build
sh ./gradlew verifyPaperCompatibility
```

On Windows, use `gradlew.bat`. Install **`build/libs/EnthusiaExpress-1.1.0.jar`**, the shaded JAR. The `-plain.jar` is not the installable artifact. SQLite and its native libraries are included; Paper and CombatLogX are not bundled.

The default compile API is Paper 1.21, with Java bytecode level 21. To run the tests with a later API classpath:

```sh
sh ./gradlew clean build -PpaperVersion=1.21.11
```

Always rebuild with no `paperVersion` override for the release artifact. `verifyPaperCompatibility` compiles against Paper 1.21, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10 and 1.21.11. See `VERIFICATION.md` for executed checks and their limits.

## Installation and configuration

1. Back up `plugins/EnthusiaExpress`, then replace the old plugin JAR with the shaded JAR.
2. Run Paper 1.21.x with Java 21. Install CombatLogX and its own required dependencies when combat protection is required.
3. Restart the server. Review `plugins/EnthusiaExpress/config.yml` and restart after edits.

Existing `mail.db` rows and package byte payloads remain supported; no destructive schema migration is performed. Existing configuration files are preserved. Missing new keys use the defaults below; add them to your existing file if you want to customize them. Invalid boolean values and invalid ranges for core mail settings fail startup. Invalid sound cue settings disable only the affected cue and log a warning.

| Setting | Default | Purpose |
| --- | --- | --- |
| `mail.require-combatlogx` | `true` | Deny mail if CombatLogX is missing or disabled. |
| `mail.raw-gold-per-item` | `1` | Fee per packed item; 0 allows free shipping. |
| `mail.max-recursive-container-depth` | `8` | Reject deeper nesting instead of undercounting it. |
| `mail.return-after-hours` | `168` | Return unclaimed packages to their senders. |
| `mail.purge-returned-after-hours` | `168` | Purge packages still unclaimed after return. |
| `mail.text-retention-hours` | `720` | Retain letters/announcements for this long after sending. |
| `mail.expiration-check-seconds` | `600` | Expiration interval; the first check is one minute after enable. |
| `mail.limits.one-outstanding-package-per-recipient` | `false` | Limit each sender/recipient pair to one unresolved package. |
| `mail.limits.one-outstanding-letter-per-recipient` | `false` | Limit each sender/recipient pair to one active unread letter. |
| `database.busy-timeout-ms` | `5000` | Wait for competing SQLite writers before failing. |
| `letters.enabled` / `announcements.enabled` | `true` | Enable authoring for the category. Existing mail stays readable. |
| `letters.max-pages` | `50` | Maximum pages for either kind of book mail. |
| `letters.max-payload-bytes` | `262144` | Maximum serialized size for either kind of book mail. |
| `letters.cooldown-seconds` / `announcements.cooldown-seconds` | `10` | Per-player wait since the last successful letter or announcement; reset after restart. |
| `notifications.join-mail.enabled` | `true` | Asynchronously summarize claimable/unread mail when a player joins. |
| `sounds.enabled` | `true` | Enable transition-success sounds globally. |
| `sounds.<cue>.sound/volume/pitch` | cue-specific | Configure package-send, package-claim, letter-send and letter-open feedback. |

The configuration file includes editable messages for common results and errors. Some GUI labels and diagnostic messages are fixed in code.

## CombatLogX

The optional reflection hook calls the published `getCombatManager().isInCombat(Player)` API. A present but incompatible or failing CombatLogX installation always blocks access, even when `require-combatlogx` is false. That option permits operation only when CombatLogX is absent/disabled. Hook errors are logged at most once a minute. Access is checked on command entry and again during mailbox callbacks and package confirmation.

## Storage and delivery behavior

SQLite runs on one dedicated worker using WAL, `synchronous=FULL` and a configurable busy timeout. Book broadcasts commit as one transaction. Claims use a conditional update, so only one caller wins, including with two repository connections. Expiration is a single transaction: only unclaimed packages return; only returned packages purge; letters and announcements expire separately. Purging erases their payload bytes while retaining the audit row. Claimed package rows remain as audit records.

Item encoding, decoding, inventories and book opening stay on the server thread. GUI state uses inventory identity, preventing stale loads and title-based ownership mistakes. The shipping slot displays a PDC-marked grey placement indicator that is never treated as cargo; direct cursor placement replaces it and removing cargo restores it. Shift-click, number-key, double-click and top-inventory drag operations are blocked. Cancel/close returns only deposited cargo. Colored bundles are recognized by their bundle metadata. Nested physical container items count toward the shipping fee along with their contents.

Outstanding-mail limits are enforced by an atomic SQLite check-and-insert transaction, including when multiple repository connections compete. Package and letter limits are independent; claimed/returned/purged packages and read retained letters do not block later mail. Join notifications count only claimable packages and unread text, then revalidate the exact online player session before messaging. Success sounds occur only after the corresponding persistence, claim or read transition succeeds. Invalid cosmetic sound settings log a warning and disable only that cue.

Pending claim callbacks recheck connection, combat, permissions, death and inventory space. A failed delivery restores the prior claim state and expiration timestamp. Graceful disable drains pending database completion callbacks before closing SQLite. A failed shipment refunds the package and fee; overflow drops at the player's location, and an offline refund saves player data.

Minecraft player inventory files and SQLite are separate storage systems. Sudden process termination or power loss between an inventory mutation and its database commit can still lose or duplicate items. This is not an exactly-once, crash-atomic delivery system. Graceful shutdown and concurrent database operations are covered by tests; keep backups of both player data and the plugin database together. Item payloads can contain newer Minecraft data, so do not downgrade a server/database after accepting newer items. This plugin targets ordinary Paper, not Folia.
