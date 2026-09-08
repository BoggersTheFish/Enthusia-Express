package io.enthusia.express.util;

import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Best-effort cosmetic feedback. Invalid sound configuration never interrupts mail transitions. */
public final class SoundFeedback {
  public enum Cue {
    PACKAGE_SEND("package-send", "minecraft:block.note_block.pling"),
    PACKAGE_CLAIM("package-claim", "minecraft:entity.item.pickup"),
    LETTER_SEND("letter-send", "minecraft:item.book.page_turn"),
    LETTER_OPEN("letter-open", "minecraft:item.book.page_turn");

    private final String key;
    private final String defaultSound;

    Cue(String key, String defaultSound) {
      this.key = key;
      this.defaultSound = defaultSound;
    }
  }

  private final JavaPlugin plugin;
  private final Set<String> warned = new java.util.HashSet<>();

  public SoundFeedback(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  public void play(Player player, Cue cue) {
    if (!plugin.getConfig().getBoolean("sounds.enabled", true)) return;
    Settings settings = settings(cue);
    if (settings == null) return;
    try {
      player.playSound(
          player.getLocation(), settings.sound(), (float) settings.volume(), (float) settings.pitch());
    } catch (RuntimeException error) {
      warnOnce(
          "sounds." + cue.key,
          "Could not play configured sound at sounds."
              + cue.key
              + ": "
              + error.getMessage());
    }
  }

  public void validate() {
    if (!plugin.getConfig().getBoolean("sounds.enabled", true)) return;
    for (Cue cue : Cue.values()) validateCue(cue);
  }

  private void validateCue(Cue cue) {
    settings(cue);
  }

  private Settings settings(Cue cue) {
    String path = "sounds." + cue.key;
    FileConfiguration config = plugin.getConfig();
    Object soundValue = config.get(path + ".sound", cue.defaultSound);
    Object volumeValue = config.get(path + ".volume", 1.0);
    Object pitchValue = config.get(path + ".pitch", 1.0);
    String sound =
        soundValue instanceof String value ? value.toLowerCase(Locale.ROOT) : "";
    double volume = volumeValue instanceof Number value ? value.doubleValue() : Double.NaN;
    double pitch = pitchValue instanceof Number value ? value.doubleValue() : Double.NaN;
    if (!validSound(sound)
        || !Double.isFinite(volume)
        || volume < 0.0
        || !Double.isFinite(pitch)
        || pitch < 0.5
        || pitch > 2.0) {
      warnOnce(path, "Invalid sound configuration at " + path + "; feedback disabled for it.");
      return null;
    }
    return new Settings(sound, volume, pitch);
  }

  static boolean validSound(String sound) {
    return sound != null && sound.matches("[a-z0-9._-]+:[a-z0-9/._-]+");
  }

  private void warnOnce(String key, String message) {
    if (warned.add(key)) {
      Logger logger = plugin.getLogger();
      if (logger != null) logger.warning(message);
    }
  }

  private record Settings(String sound, double volume, double pitch) {}
}
