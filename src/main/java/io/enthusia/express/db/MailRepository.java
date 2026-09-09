package io.enthusia.express.db;

import io.enthusia.express.mail.MailRecord;
import io.enthusia.express.mail.MailSummary;
import io.enthusia.express.mail.MailStatus;
import io.enthusia.express.mail.MailType;
import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.OptionalLong;
import java.util.concurrent.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class MailRepository {
  private final JavaPlugin plugin;
  private final File dbFile;
  private final ExecutorService executor;
  private Connection connection;
  private final int busyTimeout;
  private boolean closed;

  public MailRepository(JavaPlugin plugin, File dbFile) {
    this(plugin, dbFile, plugin.getConfig().getInt("database.busy-timeout-ms", 5000));
  }

  public MailRepository(JavaPlugin plugin, File dbFile, int busyTimeout) {
    this.plugin = plugin;
    this.busyTimeout = busyTimeout;
    this.dbFile = dbFile;
    this.executor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "EnthusiaExpress-SQLite");
              t.setDaemon(true);
              return t;
            });
  }

  public CompletableFuture<Void> initialize() {
    return run(
        () -> {
          java.nio.file.Files.createDirectories(dbFile.getAbsoluteFile().getParentFile().toPath());
          Class.forName("org.sqlite.JDBC");
          connection = openConnection();
          try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout=" + busyTimeout);
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=FULL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS mail (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  sender_uuid TEXT,
                  sender_name TEXT NOT NULL,
                  recipient_uuid TEXT NOT NULL,
                  recipient_name TEXT NOT NULL,
                  type TEXT NOT NULL,
                  status TEXT NOT NULL,
                  payload BLOB NOT NULL,
                  packed_item_count INTEGER NOT NULL DEFAULT 0,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL,
                  unread INTEGER NOT NULL DEFAULT 1,
                  return_delivery INTEGER NOT NULL DEFAULT 0
                )
                """);
            st.execute(
                "CREATE INDEX IF NOT EXISTS idx_mail_recipient_status ON mail(recipient_uuid,"
                    + " status, type)");
            st.execute(
                "CREATE INDEX IF NOT EXISTS idx_mail_expiration ON mail(status, updated_at)");
          }
        });
  }

  public CompletableFuture<Long> insertPackage(
      UUID sender,
      String senderName,
      UUID recipient,
      String recipientName,
      byte[] payload,
      int packedCount,
      boolean returnDelivery) {
    return insertMail(
        sender,
        senderName,
        recipient,
        recipientName,
        MailType.PACKAGE,
        payload,
        packedCount,
        returnDelivery);
  }

  public CompletableFuture<Long> insertMail(
      UUID sender,
      String senderName,
      UUID recipient,
      String recipientName,
      MailType type,
      byte[] payload,
      int packedCount,
      boolean returned) {
    byte[] copy = payload.clone();
    return supply(
        () ->
            insert(
                sender, senderName, recipient, recipientName, type, copy, packedCount, returned));
  }

  /**
   * Atomically checks semantic outstandingness and inserts on the same SQLite write lock.
   * An empty result means the configured cardinality constraint rejected the proposal.
   */
  public CompletableFuture<OptionalLong> insertMailLimited(
      UUID sender,
      String senderName,
      UUID recipient,
      String recipientName,
      MailType type,
      byte[] payload,
      int packedCount,
      boolean enforceLimit) {
    if (type == MailType.ANNOUNCEMENT)
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Announcements do not use outstanding-mail limits"));
    byte[] copy = payload.clone();
    return supply(
        () -> {
          if (!enforceLimit)
            return OptionalLong.of(
                insert(sender, senderName, recipient, recipientName, type, copy, packedCount, false));
          return inTransaction(() -> hasOutstanding(sender, recipient, type)
              ? OptionalLong.empty()
              : OptionalLong.of(insert(sender, senderName, recipient, recipientName,
                  type, copy, packedCount, false)));
        });
  }

  private <T> T inTransaction(SqlSupplier<T> task) throws Exception {
    Exception failure = null;
    try {
      connection.setAutoCommit(false);
      T result = task.get();
      connection.commit();
      return result;
    } catch (Exception error) {
      failure = error;
      try {
        connection.rollback();
      } catch (SQLException rollbackError) {
        error.addSuppressed(rollbackError);
        replaceFailedConnection(error);
      }
      throw error;
    } finally {
      restoreAutoCommit(failure);
    }
  }

  private Connection openConnection() throws SQLException {
    org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
    config.setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
    config.setBusyTimeout(busyTimeout);
    config.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
    config.setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.FULL);
    config.enforceForeignKeys(true);
    return config.createConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
  }

  private void replaceFailedConnection(Exception failure) {
    try {
      connection.close();
      connection = openConnection();
    } catch (SQLException recoveryError) {
      failure.addSuppressed(recoveryError);
    }
  }

  private void restoreAutoCommit(Exception failure) throws SQLException {
    try {
      connection.setAutoCommit(true);
    } catch (SQLException resetError) {
      if (failure == null) {
        replaceFailedConnection(resetError);
        throw resetError;
      }
      failure.addSuppressed(resetError);
      replaceFailedConnection(failure);
    }
  }

  private boolean hasOutstanding(UUID sender, UUID recipient, MailType type) throws SQLException {
    String sql =
        "SELECT 1 FROM mail WHERE sender_uuid=? AND recipient_uuid=? AND type=?"
            + " AND status='UNCLAIMED' AND (?='PACKAGE' OR unread=1) LIMIT 1";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, sender.toString());
      ps.setString(2, recipient.toString());
      ps.setString(3, type.name());
      ps.setString(4, type.name());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  public CompletableFuture<MailSummary> pendingMail(UUID recipient) {
    return supply(
        () -> {
          String sql =
              "SELECT"
                  + " SUM(CASE WHEN type='PACKAGE' AND status IN ('UNCLAIMED','RETURNED') THEN 1 ELSE 0 END),"
                  + " SUM(CASE WHEN type='LETTER' AND status='UNCLAIMED' AND unread=1 THEN 1 ELSE 0 END),"
                  + " SUM(CASE WHEN type='ANNOUNCEMENT' AND status='UNCLAIMED' AND unread=1 THEN 1 ELSE 0 END)"
                  + " FROM mail WHERE recipient_uuid=?";
          try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, recipient.toString());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) return new MailSummary(0, 0, 0);
              return new MailSummary(rs.getInt(1), rs.getInt(2), rs.getInt(3));
            }
          }
        });
  }

  private long insert(
      UUID sender,
      String senderName,
      UUID recipient,
      String recipientName,
      MailType type,
      byte[] payload,
      int packedCount,
      boolean returned)
      throws SQLException {
    long now = System.currentTimeMillis();
    String sql =
        "INSERT INTO"
            + " mail(sender_uuid,sender_name,recipient_uuid,recipient_name,type,status,payload,packed_item_count,created_at,updated_at,unread,return_delivery)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,1,?)";
    try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, sender == null ? null : sender.toString());
      ps.setString(2, senderName);
      ps.setString(3, recipient.toString());
      ps.setString(4, recipientName);
      ps.setString(5, type.name());
      ps.setString(6, returned ? MailStatus.RETURNED.name() : MailStatus.UNCLAIMED.name());
      ps.setBytes(7, payload);
      ps.setInt(8, packedCount);
      ps.setLong(9, now);
      ps.setLong(10, now);
      ps.setInt(11, returned ? 1 : 0);
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        if (!rs.next()) throw new SQLException("Missing generated mail ID");
        return rs.getLong(1);
      }
    }
  }

  /** Snapshot recipients; the entire broadcast commits or rolls back together. */
  public CompletableFuture<Integer> announce(
      UUID sender, String senderName, java.util.Map<UUID, String> recipients, byte[] payload) {
    var snapshot = java.util.Map.copyOf(recipients);
    byte[] copy = payload.clone();
    return supply(
        () -> inTransaction(() -> {
            for (var recipient : snapshot.entrySet())
              insert(
                  sender,
                  senderName,
                  recipient.getKey(),
                  recipient.getValue(),
                  MailType.ANNOUNCEMENT,
                  copy,
                  0,
                  false);
            return snapshot.size();
        }));
  }

  public CompletableFuture<List<MailRecord>> listInbox(UUID recipient, MailType type) {
    return listInbox(recipient, type, 0);
  }

  public CompletableFuture<List<MailRecord>> listInbox(UUID recipient, MailType type, int page) {
    if (page < 0 || page > 1_000_000)
      return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid page"));
    return supply(
        () -> {
          List<MailRecord> out = new ArrayList<>();
          String sql =
              "SELECT * FROM mail WHERE recipient_uuid=? AND type=? AND status IN (?,?) ORDER BY"
                  + " created_at DESC, id DESC LIMIT 45 OFFSET ?";
          try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, recipient.toString());
            ps.setString(2, type.name());
            ps.setString(3, MailStatus.UNCLAIMED.name());
            ps.setString(4, MailStatus.RETURNED.name());
            ps.setInt(5, page * 45);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) out.add(read(rs));
            }
          }
          return out;
        });
  }

  public CompletableFuture<MailRecord> get(long id) {
    return supply(
        () -> {
          try (PreparedStatement ps =
              connection.prepareStatement("SELECT * FROM mail WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? read(rs) : null;
            }
          }
        });
  }

  public CompletableFuture<Boolean> claim(long id, UUID recipient) {
    return supply(
        () -> {
          MailRecord current = null;
          try (PreparedStatement read =
              connection.prepareStatement(
                  "SELECT * FROM mail WHERE id=? AND recipient_uuid=? AND type='PACKAGE' AND status"
                      + " IN (?,?)")) {
            read.setLong(1, id);
            read.setString(2, recipient.toString());
            read.setString(3, MailStatus.UNCLAIMED.name());
            read.setString(4, MailStatus.RETURNED.name());
            try (ResultSet rs = read.executeQuery()) {
              if (rs.next()) current = read(rs);
            }
          }
          if (current == null) return false;
          MailStatus next =
              current.status() == MailStatus.RETURNED
                  ? MailStatus.RETURN_CLAIMED
                  : MailStatus.CLAIMED;
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "UPDATE mail SET status=?, unread=0, updated_at=? WHERE id=? AND recipient_uuid=?"
                      + " AND status=?")) {
            ps.setString(1, next.name());
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, id);
            ps.setString(4, recipient.toString());
            ps.setString(5, current.status().name());
            return ps.executeUpdate() == 1;
          }
        });
  }

  public CompletableFuture<Boolean> restoreClaim(MailRecord record) {
    return supply(
        () -> {
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "UPDATE mail SET status=?, unread=1, updated_at=? WHERE id=? AND recipient_uuid=?"
                      + " AND status=?")) {
            ps.setString(1, record.status().name());
            ps.setLong(2, record.updatedAt());
            ps.setLong(3, record.id());
            ps.setString(4, record.recipient().toString());
            ps.setString(5, record.status() == MailStatus.RETURNED ? "RETURN_CLAIMED" : "CLAIMED");
            return ps.executeUpdate() == 1;
          }
        });
  }

  public CompletableFuture<Boolean> markRead(long id, UUID recipient) {
    return supply(
        () -> {
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "UPDATE mail SET unread=0 WHERE id=? AND recipient_uuid=? AND type IN"
                      + " ('LETTER','ANNOUNCEMENT') AND status='UNCLAIMED'")) {
            ps.setLong(1, id);
            ps.setString(2, recipient.toString());
            return ps.executeUpdate() == 1;
          }
        });
  }

  /** One transaction, no stale read/modify/write window; text mail never enters RTS. */
  public CompletableFuture<Integer> expire(
      long now, long returnCutoff, long purgeCutoff, long textCutoff) {
    return supply(
        () -> inTransaction(() -> {
            int changed;
            try (PreparedStatement ps =
                connection.prepareStatement(
                    "UPDATE mail SET status='PURGED', payload=X'', updated_at=? WHERE"
                        + " (type='PACKAGE' AND status='RETURNED' AND updated_at<?) OR (type IN"
                        + " ('LETTER','ANNOUNCEMENT') AND status='UNCLAIMED' AND created_at<?)")) {
              ps.setLong(1, now);
              ps.setLong(2, purgeCutoff);
              ps.setLong(3, textCutoff);
              changed = ps.executeUpdate();
            }
            try (PreparedStatement ps =
                connection.prepareStatement(
                    "UPDATE mail SET recipient_uuid=COALESCE(sender_uuid,recipient_uuid),"
                        + " recipient_name=CASE WHEN sender_uuid IS NULL THEN recipient_name ELSE"
                        + " sender_name END, status=CASE WHEN sender_uuid IS NULL THEN 'PURGED'"
                        + " ELSE 'RETURNED' END, payload=CASE WHEN sender_uuid IS NULL THEN X''"
                        + " ELSE payload END, unread=1, return_delivery=1, updated_at=? WHERE"
                        + " type='PACKAGE' AND status='UNCLAIMED' AND updated_at<?")) {
              ps.setLong(1, now);
              ps.setLong(2, returnCutoff);
              changed += ps.executeUpdate();
            }
            return changed;
        }));
  }

  private MailRecord read(ResultSet rs) throws SQLException {
    String sender = rs.getString("sender_uuid");
    return new MailRecord(
        rs.getLong("id"),
        sender == null ? null : UUID.fromString(sender),
        rs.getString("sender_name"),
        UUID.fromString(rs.getString("recipient_uuid")),
        rs.getString("recipient_name"),
        MailType.valueOf(rs.getString("type")),
        MailStatus.valueOf(rs.getString("status")),
        rs.getBytes("payload"),
        rs.getInt("packed_item_count"),
        rs.getLong("created_at"),
        rs.getLong("updated_at"),
        rs.getInt("unread") != 0,
        rs.getInt("return_delivery") != 0);
  }

  private CompletableFuture<Void> run(SqlRunnable task) {
    return supply(
        () -> {
          task.run();
          return null;
        });
  }

  private synchronized <T> CompletableFuture<T> supply(SqlSupplier<T> task) {
    if (closed)
      return CompletableFuture.failedFuture(new IllegalStateException("Repository is closed"));
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return task.get();
          } catch (Exception e) {
            throw new CompletionException(e);
          }
        },
        executor);
  }

  /** Called after main-thread completion callbacks have drained. */
  public void close() {
    CompletableFuture<Void> closing;
    synchronized (this) {
      if (closed) return;
      closing =
          run(
              () -> {
                if (connection != null) connection.close();
              });
      closed = true;
      executor.shutdown();
    }
    closing.join();
  }

  @FunctionalInterface
  private interface SqlRunnable {
    void run() throws Exception;
  }

  @FunctionalInterface
  private interface SqlSupplier<T> {
    T get() throws Exception;
  }
}
