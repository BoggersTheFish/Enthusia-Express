# Enthusia Express mail features

This guide describes the package placement indicator, outstanding-mail limits, join notifications and mail sounds introduced on `codex/finish-mail-features`. It covers what players see, what server owners can configure, and why each feature is implemented at its particular boundary.

## Package placement indicator

Running `/mail send <player>` opens the 27-slot shipping inventory. Slot 13 displays a grey stained-glass pane named **Place package here**, with lore listing the accepted container types: Shulker Boxes and Bundles.

The pane is a visual instruction only. It is marked with the private persistent-data key `enthusiaexpress:shipping-placeholder`, so its display name or material is never trusted as proof of identity. Only an actual player item may become shipment cargo.

### Player behavior

1. Opening the shipping inventory places the indicator in slot 13.
2. Left- or right-clicking the indicator with an item on the cursor replaces it with that item.
3. Removing the deposited item restores the indicator on the next server tick.
4. Confirm validates that the slot contains exactly one allowed, non-empty container before calculating postage or encoding data.
5. Cancel or close returns only actual deposited cargo. The indicator is discarded with the temporary GUI.

Shift-clicks, number-key swaps, double-clicks, drop-style clicks and top-inventory drags are blocked. This prevents the indicator or control buttons from escaping and prevents inventory shortcuts from bypassing slot ownership rules. If a real package is returned while the player's inventory is full, only that package is dropped at the player's location.

### Why it works this way

The GUI is representation, while the deposited container is authoritative payload. A PDC marker is stronger than matching the visible name because players can rename ordinary items. The package is copied and encoded on the server thread, then removed with its postage before asynchronous persistence. A failed database write—or an outstanding-limit rejection—compensates by returning the package and fee.

## One-outstanding-mail limits

The limits are disabled by default for backward compatibility:

```yaml
mail:
  limits:
    one-outstanding-package-per-recipient: false
    one-outstanding-letter-per-recipient: false
```

Each option applies independently to a `(sender, recipient, mail type)` combination. When both are enabled, Ben may have one unresolved package **and** one unread letter for Alice at the same time. Ben may also send mail to another recipient, and another sender may send mail to Alice. Announcements are never restricted by these settings.

### What counts as outstanding

| Type | Blocks another send | Does not block |
| --- | --- | --- |
| Package | Original delivery is `UNCLAIMED` for that sender and recipient | `CLAIMED`, `RETURNED`, `RETURN_CLAIMED`, or `PURGED` history |
| Letter | Active `UNCLAIMED` row with `unread = 1` | Read retained letters or `PURGED` history |
| Announcement | Never governed by these limits | All announcement states |

A returned package has already been reassigned to its sender, so the original recipient may receive another package. Reading a letter clears its unread flag without deleting retained history, immediately releasing the letter limit.

### Concurrency and failure behavior

The repository acquires a SQLite `BEGIN IMMEDIATE` write transaction, checks semantic outstandingness with a bounded `SELECT 1 … LIMIT 1`, and either inserts or rolls back. The check and insert therefore form one authoritative operation, including when separate repository connections compete.

The shipping GUI cannot be the authority for this rule: two sends could otherwise both observe zero outstanding rows before either inserts. For package sends, cargo and postage are returned if the transaction rejects the second send. Letter sends leave the original signed book untouched and show the configured rejection message.

## Join mail notification

Join summaries are enabled by default:

```yaml
notifications:
  join-mail:
    enabled: true
```

The message is configurable under `messages.join-mail` and supports:

- `{packages}` — claimable `UNCLAIMED` or `RETURNED` packages addressed to the joining player;
- `{letters}` — unread active letters;
- `{announcements}` — unread active announcements;
- `{total}` — the sum of the preceding values.

No message is sent when every count is zero. Claimed packages, read retained text and purged history are excluded.

### Why the notification is asynchronous

`PlayerJoinEvent` starts a query on the existing SQLite worker instead of blocking the Paper server thread. The completed observation returns through the main-thread callback queue. Before sending text, the service verifies that the player is still online and that `Bukkit.getPlayer(uuid)` is the exact same player session captured at join time. A result from a player who disconnected or reconnected is discarded as stale.

This is an observation only: joining does not claim, read or otherwise mutate mail.

## Configurable mail sounds

Sounds are globally enabled by default and can be changed per successful transition:

```yaml
sounds:
  enabled: true
  package-send:
    sound: "minecraft:block.note_block.pling"
    volume: 1.0
    pitch: 1.0
  package-claim:
    sound: "minecraft:entity.item.pickup"
    volume: 1.0
    pitch: 1.0
  letter-send:
    sound: "minecraft:item.book.page_turn"
    volume: 1.0
    pitch: 1.0
  letter-open:
    sound: "minecraft:item.book.page_turn"
    volume: 1.0
    pitch: 1.0
```

The success ordering is:

| Cue | Sound is played only after |
| --- | --- |
| `package-send` | SQLite accepts and persists the package |
| `package-claim` | The conditional claim wins and delivery to the player is accepted |
| `letter-send` | SQLite accepts and persists the letter |
| `letter-open` | The book opens and the repository accepts its read transition |

Rejected limits, database failures, failed claims and rejected reads never play a success sound. Targeted or broadcast announcements do not use the letter sound cues.

Sound names must have a namespaced form such as `minecraft:block.note_block.pling`. Volume must be finite and non-negative; pitch must be finite and between `0.5` and `2.0`. Invalid settings disable only that cue and create one server-log warning. The underlying mail transition continues because cosmetic feedback is not transaction authority. A well-formed but nonexistent custom sound may be ignored by the client and should be checked on staging.

## Messages

The following configurable message keys support these features:

| Key | Purpose |
| --- | --- |
| `messages.outstanding-package` | Package transaction was rejected and cargo/postage were returned |
| `messages.outstanding-letter` | An unread letter already exists for the pair |
| `messages.join-mail` | Join summary with package, letter, announcement and total placeholders |

All messages continue to use `messages.prefix` and ampersand color codes through the existing text system.

## Persistence and thread model

- SQLite owns authoritative mail rows and executes on one dedicated worker per repository connection.
- Bukkit inventories, item encoding/decoding, messages, books and sounds are handled on the server thread.
- Database completions enter the tracked main-thread callback queue, which is drained during graceful shutdown.
- The existing SQLite schema is sufficient; these features require no migration and remain compatible with existing databases.

Minecraft player inventory files and SQLite cannot participate in one shared transaction. Normal failures are compensated, but a forced process termination between inventory mutation and database commit is still not claimed to be exactly-once or crash-atomic. Keep player-data and plugin-database backups together.

## Verification scope

Automated tests exercise PDC marker authority, hostile click/drag paths, full-inventory return behavior, cross-connection limit races, every relevant lifecycle release, pending-summary filtering, stale join callbacks, sound success/failure ordering, disabled and malformed sound configuration, default YAML parsing, the full prior regression suite, shaded-JAR contents, and the supported Paper compile matrix.

No live Paper client was used. Before production rollout, verify the pane appearance and click feel, audible sound choices, real book presentation, join timing after restart, CombatLogX behavior and real ItemStack/PDC round trips on a staging server. See `VERIFICATION.md` for exact automated results and the live checklist.
