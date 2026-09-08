package io.enthusia.express;

import static org.junit.jupiter.api.Assertions.*;

import io.enthusia.express.db.MailRepository;
import io.enthusia.express.mail.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MailRepositoryTest {
  @TempDir Path directory;
  MailRepository repository;
  UUID sender = UUID.randomUUID(), recipient = UUID.randomUUID();
  Path file;

  @BeforeEach
  void start() {
    file = directory.resolve("mail.db");
    repository = new MailRepository(null, file.toFile(), 5000);
    repository.initialize().join();
  }

  @AfterEach
  void stop() {
    repository.close();
  }

  long insert() {
    return repository
        .insertPackage(sender, "Sender", recipient, "Recipient", new byte[] {1, 2, 3}, 4, false)
        .join();
  }

  @Test
  void sqliteUsesWalAndPersistsBytesAcrossRestart() throws Exception {
    long id = insert();
    repository.close();
    repository = new MailRepository(null, file.toFile(), 5000);
    repository.initialize().join();
    assertArrayEquals(new byte[] {1, 2, 3}, repository.get(id).join().payload());
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
      assertEquals("wal", rs.getString(1));
    }
  }

  @Test
  void concurrentInsertsAndClaimsHaveExactlyOneWinner() {
    var writes =
        IntStream.range(0, 250).mapToObj(i -> CompletableFuture.supplyAsync(this::insert)).toList();
    var ids = writes.stream().map(CompletableFuture::join).toList();
    assertEquals(250, new HashSet<>(ids).size());
    var claims =
        IntStream.range(0, 100).mapToObj(i -> repository.claim(ids.getFirst(), recipient)).toList();
    assertEquals(
        1, claims.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count());
    assertFalse(repository.claim(ids.get(1), UUID.randomUUID()).join());
  }

  @Test
  void competingConnectionsCannotDuplicateClaim() {
    long id = insert();
    MailRepository other = new MailRepository(null, file.toFile(), 5000);
    other.initialize().join();
    try {
      var claims =
          IntStream.range(0, 80)
              .mapToObj(i -> (i % 2 == 0 ? repository : other).claim(id, recipient))
              .toList();
      assertEquals(
          1, claims.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count());
    } finally {
      other.close();
    }
  }

  @Test
  void returnAndPurgeRespectBoundariesAndNeverOverwriteClaim() {
    long id = insert();
    long stamp = repository.get(id).join().updatedAt();
    repository.expire(stamp + 100, stamp, 0, 0).join();
    assertEquals(MailStatus.UNCLAIMED, repository.get(id).join().status());
    repository.expire(stamp + 200, stamp + 1, 0, 0).join();
    MailRecord returned = repository.get(id).join();
    assertEquals(sender, returned.recipient());
    assertTrue(returned.returnDelivery());
    assertEquals(MailStatus.RETURNED, returned.status());
    assertFalse(repository.claim(id, recipient).join());
    assertTrue(repository.claim(id, sender).join());
    repository.expire(stamp + 300, Long.MAX_VALUE, Long.MAX_VALUE, 0).join();
    assertEquals(MailStatus.RETURN_CLAIMED, repository.get(id).join().status());
  }

  @Test
  void unclaimedReturnPurgesPayloadAndDoesNotLoop() {
    long id = insert();
    long now = System.currentTimeMillis();
    repository.expire(now + 1, Long.MAX_VALUE, Long.MAX_VALUE, 0).join();
    assertEquals(MailStatus.RETURNED, repository.get(id).join().status());
    repository.expire(now + 2, Long.MAX_VALUE, Long.MAX_VALUE, 0).join();
    assertEquals(MailStatus.PURGED, repository.get(id).join().status());
    assertEquals(0, repository.get(id).join().payload().length);
    assertFalse(repository.claim(id, sender).join());
  }

  @Test
  void claimRacingExpirationCannotBeBothReturnedAndDelivered() {
    MailRepository other = new MailRepository(null, file.toFile(), 5000);
    other.initialize().join();
    try {
      for (int i = 0; i < 50; i++) {
        long id = insert();
        var claim = repository.claim(id, recipient);
        other.expire(System.currentTimeMillis(), Long.MAX_VALUE, 0, 0).join();
        boolean won = claim.join();
        MailRecord record = repository.get(id).join();
        assertEquals(won ? MailStatus.CLAIMED : MailStatus.RETURNED, record.status());
      }
    } finally {
      other.close();
    }
  }

  @Test
  void textsCanBeRereadAndExpireWithoutReturning() {
    long id =
        repository
            .insertMail(
                sender, "Sender", recipient, "Recipient", MailType.LETTER, new byte[] {9}, 0, false)
            .join();
    assertFalse(repository.markRead(id, sender).join());
    assertTrue(repository.markRead(id, recipient).join());
    assertTrue(repository.markRead(id, recipient).join());
    assertFalse(repository.get(id).join().unread());
    assertFalse(repository.claim(id, recipient).join());
    repository.expire(System.currentTimeMillis(), Long.MAX_VALUE, Long.MAX_VALUE, 0).join();
    assertEquals(MailStatus.UNCLAIMED, repository.get(id).join().status());
    repository.expire(System.currentTimeMillis(), 0, 0, Long.MAX_VALUE).join();
    assertEquals(MailStatus.PURGED, repository.get(id).join().status());
  }

  @Test
  void broadcastIsAtomicAndPerRecipientUnreadIsIndependent() throws Exception {
    Map<UUID, String> targets = Map.of(sender, "Sender", recipient, "Recipient");
    assertEquals(2, repository.announce(sender, "Admin", targets, new byte[] {5}).join());
    long first = repository.listInbox(sender, MailType.ANNOUNCEMENT).join().getFirst().id();
    repository.markRead(first, sender).join();
    assertTrue(repository.listInbox(recipient, MailType.ANNOUNCEMENT).join().getFirst().unread());
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = c.createStatement()) {
      st.execute(
          "CREATE TRIGGER fail_broadcast BEFORE INSERT ON mail WHEN NEW.recipient_name='Reject'"
              + " BEGIN SELECT RAISE(ABORT,'test failure'); END");
    }
    assertThrows(
        CompletionException.class,
        () ->
            repository
                .announce(
                    sender,
                    "Admin",
                    Map.of(UUID.randomUUID(), "Good", UUID.randomUUID(), "Reject"),
                    new byte[] {7})
                .join());
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM mail")) {
      assertEquals(2, rs.getInt(1));
    }
    assertTrue(insert() > 0); // transaction state recovered after rollback
  }

  @Test
  void paginationHasStableOrderAndNoOverlap() {
    for (int i = 0; i < 100; i++) insert();
    Set<Long> ids = new HashSet<>();
    for (int page = 0; page < 3; page++)
      for (MailRecord record : repository.listInbox(recipient, MailType.PACKAGE, page).join())
        assertTrue(ids.add(record.id()));
    assertEquals(100, ids.size());
    assertTrue(repository.listInbox(recipient, MailType.PACKAGE, 3).join().isEmpty());
  }

  @Test
  void gracefulCloseDrainsWritesAndRejectsNewWork() {
    var writes =
        IntStream.range(0, 100)
            .mapToObj(
                i ->
                    repository.insertPackage(sender, "S", recipient, "R", new byte[] {1}, 1, false))
            .toList();
    repository.close();
    assertTrue(writes.stream().allMatch(CompletableFuture::isDone));
    assertThrows(CompletionException.class, () -> repository.get(1).join());
  }

  @Test
  void failedDeliveryRestoresClaimWithoutResettingExpiration() {
    long id = insert();
    MailRecord original = repository.get(id).join();
    assertTrue(repository.claim(id, recipient).join());
    assertTrue(repository.restoreClaim(original).join());
    assertFalse(repository.restoreClaim(original).join());
    assertEquals(original.updatedAt(), repository.get(id).join().updatedAt());
    assertTrue(repository.claim(id, recipient).join());
  }

  @Test
  void busyTimeoutAllowsExternalWriterToFinish() throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement st = c.createStatement()) {
      st.execute("BEGIN IMMEDIATE");
      var pending = repository.insertPackage(sender, "S", recipient, "R", new byte[] {1}, 1, false);
      Thread.sleep(150);
      assertFalse(pending.isDone());
      st.execute("COMMIT");
      assertTrue(pending.get(5, TimeUnit.SECONDS) > 0);
    }
  }

  @Test
  void packageLimitIsAtomicAcrossConnectionsAndResolvedStatesReleaseIt() {
    MailRepository other = new MailRepository(null, file.toFile(), 5000);
    other.initialize().join();
    try {
      var first =
          repository.insertMailLimited(
              sender, "S", recipient, "R", MailType.PACKAGE, new byte[] {1}, 1, true);
      var second =
          other.insertMailLimited(
              sender, "S", recipient, "R", MailType.PACKAGE, new byte[] {2}, 1, true);
      assertEquals(
          1,
          java.util.stream.Stream.of(first, second)
              .map(CompletableFuture::join)
              .filter(OptionalLong::isPresent)
              .count());
      long id = first.join().isPresent() ? first.join().getAsLong() : second.join().getAsLong();
      assertTrue(repository.claim(id, recipient).join());
      assertTrue(
          repository
              .insertMailLimited(
                  sender, "S", recipient, "R", MailType.PACKAGE, new byte[] {3}, 1, true)
              .join()
              .isPresent());
    } finally {
      other.close();
    }
  }

  @Test
  void disabledLimitsAllowDuplicatesAndTypesAndRecipientsAreIndependent() {
    UUID otherRecipient = UUID.randomUUID();
    for (int i = 0; i < 2; i++)
      assertTrue(
          repository
              .insertMailLimited(
                  sender, "S", recipient, "R", MailType.PACKAGE, new byte[] {1}, 1, false)
              .join()
              .isPresent());
    assertTrue(
        repository
            .insertMailLimited(
                sender, "S", recipient, "R", MailType.LETTER, new byte[] {2}, 0, true)
            .join()
            .isPresent());
    assertTrue(
        repository
            .insertMailLimited(
                sender, "S", otherRecipient, "Other", MailType.PACKAGE, new byte[] {3}, 1, true)
            .join()
            .isPresent());
  }

  @Test
  void unreadLetterBlocksButReadRetainedHistoryAndAnnouncementsDoNot() {
    OptionalLong first =
        repository
            .insertMailLimited(
                sender, "S", recipient, "R", MailType.LETTER, new byte[] {1}, 0, true)
            .join();
    assertTrue(first.isPresent());
    assertTrue(
        repository
            .insertMailLimited(
                sender, "S", recipient, "R", MailType.LETTER, new byte[] {2}, 0, true)
            .join()
            .isEmpty());
    repository
        .insertMail(
            sender, "S", recipient, "R", MailType.ANNOUNCEMENT, new byte[] {8}, 0, false)
        .join();
    assertTrue(repository.markRead(first.getAsLong(), recipient).join());
    assertTrue(
        repository
            .insertMailLimited(
                sender, "S", recipient, "R", MailType.LETTER, new byte[] {3}, 0, true)
            .join()
            .isPresent());
  }

  @Test
  void pendingSummaryExcludesClaimedReadAndPurgedHistory() {
    long packageId = insert();
    long claimed = insert();
    assertTrue(repository.claim(claimed, recipient).join());
    long letter =
        repository
            .insertMail(
                sender, "S", recipient, "R", MailType.LETTER, new byte[] {1}, 0, false)
            .join();
    repository.markRead(letter, recipient).join();
    repository
        .insertMail(
            sender, "S", recipient, "R", MailType.LETTER, new byte[] {2}, 0, false)
        .join();
    repository
        .insertMail(
            sender, "S", recipient, "R", MailType.ANNOUNCEMENT, new byte[] {3}, 0, false)
        .join();
    MailSummary summary = repository.pendingMail(recipient).join();
    assertEquals(new MailSummary(1, 1, 1), summary);
    assertNotNull(repository.get(packageId).join());
  }

  @Test
  void returnedReturnClaimedAndPurgedPackagesDoNotBlockOriginalPair() {
    OptionalLong original =
        repository
            .insertMailLimited(
                sender, "S", recipient, "R", MailType.PACKAGE, new byte[] {1}, 1, true)
            .join();
    assertTrue(original.isPresent());
    repository.expire(System.currentTimeMillis(), Long.MAX_VALUE, 0, 0).join();
    assertEquals(MailStatus.RETURNED, repository.get(original.getAsLong()).join().status());
    assertTrue(
        repository
            .insertMailLimited(
                sender, "S", recipient, "R", MailType.PACKAGE, new byte[] {2}, 1, true)
            .join()
            .isPresent());
    assertTrue(repository.claim(original.getAsLong(), sender).join());
    assertEquals(MailStatus.RETURN_CLAIMED, repository.get(original.getAsLong()).join().status());
    repository.expire(System.currentTimeMillis(), Long.MAX_VALUE, Long.MAX_VALUE, 0).join();
    assertEquals(MailStatus.RETURN_CLAIMED, repository.get(original.getAsLong()).join().status());

    UUID anotherSender = UUID.randomUUID();
    UUID anotherRecipient = UUID.randomUUID();
    long purged =
        repository
            .insertPackage(
                anotherSender,
                "Other sender",
                anotherRecipient,
                "Other recipient",
                new byte[] {4},
                1,
                false)
            .join();
    repository.expire(System.currentTimeMillis(), Long.MAX_VALUE, 0, 0).join();
    repository.expire(System.currentTimeMillis(), Long.MAX_VALUE, Long.MAX_VALUE, 0).join();
    assertEquals(MailStatus.PURGED, repository.get(purged).join().status());
    assertTrue(
        repository
            .insertMailLimited(
                anotherSender,
                "Other sender",
                anotherRecipient,
                "Other recipient",
                MailType.PACKAGE,
                new byte[] {5},
                1,
                true)
            .join()
            .isPresent());
  }
}
