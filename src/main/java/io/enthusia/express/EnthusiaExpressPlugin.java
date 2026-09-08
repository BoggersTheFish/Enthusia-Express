package io.enthusia.express;

import io.enthusia.express.command.MailCommand;
import io.enthusia.express.db.MailRepository;
import io.enthusia.express.gui.GuiListener;
import io.enthusia.express.gui.MailboxService;
import io.enthusia.express.gui.ShippingService;
import io.enthusia.express.hook.CombatLogXHook;
import io.enthusia.express.mail.ExpirationService;
import io.enthusia.express.mail.JoinNotificationService;
import io.enthusia.express.util.SoundFeedback;
import java.io.File;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class EnthusiaExpressPlugin extends JavaPlugin {
  private io.enthusia.express.util.MainThread main;
  private MailRepository repository;
  private CombatLogXHook combatHook;
  private ShippingService shippingService;
  private MailboxService mailboxService;
  private ExpirationService expirationService;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    io.enthusia.express.util.ConfigValidation.validate(getConfig());
    this.main = new io.enthusia.express.util.MainThread(this);
    File dbFile = new File(getDataFolder(), "mail.db");
    this.repository = new MailRepository(this, dbFile);
    repository.initialize().join();
    this.combatHook = new CombatLogXHook(this);
    SoundFeedback sounds = new SoundFeedback(this);
    sounds.validate();
    this.shippingService = new ShippingService(this, repository, combatHook, main, sounds);
    this.mailboxService = new MailboxService(this, repository, combatHook, main, sounds);
    this.expirationService = new ExpirationService(this, repository);
    MailCommand command =
        new MailCommand(
            this,
            shippingService,
            mailboxService,
            combatHook,
            new io.enthusia.express.mail.BookMailService(
                this, repository, combatHook, main, sounds));
    Objects.requireNonNull(getCommand("mail")).setExecutor(command);
    Objects.requireNonNull(getCommand("mail")).setTabCompleter(command);
    Bukkit.getPluginManager()
        .registerEvents(new GuiListener(shippingService, mailboxService), this);
    Bukkit.getPluginManager()
        .registerEvents(new JoinNotificationService(this, repository, main), this);
    expirationService.start();
    getLogger().info("Enthusia Express enabled.");
  }

  @Override
  public void onDisable() {
    if (expirationService != null) expirationService.stop();
    if (shippingService != null) shippingService.shutdown();
    if (mailboxService != null) mailboxService.shutdown();
    if (main != null) main.close();
    if (repository != null) repository.close();
  }
}
