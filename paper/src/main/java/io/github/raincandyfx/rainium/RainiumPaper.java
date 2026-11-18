package io.github.raincandyfx.rainium;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Rainium on a Paper (or Spigot) server: the {@link ServerCore} wired to plugin messages, events and a
 * command. Permissions are Bukkit's, so LuckPerms (or any permissions plugin) decides them; a node no
 * plugin has set falls back to Rainium's defaults (uploads on, admin for operators, cosmetics off).
 */
public final class RainiumPaper extends JavaPlugin implements Listener, PluginMessageListener, ServerCore.Platform {
    private ServerCore core;

    @Override
    public void onEnable() {
        core = new ServerCore(this);
        core.load();
        getServer().getMessenger().registerOutgoingPluginChannel(this, NetProtocol.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, NetProtocol.CHANNEL, this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, () -> core.tick(), 1L, 1L);
        PluginCommand cmd = getCommand("rainiumserver");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        for (Player p : getServer().getOnlinePlayers()) joined(p);   // after /reload
        getLogger().info("Players with Rainium now see each other's cosmetics.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    private void joined(Player p) {
        core.playerJoined(p.getUniqueId());
        if (p.getListeningPluginChannels().contains(NetProtocol.CHANNEL)) core.rainiumJoined(p.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // a tick later, once the client has said which channels it listens on
        getServer().getScheduler().runTask(this, () -> { if (p.isOnline()) joined(p); });
    }

    @EventHandler
    public void onChannel(PlayerRegisterChannelEvent e) {
        if (!e.getChannel().equals(NetProtocol.CHANNEL)) return;
        Player p = e.getPlayer();
        getServer().getScheduler().runTask(this, () -> { if (p.isOnline()) joined(p); });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { core.playerLeft(e.getPlayer().getUniqueId()); }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (NetProtocol.CHANNEL.equals(channel)) core.received(player.getUniqueId(), message);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        UUID who = sender instanceof Player p ? p.getUniqueId() : null;
        for (String line : core.command(who, args)) sender.sendMessage(line);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        UUID who = sender instanceof Player p ? p.getUniqueId() : null;
        return core.isAdmin(who) ? core.complete(args) : List.of();
    }

    // ------------------------------------------------------------------ ServerCore.Platform

    @Override public void send(UUID u, byte[] msg) {
        Player p = getServer().getPlayer(u);
        if (p != null && p.getListeningPluginChannels().contains(NetProtocol.CHANNEL)) p.sendPluginMessage(this, NetProtocol.CHANNEL, msg);
    }

    @Override public Boolean permission(UUID u, String node) {
        Player p = getServer().getPlayer(u);
        if (p == null) return null;
        // only what a permissions plugin (or the operator default of rainium.admin) actually decided
        return p.isPermissionSet(node) ? p.hasPermission(node) : null;
    }

    @Override public boolean isOp(UUID u) {
        Player p = getServer().getPlayer(u);
        return p != null && p.isOp();
    }

    @Override public void tell(UUID u, String text) {
        Player p = getServer().getPlayer(u);
        if (p != null) p.sendMessage(text);
    }

    @Override public String name(UUID u) {
        Player p = getServer().getPlayer(u);
        return p == null ? null : p.getName();
    }

    @Override public UUID find(String name) {
        Player p = getServer().getPlayerExact(name);
        return p == null ? null : p.getUniqueId();
    }

    @Override public Collection<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out;
    }

    @Override public Path dataDir() { return getDataFolder().toPath(); }
    @Override public void info(String msg) { getLogger().info(msg.replace("[rainium] ", "")); }
    @Override public void warn(String msg) { getLogger().warning(msg.replace("[rainium] ", "")); }
    @Override public String serverName() { return "Paper"; }
}
