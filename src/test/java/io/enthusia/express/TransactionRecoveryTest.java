package io.enthusia.express;

import static org.junit.jupiter.api.Assertions.*;

import io.enthusia.express.db.MailRepository;
import io.enthusia.express.mail.MailType;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransactionRecoveryTest {
  @TempDir Path directory;

  @Test
  void announcementRollbackPreservesFailureAndRecovers() throws Exception {
    verifyRecovery(false);
  }

  @Test
  void expiryRollbackPreservesFailureAndRecovers() throws Exception {
    verifyRecovery(true);
  }

  private void verifyRecovery(boolean expiry) throws Exception {
    Path file = directory.resolve("recovery.db");
    MailRepository repository = new MailRepository(null, file.toFile(), 1000);
    UUID sender = UUID.randomUUID();
    UUID recipient = UUID.randomUUID();
    repository.initialize().join();
    try {
      repository.announce(sender, "Sender", Map.of(recipient, "Recipient"), new byte[] {1}).join();
      try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
          Statement statement = connection.createStatement()) {
        statement.execute("CREATE TRIGGER reject_operation BEFORE " + (expiry ? "UPDATE" : "INSERT")
            + " ON mail BEGIN SELECT RAISE(ROLLBACK, 'original operation rejection'); END");
      }
      CompletionException failure = assertThrows(CompletionException.class, () -> {
        if (expiry) repository.expire(System.currentTimeMillis(), 0, 0, Long.MAX_VALUE).join();
        else repository.announce(sender, "Sender", Map.of(recipient, "Recipient"), new byte[] {2}).join();
      });
      assertTrue(failure.getCause().getMessage().contains("original operation rejection"));
      try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
          Statement statement = connection.createStatement()) {
        statement.execute("DROP TRIGGER reject_operation");
      }
      assertEquals(1, repository.listInbox(recipient, MailType.ANNOUNCEMENT).join().size());
      assertTrue(repository.insertMailLimited(sender, "Sender", recipient, "Recipient",
          MailType.PACKAGE, new byte[] {1}, 1, true).join().isPresent());
      assertEquals(1, repository.announce(sender, "Sender", Map.of(recipient, "Recipient"),
          new byte[] {2}).join());
      assertEquals(2, repository.expire(System.currentTimeMillis(), 0, 0, Long.MAX_VALUE).join());
    } finally {
      repository.close();
    }
  }

  @Test
  void sqliteRollbackPreservesOriginalErrorAndAllowsLaterTransactions() throws Exception {
    Path file = directory.resolve("mail.db");
    MailRepository repository = new MailRepository(null, file.toFile(), 1000);
    UUID sender = UUID.randomUUID();
    UUID recipient = UUID.randomUUID();
    repository.initialize().join();
    try {
      try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
          Statement statement = connection.createStatement()) {
        statement.execute("CREATE TRIGGER reject_mail BEFORE INSERT ON mail BEGIN "
            + "SELECT RAISE(ROLLBACK, 'original mail rejection'); END");
      }
      CompletionException failure = assertThrows(CompletionException.class,
          () -> repository.insertMailLimited(sender, "Sender", recipient, "Recipient",
              MailType.PACKAGE, new byte[] {1}, 1, true).join());
      assertTrue(failure.getCause().getMessage().contains("original mail rejection"));
      try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
          Statement statement = connection.createStatement()) {
        statement.execute("DROP TRIGGER reject_mail");
      }
      assertTrue(repository.insertMailLimited(sender, "Sender", recipient, "Recipient",
          MailType.PACKAGE, new byte[] {1}, 1, true).join().isPresent());
      assertEquals(1, repository.announce(sender, "Sender", Map.of(recipient, "Recipient"),
          new byte[] {2}).join());
      assertEquals(0, repository.expire(System.currentTimeMillis(), 0, 0, 0).join());
    } finally {
      repository.close();
    }
  }
}
