package io.github.raincandyfx.rainium;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The client's side of a server that runs Rainium (Fabric mod or Paper plugin). Once the server says
 * hello, this sends what you wear on yourself (see {@link Assets#outfitBlob}) when the server asks for
 * it, and fetches what everyone else wears, so {@link MeshRenderLayer} can draw each player's own
 * cosmetics on them. On any other server nothing here does anything.
 */
final class ServerLink {
    private ServerLink() {}

    private static boolean active, told;
    private static byte mode = NetProtocol.MODE_OWN;
    private static boolean canUpload;
    private static int maxOutfit;
    private static final Map<UUID, List<NetProtocol.Worn>> wearing = new HashMap<>();
    private static final ArrayDeque<byte[]> outbox = new ArrayDeque<>();
    private static final NetProtocol.Assembler downloads = new NetProtocol.Assembler(NetProtocol.HARD_MAX_BLOB, 8);
    private static final Map<String, Long> asked = new HashMap<>();
    private static String myHash = "";
    private static byte[] myBlob;
    private static Object lastState;
    private static long dirtySince;
    private static boolean building;
    private static int ticks;

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(RainiumPayload.TYPE, (payload, ctx) -> received(payload.data()));
        ClientPlayConnectionEvents.JOIN.register((listener, sender, mc) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((listener, mc) -> reset());
    }

    /** Whether this server runs Rainium (then others are drawn with what they wear, and only that). */
    static boolean active() { return active; }

    /** What a player wears according to the server (empty if nothing). */
    static List<NetProtocol.Worn> wornBy(UUID player) { return wearing.getOrDefault(player, List.of()); }

    /** Whether anyone on the server wears this outfit. */
    static boolean wanted(String hash) {
        if (!active) return false;
        for (List<NetProtocol.Worn> l : wearing.values()) for (NetProtocol.Worn w : l) if (w.hash().equals(hash)) return true;
        return false;
    }

    private static void reset() {
        active = false;
        told = false;
        wearing.clear();
        outbox.clear();
        downloads.clear();
        asked.clear();
        myHash = "";
        myBlob = null;
        lastState = null;
        RemoteOutfits.clear();
    }

    // ------------------------------------------------------------------ from the server

    private static void received(byte[] msg) {
        try {
            switch (NetProtocol.type(msg)) {
                case NetProtocol.S_WELCOME -> {
                    NetProtocol.Welcome w = NetProtocol.readWelcome(msg);
                    if (w.version() != NetProtocol.VERSION) {
                        say(Component.translatable("rainium.server.other_version"));
                        return;
                    }
                    active = true;
                    mode = w.mode();
                    canUpload = w.canUpload();
                    maxOutfit = w.maxOutfitBytes();
                    send(NetProtocol.hello(new NetProtocol.Hello(NetProtocol.VERSION, version())));
                    lastState = null;   // build and announce my outfit
                    dirtySince = 0;
                    if (!told) {
                        told = true;
                        say(Component.translatable(mode == NetProtocol.MODE_OWN ? "rainium.server.shared_own" : "rainium.server.shared_server"));
                    }
                }
                case NetProtocol.S_WEARING -> {
                    NetProtocol.Wearing w = NetProtocol.readWearing(msg);
                    if (w.worn().isEmpty()) wearing.remove(w.player());
                    else wearing.put(w.player(), w.worn());
                    for (NetProtocol.Worn x : w.worn()) want(x.hash());
                }
                case NetProtocol.S_PART -> {
                    NetProtocol.Part p = NetProtocol.readPart(msg);
                    byte[] whole = downloads.add(p, System.currentTimeMillis());
                    if (whole != null) { asked.remove(p.hash()); RemoteOutfits.store(p.hash(), whole); }
                }
                case NetProtocol.S_NEED -> {
                    String h = NetProtocol.readHash(msg);
                    if (h.equals(myHash) && myBlob != null) outbox.addAll(NetProtocol.split(NetProtocol.C_PART, h, myBlob));
                }
                case NetProtocol.S_NOTICE -> say(Component.literal(NetProtocol.readNotice(msg).text()));
                default -> { }
            }
        } catch (Exception e) {
            // a message we don't understand is skipped
        }
    }

    /** Asks for an outfit unless it's here or on its way. */
    private static void want(String hash) {
        if (RemoteOutfits.have(hash) || downloads.receiving(hash)) return;
        Long t = asked.get(hash);
        if (t != null && System.currentTimeMillis() - t < 30_000) return;
        asked.put(hash, System.currentTimeMillis());
        send(NetProtocol.fetch(hash));
    }

    // ------------------------------------------------------------------ every tick

    static void tick(Minecraft mc) {
        if (!active || mc.player == null) return;
        ticks++;
        // my own outfit: rebuilt a second after the layers change, announced when it differs
        Object st = RainiumClient.ASSETS.current();
        if (st != lastState) { lastState = st; dirtySince = System.currentTimeMillis(); }
        if (dirtySince != 0 && !building && System.currentTimeMillis() - dirtySince > 1000) {
            dirtySince = 0;
            if (mode == NetProtocol.MODE_OWN && canUpload) {
                building = true;
                String name = mc.player.getName().getString();
                CompletableFuture.supplyAsync(() -> {
                    try { return RainiumClient.ASSETS.outfitBlob(name); } catch (Exception e) { return null; }
                }).thenAccept(blob -> mc.execute(() -> {
                    building = false;
                    if (!active) return;
                    String h = blob == null ? "" : NetProtocol.hash(blob);
                    if (h.equals(myHash)) return;
                    myHash = h;
                    myBlob = blob;
                    if (blob != null && blob.length > maxOutfit) Assets.LOG.info("[rainium] your outfit is " + (blob.length >> 10) + " KB, over this server's limit");
                    send(NetProtocol.outfit(new NetProtocol.Outfit(h, blob == null ? 0 : blob.length)));
                }));
            }
        }
        // send what's waiting, a few parts a tick
        for (int i = 0; i < 8 && !outbox.isEmpty(); i++) {
            if (!send(outbox.peek())) break;
            outbox.poll();
        }
        if (ticks % 100 == 0) {
            downloads.expire(System.currentTimeMillis(), 30_000);
            for (List<NetProtocol.Worn> l : wearing.values()) for (NetProtocol.Worn w : l) want(w.hash());
            RemoteOutfits.tidy();
        }
    }

    private static boolean send(byte[] msg) {
        try {
            ClientPlayNetworking.send(new RainiumPayload(msg));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void say(Component text) {
        Minecraft mc = Minecraft.getInstance();
        Component c = Component.literal("[Rainium] ").append(text);
        if (mc.player != null) mc.player.sendSystemMessage(c);
    }

    private static String version() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("rainium")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }
}
