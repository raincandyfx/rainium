package io.github.raincandyfx.rainium;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Rainium on a Fabric dedicated server: the {@link ServerCore} wired to Fabric's networking, events and
 * commands. Permissions come from LuckPerms (through fabric-permissions-api, which LuckPerms ships, or
 * Fabric's own permission API) when installed, otherwise from the defaults and operator status.
 */
final class RainiumServerFabric implements ServerCore.Platform {
    private static final Logger LOG = LoggerFactory.getLogger("rainium");
    private static MinecraftServer server;
    private static ServerCore core;

    private RainiumServerFabric() {}

    static void init() {
        RainiumServerFabric platform = new RainiumServerFabric();
        core = new ServerCore(platform);
        ServerLifecycleEvents.SERVER_STARTED.register(s -> { server = s; core.load(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
        ServerTickEvents.END_SERVER_TICK.register(s -> core.tick());
        ServerPlayNetworking.registerGlobalReceiver(RainiumPayload.TYPE, (payload, ctx) -> core.received(ctx.player().getUUID(), payload.data()));
        ServerPlayConnectionEvents.JOIN.register((listener, sender, s) -> {
            ServerPlayer p = listener.getPlayer();
            core.playerJoined(p.getUUID());
            if (ServerPlayNetworking.canSend(p, RainiumPayload.TYPE)) core.rainiumJoined(p.getUUID());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((listener, s) -> core.playerLeft(listener.getPlayer().getUUID()));
        CommandRegistrationCallback.EVENT.register((dispatcher, ctx, selection) -> dispatcher.register(
            Commands.literal("rainiumserver")
                .requires(RainiumServerFabric::mayManage)
                .executes(c -> run(c, ""))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                    .suggests((c, b) -> {
                        String typed = b.getRemaining();
                        String[] words = typed.split(" ", -1);
                        int start = b.getStart() + typed.lastIndexOf(' ') + 1;
                        var sb = b.createOffset(start);
                        for (String s : core.complete(words)) sb.suggest(s);
                        return sb.buildFuture();
                    })
                    .executes(c -> run(c, StringArgumentType.getString(c, "args"))))));
        LOG.info("[rainium] server side on: players with Rainium see each other's cosmetics");
    }

    private static boolean mayManage(CommandSourceStack src) {
        if (src.getEntity() instanceof ServerPlayer p) return core.isAdmin(p.getUUID());
        return src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);   // the console, command blocks
    }

    private static int run(CommandContext<CommandSourceStack> c, String args) {
        UUID who = c.getSource().getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
        String trimmed = args.trim();
        List<String> lines = core.command(who, trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+"));
        for (String l : lines) c.getSource().sendSystemMessage(Component.literal(l));
        return 1;
    }

    private static ServerPlayer player(UUID u) { return server == null ? null : server.getPlayerList().getPlayer(u); }

    // ------------------------------------------------------------------ ServerCore.Platform

    @Override public void send(UUID u, byte[] msg) {
        ServerPlayer p = player(u);
        if (p != null) ServerPlayNetworking.send(p, new RainiumPayload(msg));
    }

    @Override public Boolean permission(UUID u, String node) {
        ServerPlayer p = player(u);
        if (p == null) return null;
        Boolean b = Perms.lucko(p, node);
        return b != null ? b : Perms.fabric(p, node);
    }

    @Override public boolean isOp(UUID u) {
        ServerPlayer p = player(u);
        return p != null && p.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override public String name(UUID u) {
        ServerPlayer p = player(u);
        return p == null ? null : p.getName().getString();
    }

    @Override public UUID find(String name) {
        if (server == null) return null;
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        return p == null ? null : p.getUUID();
    }

    @Override public Collection<String> onlineNames() {
        List<String> out = new ArrayList<>();
        if (server != null) for (ServerPlayer p : server.getPlayerList().getPlayers()) out.add(p.getName().getString());
        return out;
    }

    @Override public Path dataDir() { return FabricLoader.getInstance().getConfigDir().resolve("rainium-server"); }
    @Override public void info(String msg) { LOG.info(msg); }
    @Override public void warn(String msg) { LOG.warn(msg); }
    @Override public String serverName() { return "Fabric"; }

    @Override public String world(UUID u) {
        ServerPlayer p = player(u);
        if (p == null) return "";
        String d = p.level().dimension().toString();   // "ResourceKey[minecraft:dimension / minecraft:the_nether]"
        int i = d.lastIndexOf(" / ");
        return i >= 0 && d.endsWith("]") ? d.substring(i + 3, d.length() - 1) : d;
    }

    /** Permission checks through whatever permissions mod is installed, found at run time (none is required). */
    static final class Perms {
        private static boolean luckoLooked, fabricLooked;
        private static Method luckoValue, fabricCheck;

        /** fabric-permissions-api (me.lucko), which LuckPerms for Fabric includes: TRUE/FALSE, or null when unset or absent. */
        static Boolean lucko(ServerPlayer p, String node) {
            if (!luckoLooked) {
                luckoLooked = true;
                try {
                    Class<?> c = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
                    for (Method m : c.getMethods()) {
                        Class<?>[] t = m.getParameterTypes();
                        if (m.getName().equals("getPermissionValue") && t.length == 2 && t[1] == String.class && t[0].isAssignableFrom(p.getClass())) { luckoValue = m; break; }
                    }
                } catch (Throwable ignored) { }
            }
            if (luckoValue == null) return null;
            try { return tri(luckoValue.invoke(null, p, node)); } catch (Throwable t) { return null; }
        }

        /** Fabric's own permission API (Minecraft 26.x): node "rainium.x.y" is asked as rainium:x.y. */
        static Boolean fabric(ServerPlayer p, String node) {
            if (!fabricLooked) {
                fabricLooked = true;
                try { fabricCheck = p.getClass().getMethod("checkPermission", Identifier.class); } catch (Throwable ignored) { }
            }
            if (fabricCheck == null) return null;
            try {
                int dot = node.indexOf('.');
                Identifier id = Identifier.fromNamespaceAndPath(node.substring(0, dot), node.substring(dot + 1));
                return tri(fabricCheck.invoke(p, id));
            } catch (Throwable t) {
                return null;
            }
        }

        private static Boolean tri(Object r) {
            if (r == null) return null;
            String s = r instanceof Enum<?> e ? e.name() : String.valueOf(r);
            return s.equals("TRUE") ? Boolean.TRUE : s.equals("FALSE") ? Boolean.FALSE : null;
        }
    }
}
