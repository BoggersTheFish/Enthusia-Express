package io.enthusia.express;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.enthusia.express.hook.CombatLogXHook;
import io.enthusia.express.util.ConfigValidation;
import java.util.logging.Logger;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;

class CombatLogXHookTest {
  JavaPlugin plugin;
  PluginManager manager;
  YamlConfiguration config;
  Player player;
  CombatLogXHook hook;

  public interface CombatPlugin extends Plugin {
    CombatManager getCombatManager();
  }

  public interface CombatManager {
    boolean isInCombat(Player player);
  }

  @BeforeEach
  void start() {
    plugin = mock(JavaPlugin.class);
    manager = mock(PluginManager.class);
    Server server = mock(Server.class);
    config = new YamlConfiguration();
    player = mock(Player.class);
    when(plugin.getServer()).thenReturn(server);
    when(server.getPluginManager()).thenReturn(manager);
    when(plugin.getConfig()).thenReturn(config);
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    hook = new CombatLogXHook(plugin);
  }

  @Test
  void missingAndDisabledDependenciesFollowRequiredSetting() {
    assertFalse(hook.mayUseMail(player));
    assertFalse(hook.isAvailable());
    config.set("mail.require-combatlogx", false);
    assertTrue(hook.mayUseMail(player));
    Plugin disabled = mock(Plugin.class);
    when(manager.getPlugin("CombatLogX")).thenReturn(disabled);
    assertTrue(hook.mayUseMail(player));
    config.set("mail.require-combatlogx", true);
    assertFalse(hook.mayUseMail(player));
  }

  @Test
  void supportedApiAllowsSafePlayersAndBlocksTaggedPlayers() {
    CombatPlugin dependency = mock(CombatPlugin.class);
    CombatManager combat = mock(CombatManager.class);
    when(manager.getPlugin("CombatLogX")).thenReturn(dependency);
    when(dependency.isEnabled()).thenReturn(true);
    when(dependency.getCombatManager()).thenReturn(combat);
    assertTrue(hook.isAvailable());
    assertTrue(hook.mayUseMail(player));
    when(combat.isInCombat(player)).thenReturn(true);
    assertFalse(hook.mayUseMail(player));
    config.set("mail.require-combatlogx", false);
    assertFalse(hook.mayUseMail(player));
  }

  @Test
  void brokenApiFailsClosedEvenWhenOptional() {
    Plugin dependency = mock(Plugin.class);
    when(manager.getPlugin("CombatLogX")).thenReturn(dependency);
    when(dependency.isEnabled()).thenReturn(true);
    config.set("mail.require-combatlogx", false);
    assertFalse(hook.mayUseMail(player));
  }

  @Test
  void invocationFailureAndNullManagerFailClosed() {
    CombatPlugin dependency = mock(CombatPlugin.class);
    when(manager.getPlugin("CombatLogX")).thenReturn(dependency);
    when(dependency.isEnabled()).thenReturn(true);
    assertFalse(hook.mayUseMail(player));
    when(dependency.getCombatManager()).thenThrow(new IllegalStateException("broken"));
    assertFalse(hook.mayUseMail(player));
  }

  @Test
  void publishedCombatLogXApiMatchesTheHook() {
    Plugin dependency =
        mock(
            Plugin.class,
            withSettings().extraInterfaces(com.github.sirblobman.combatlogx.api.ICombatLogX.class));
    var api = (com.github.sirblobman.combatlogx.api.ICombatLogX) dependency;
    var combat = mock(com.github.sirblobman.combatlogx.api.manager.ICombatManager.class);
    when(manager.getPlugin("CombatLogX")).thenReturn(dependency);
    when(dependency.isEnabled()).thenReturn(true);
    when(api.getCombatManager()).thenReturn(combat);
    assertTrue(hook.mayUseMail(player));
    when(combat.isInCombat(player)).thenReturn(true);
    assertFalse(hook.mayUseMail(player));
  }

  @Test
  void configurationBoundsRejectUnsafeValues() {
    ConfigValidation.validate(config);
    config.set("mail.raw-gold-per-item", -1);
    assertThrows(IllegalArgumentException.class, () -> ConfigValidation.validate(config));
    config.set("mail.raw-gold-per-item", 1);
    config.set("mail.return-after-hours", "oops");
    assertThrows(IllegalArgumentException.class, () -> ConfigValidation.validate(config));
  }

  @Test
  void shippedConfigurationParsesAndNewSafetyBooleansAreTyped() throws Exception {
    try (var stream = getClass().getResourceAsStream("/config.yml")) {
      assertNotNull(stream);
      YamlConfiguration shipped =
          YamlConfiguration.loadConfiguration(
              new InputStreamReader(stream, StandardCharsets.UTF_8));
      assertDoesNotThrow(() -> ConfigValidation.validate(shipped));
      assertEquals(
          "minecraft:block.note_block.pling",
          shipped.getString("sounds.package-send.sound"));
    }
    config.set("notifications.join-mail.enabled", "yes");
    assertThrows(IllegalArgumentException.class, () -> ConfigValidation.validate(config));
  }
}
