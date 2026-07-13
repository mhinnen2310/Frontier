package nl.frontier.bootstrap;

import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Server;
import org.bukkit.entity.Player;

final class PaperPresentationService {
  private final Server server;

  PaperPresentationService(Server server) {
    this.server = server;
  }

  void repair(Location location) {
    location.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, location, 5, 0.25, 0.25, 0.25, 0);
    location.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, location, 8, 0.3, 0.3, 0.3, 0.02);
    location.getWorld().playSound(location, "minecraft:block.anvil.use", 0.35f, 1.45f);
  }

  void campaignTransition(int activated, int resolving) {
    if (activated > 0) {
      server.broadcast(
          Component.text(activated + " Frontier campaign(s) are now active.", NamedTextColor.RED));
      server
          .getOnlinePlayers()
          .forEach(player -> play(player, "minecraft:event.raid.horn", 0.7f, 1f));
    }
    if (resolving > 0)
      server.broadcast(
          Component.text(
              resolving + " campaign(s) entered resolution. Open the War report.",
              NamedTextColor.GOLD));
  }

  void settlementFounded(Player founder, String name) {
    server.broadcast(
        Component.text(founder.getName(), NamedTextColor.AQUA)
            .append(Component.text(" founded ", NamedTextColor.GRAY))
            .append(Component.text(name, NamedTextColor.GOLD))
            .append(Component.text("!", NamedTextColor.GRAY)));
    founder.showTitle(
        Title.title(
            Component.text(name, NamedTextColor.GOLD),
            Component.text("A new frontier begins", NamedTextColor.GREEN),
            Title.Times.times(
                Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(800))));
    play(founder, "minecraft:ui.toast.challenge_complete", 0.9f, 1f);
  }

  void harborWelcome(Player player) {
    player.showTitle(
        Title.title(
            Component.text("Frontier Harbor", NamedTextColor.GOLD),
            Component.text("Contracts, trade and a path to settlement", NamedTextColor.AQUA),
            Title.Times.times(
                Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))));
    play(player, "minecraft:music.overworld.day", 0.25f, 1f);
    play(player, "minecraft:block.bell.use", 0.6f, 1.2f);
  }

  private static void play(Player player, String sound, float volume, float pitch) {
    player.playSound(player.getLocation(), sound, volume, pitch);
  }
}
