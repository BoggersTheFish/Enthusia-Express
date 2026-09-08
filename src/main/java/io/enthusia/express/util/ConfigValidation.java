package io.enthusia.express.util;

import org.bukkit.configuration.file.FileConfiguration;

public final class ConfigValidation {
  private ConfigValidation() {}

  public static void validate(FileConfiguration config) {
    for (String key :
        java.util.List.of(
            "mail.require-combatlogx",
            "mail.limits.one-outstanding-package-per-recipient",
            "mail.limits.one-outstanding-letter-per-recipient",
            "letters.enabled",
            "announcements.enabled",
            "notifications.join-mail.enabled",
            "sounds.enabled")) {
      if (config.contains(key) && !config.isBoolean(key))
        throw new IllegalArgumentException(key + " must be true or false");
    }
    range(config, "database.busy-timeout-ms", 5000, 1, 60000);
    range(config, "mail.raw-gold-per-item", 1, 0, 1000000);
    range(config, "mail.max-recursive-container-depth", 8, 1, 32);
    range(config, "mail.return-after-hours", 168, 1, 876000);
    range(config, "mail.purge-returned-after-hours", 168, 1, 876000);
    range(config, "mail.text-retention-hours", 720, 1, 876000);
    range(config, "mail.expiration-check-seconds", 600, 1, 86400);
    range(config, "letters.max-pages", 50, 1, 100);
    range(config, "letters.max-payload-bytes", 262144, 1024, 1048576);
    range(config, "letters.cooldown-seconds", 10, 0, 86400);
    range(config, "announcements.cooldown-seconds", 10, 0, 86400);
  }

  private static void range(
      FileConfiguration config, String key, long fallback, long min, long max) {
    if (config.contains(key) && !config.isInt(key) && !config.isLong(key))
      throw new IllegalArgumentException(key + " must be an integer");
    long value = config.getLong(key, fallback);
    if (value < min || value > max)
      throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
  }
}
