package io.github.raincandyfx.rainium;

import java.nio.file.*;
import java.util.*;

/**
 * Offline checks for the multiplayer protocol and server logic (no game needed). From the repo root:
 *   javac -d /tmp/nt src/main/java/io/github/raincandyfx/rainium/{NetProtocol,ServerCore,Json,JsonWriter}.java tools/nettest/NetTest.java
 *   java -cp /tmp/nt io.github.raincandyfx.rainium.NetTest
 */
public class NetTest {
    static int fails = 0;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL " + what); } }

    static final class Fake implements ServerCore.Platform {
        final Path dir; final Map<UUID, List<byte[]>> sent = new HashMap<>(); final Map<String, Boolean> perms = new HashMap<>();
        final Map<UUID, String> names = new HashMap<>(); final Set<UUID> ops = new HashSet<>();
        Fake(Path d) { dir = d; }
        public void send(UUID p, byte[] m) { sent.computeIfAbsent(p, k -> new ArrayList<>()).add(m); }
        public Boolean permission(UUID p, String node) { return perms.get(p + " " + node); }
        public boolean isOp(UUID p) { return ops.contains(p); }
        public String name(UUID p) { return names.get(p); }
        public UUID find(String n) { for (var e : names.entrySet()) if (e.getValue().equalsIgnoreCase(n)) return e.getKey(); return null; }
        public Collection<String> onlineNames() { return names.values(); }
        public Path dataDir() { return dir; }
        public void info(String m) { } public void warn(String m) { System.out.println("warn " + m); }
        public String serverName() { return "test"; }
        final Map<UUID, String> worlds = new HashMap<>();
        public String world(UUID p) { return worlds.getOrDefault(p, "minecraft:overworld"); }
        List<byte[]> take(UUID u) { List<byte[]> l = sent.getOrDefault(u, new ArrayList<>()); sent.put(u, new ArrayList<>()); return l; }
    }

    static byte[] zip(int size, long seed) throws Exception {
        var bo = new java.io.ByteArrayOutputStream();
        try (var z = new java.util.zip.ZipOutputStream(bo)) {
            z.putNextEntry(new java.util.zip.ZipEntry("outfit.json"));
            z.write("{\"format\":\"rainium-outfit-1\",\"config\":{\"layers\":[{\"label\":\"x\"}]}}".getBytes()); z.closeEntry();
            z.putNextEntry(new java.util.zip.ZipEntry("pad.bin"));
            byte[] b = new byte[size]; new Random(seed).nextBytes(b); z.write(b); z.closeEntry();
        }
        return bo.toByteArray();
    }

    public static void main(String[] a) throws Exception {
        // split + assemble round trip, in a shuffled order, with a duplicate
        byte[] blob = zip(100_000, 1);
        String h = NetProtocol.hash(blob);
        List<byte[]> parts = NetProtocol.split(NetProtocol.C_PART, h, blob);
        check(parts.size() == (blob.length + NetProtocol.PART - 1) / NetProtocol.PART, "parts count");
        for (byte[] p : parts) check(p.length < 32767, "every part fits a client message");
        Collections.shuffle(parts, new Random(3));
        parts.add(parts.get(0));
        var as = new NetProtocol.Assembler(1 << 20, 2);
        byte[] got = null; int done = 0;
        for (byte[] p : parts) { byte[] r = as.add(NetProtocol.readPart(p), 0); if (r != null) { got = r; done++; } }
        check(done == 1 && Arrays.equals(got, blob), "blob reassembled once");
        // a wrong hash is refused
        List<byte[]> bad = NetProtocol.split(NetProtocol.C_PART, NetProtocol.hash(new byte[]{1}), blob);
        byte[] r2 = null; for (byte[] p : bad) { byte[] r = new NetProtocol.Assembler(1 << 20, 2).add(NetProtocol.readPart(p), 0); if (r != null) r2 = r; }
        var as2 = new NetProtocol.Assembler(1 << 20, 2); for (byte[] p : bad) { byte[] r = as2.add(NetProtocol.readPart(p), 0); if (r != null) r2 = r; }
        check(r2 == null, "hash mismatch refused");
        // over the size limit is refused
        var small = new NetProtocol.Assembler(1000, 2); byte[] r3 = null;
        for (byte[] p : NetProtocol.split(NetProtocol.C_PART, h, blob)) { byte[] r = small.add(NetProtocol.readPart(p), 0); if (r != null) r3 = r; }
        check(r3 == null, "too big refused");
        // messages round trip
        var w = new NetProtocol.Wearing(UUID.randomUUID(), List.of(new NetProtocol.Worn(NetProtocol.KIND_SERVER, h, 5, "halo")));
        check(NetProtocol.readWearing(NetProtocol.wearing(w)).equals(w), "wearing round trip");
        var wl = new NetProtocol.Welcome(NetProtocol.VERSION, NetProtocol.MODE_OWN, true, 2048 << 10, "srv", false, true);
        check(NetProtocol.readWelcome(NetProtocol.welcome(wl)).equals(wl), "welcome round trip");

        // ---- server core
        Path dir = Files.createTempDirectory("rsv");
        Fake f = new Fake(dir);
        Files.createDirectories(dir.resolve("cosmetics"));
        byte[] halo = zip(5000, 7);
        Files.write(dir.resolve("cosmetics/Gold Halo.zip"), halo);
        ServerCore core = new ServerCore(f);
        core.load();
        check(core.cosmetics().size() == 1 && core.cosmetics().iterator().next().id().equals("gold_halo"), "cosmetic id from file name");
        check(Files.exists(dir.resolve("config.json")), "config written");
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), carl = UUID.randomUUID();
        f.names.put(alice, "Alice"); f.names.put(bob, "Bob"); f.names.put(carl, "Carl");
        core.playerJoined(alice); core.rainiumJoined(alice);
        core.playerJoined(bob); core.rainiumJoined(bob);
        core.playerJoined(carl);   // no Rainium
        core.tick();
        List<byte[]> am = f.take(alice);
        check(!am.isEmpty() && NetProtocol.type(am.get(0)) == NetProtocol.S_WELCOME, "welcome first");
        check(f.take(carl).isEmpty(), "nothing sent to a player without Rainium");
        // alice uploads her outfit
        byte[] outfit = zip(70_000, 9);
        String oh = NetProtocol.hash(outfit);
        core.received(alice, NetProtocol.outfit(new NetProtocol.Outfit(oh, outfit.length)));
        core.tick();
        am = f.take(alice);
        check(am.stream().anyMatch(m -> NetProtocol.type(m) == NetProtocol.S_NEED), "server asks for the upload");
        for (byte[] p : NetProtocol.split(NetProtocol.C_PART, oh, outfit)) core.received(alice, p);
        core.tick();
        check(Files.exists(dir.resolve("uploads/" + oh + ".zip")), "upload stored");
        List<byte[]> bm = f.take(bob);
        NetProtocol.Wearing seen = null;
        for (byte[] m : bm) if (NetProtocol.type(m) == NetProtocol.S_WEARING) { var x = NetProtocol.readWearing(m); if (x.player().equals(alice)) seen = x; }
        check(seen != null && seen.worn().size() == 1 && seen.worn().get(0).hash().equals(oh) && seen.worn().get(0).kind() == NetProtocol.KIND_OWN, "bob is told what alice wears");
        // bob fetches it
        core.received(bob, NetProtocol.fetch(oh));
        for (int i = 0; i < 5; i++) core.tick();
        var bas = new NetProtocol.Assembler(1 << 20, 4); byte[] bgot = null;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_PART) { byte[] r = bas.add(NetProtocol.readPart(m), 0); if (r != null) bgot = r; }
        check(Arrays.equals(bgot, outfit), "bob downloads alice's outfit");
        // fetching something nobody wears is refused
        core.received(bob, NetProtocol.fetch(NetProtocol.hash(new byte[]{9})));
        core.tick();
        check(f.take(bob).isEmpty(), "unworn blobs can't be fetched");
        // give carl (no mod) the halo: bob sees it
        List<String> out = core.command(null, new String[]{"give", "Carl", "gold_halo"});
        check(out.get(0).startsWith("Gave"), "give: " + out);
        core.tick();
        boolean carlHalo = false;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_WEARING) { var x = NetProtocol.readWearing(m); if (x.player().equals(carl) && x.worn().size() == 1 && x.worn().get(0).name().equals("gold_halo")) carlHalo = true; }
        check(carlHalo, "server cosmetic on a player without the mod is shown to others");
        check(Files.readString(dir.resolve("grants.json")).contains(carl.toString()), "grant saved");
        // a non-admin can't run commands; LuckPerms admin can
        check(core.command(bob, new String[]{"list"}).get(0).contains("permission"), "non-admin refused");
        f.perms.put(bob + " rainium.admin", true);
        check(core.command(bob, new String[]{"list"}).get(0).contains("installed"), "admin by permission");
        // permission-granted cosmetic
        f.perms.put(alice + " rainium.cosmetic.gold_halo", true);
        for (int i = 0; i < 200; i++) core.tick();
        boolean aliceTwo = false;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_WEARING) { var x = NetProtocol.readWearing(m); if (x.player().equals(alice) && x.worn().size() == 2) aliceTwo = true; }
        check(aliceTwo, "permission node grants a cosmetic (picked up by the periodic check)");
        // upload permission removed -> own outfit no longer shown
        f.take(alice);
        f.perms.put(alice + " rainium.upload", false);
        for (int i = 0; i < 200; i++) core.tick();
        boolean ownGone = false;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_WEARING) { var x = NetProtocol.readWearing(m); if (x.player().equals(alice) && x.worn().size() == 1 && x.worn().get(0).kind() == NetProtocol.KIND_SERVER) ownGone = true; }
        check(ownGone, "without rainium.upload the own outfit stops showing");
        boolean rewelcomed = false;
        for (byte[] m : f.take(alice)) if (NetProtocol.type(m) == NetProtocol.S_WELCOME && !NetProtocol.readWelcome(m).canUpload()) rewelcomed = true;
        check(rewelcomed, "a player whose upload right changed is told again");
        // server-only mode
        f.perms.remove(alice + " rainium.upload");
        core.command(null, new String[]{"mode", "server"});
        core.tick();
        boolean onlyServer = true;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_WEARING) { var x = NetProtocol.readWearing(m); for (var wn : x.worn()) if (wn.kind() == NetProtocol.KIND_OWN) onlyServer = false; }
        check(onlyServer && Files.readString(dir.resolve("config.json")).contains("\"server\""), "server mode hides own outfits and is saved");
        // install from a player
        core.command(null, new String[]{"mode", "own"});
        core.tick();
        out = core.command(null, new String[]{"install", "alices_wings", "Alice"});
        check(out.get(0).startsWith("Installed") && Files.exists(dir.resolve("cosmetics/alices_wings.zip")), "install from a player: " + out);
        // locking a player's own cosmetics: they stop showing, and the client is told
        f.take(alice); f.take(bob);
        out = core.command(null, new String[]{"lock", "Alice"});
        core.tick();
        boolean lockedGone = false, lockedTold = false;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_WEARING) { var x = NetProtocol.readWearing(m); if (x.player().equals(alice) && x.worn().stream().noneMatch(wn -> wn.kind() == NetProtocol.KIND_OWN)) lockedGone = true; }
        for (byte[] m : f.take(alice)) if (NetProtocol.type(m) == NetProtocol.S_WELCOME) { var x = NetProtocol.readWelcome(m); if (!x.ownAllowed() && !x.canUpload()) lockedTold = true; }
        check(out.get(0).startsWith("Turned off") && lockedGone && lockedTold, "lock: own cosmetics off and the player is told");
        check(Files.readString(dir.resolve("grants.json")).contains("\"locked\""), "lock saved");
        core.command(null, new String[]{"unlock", "Alice"});
        core.tick();
        boolean unlockedTold = false;
        for (byte[] m : f.take(alice)) if (NetProtocol.type(m) == NetProtocol.S_WELCOME && NetProtocol.readWelcome(m).ownAllowed()) unlockedTold = true;
        check(unlockedTold, "unlock tells the player again");
        // the server-wide switch
        core.command(null, new String[]{"set", "client_cosmetics", "false"});
        core.tick();
        boolean allOff = true;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_WELCOME && NetProtocol.readWelcome(m).ownAllowed()) allOff = false;
        check(allOff && !core.ownAllowed(bob) && Files.readString(dir.resolve("config.json")).contains("\"client_cosmetics\": false"), "client_cosmetics off for everyone, saved");
        core.command(null, new String[]{"set", "client_cosmetics", "true"});
        check(core.command(null, new String[]{"set", "max_outfit_kb", "999999999"}).get(0).contains("32768"), "numbers are clamped");
        // the admin panel: only for admins, runs commands and answers with the state
        f.take(alice); f.take(bob);
        core.received(alice, NetProtocol.adminOpen());
        core.tick();
        check(f.take(alice).stream().noneMatch(m -> NetProtocol.type(m) == NetProtocol.S_ADMIN), "no admin panel for a non-admin");
        core.received(bob, NetProtocol.adminRun(List.of("give", "Alice", "gold_halo")));
        core.tick();
        String st = null;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_ADMIN) st = NetProtocol.readAdmin(m);
        check(st != null && st.contains("Gave gold_halo to Alice") && st.contains("\"cosmetics\"") && st.contains("\"Alice\""), "admin action answered with the new state");
        Object parsed = Json.parse(st);
        check(parsed instanceof Map<?, ?> pm && pm.get("players") instanceof List<?> pl && pl.size() == 3, "admin state lists the players");
        // cosmetics for everyone, with a display name
        f.take(bob);
        out = core.command(null, new String[]{"everyone", "gold_halo", "on"});
        core.command(null, new String[]{"rename", "gold_halo", "Golden", "Halo"});
        core.tick();
        boolean bobHalo = false;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_WEARING) { var x = NetProtocol.readWearing(m); if (x.player().equals(bob) && x.worn().stream().anyMatch(wn -> wn.name().equals("Golden Halo"))) bobHalo = true; }
        check(out.get(0).startsWith("Everyone") && bobHalo && Files.readString(dir.resolve("cosmetics.json")).contains("Golden Halo"), "everyone wears it, under its display name");
        core.command(null, new String[]{"everyone", "gold_halo", "off"});
        // giveall / takeall
        out = core.command(null, new String[]{"giveall", "alices_wings"});
        check(out.get(0).contains("player(s) online") && Files.readString(dir.resolve("grants.json")).contains("alices_wings"), "giveall: " + out);
        core.command(null, new String[]{"takeall", "alices_wings"});
        // a world with cosmetics off: nothing shows on players there, and they're told their own are off
        f.take(alice); f.take(bob);
        core.command(null, new String[]{"set", "worlds_off", "the_nether,", "arena"});
        f.worlds.put(alice, "minecraft:the_nether");
        for (int i = 0; i < 20; i++) core.tick();
        boolean aliceBare = false, aliceTold = false;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_WEARING) { var x = NetProtocol.readWearing(m); if (x.player().equals(alice) && x.worn().isEmpty()) aliceBare = true; }
        for (byte[] m : f.take(alice)) if (NetProtocol.type(m) == NetProtocol.S_WELCOME && !NetProtocol.readWelcome(m).ownAllowed()) aliceTold = true;
        check(aliceBare && aliceTold, "cosmetics off in a listed world (namespace optional)");
        check(ServerCore.sameWorld("minecraft:the_nether", "the_nether") && ServerCore.sameWorld("world_nether", "WORLD_NETHER") && !ServerCore.sameWorld("minecraft:overworld", "the_nether"), "world names match with or without the namespace");
        f.worlds.put(alice, "minecraft:overworld");
        for (int i = 0; i < 20; i++) core.tick();
        core.command(null, new String[]{"set", "worlds_off", "-"});
        check(core.ownAllowed(alice), "back in a normal world, own cosmetics are allowed again");
        // upload rules: too many layers, particles
        Path up = dir.resolve("uploads/" + oh + ".zip");
        var bo2 = new java.io.ByteArrayOutputStream();
        try (var z = new java.util.zip.ZipOutputStream(bo2)) {
            z.putNextEntry(new java.util.zip.ZipEntry("outfit.json"));
            z.write("{\"format\":\"rainium-outfit-1\",\"config\":{\"layers\":[{\"label\":\"a\"},{\"label\":\"b\",\"particles\":{}},{\"label\":\"c\"}]}}".getBytes());
            z.closeEntry();
        }
        byte[] three = bo2.toByteArray();
        String th = NetProtocol.hash(three);
        core.received(alice, NetProtocol.outfit(new NetProtocol.Outfit(th, three.length)));
        for (byte[] p2 : NetProtocol.split(NetProtocol.C_PART, th, three)) core.received(alice, p2);
        core.tick();
        check(core.shape(th) != null && core.shape(th).layers() == 3 && core.shape(th).particles(), "an outfit's layers and particles are read");
        f.take(alice); f.take(bob);
        core.command(null, new String[]{"set", "own_particles", "false"});
        core.tick();
        boolean noOwn = false, ruleTold = false;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_WEARING) { var x = NetProtocol.readWearing(m); if (x.player().equals(alice) && x.worn().stream().noneMatch(wn -> wn.kind() == NetProtocol.KIND_OWN)) noOwn = true; }
        for (byte[] m : f.take(alice)) if (NetProtocol.type(m) == NetProtocol.S_NOTICE && NetProtocol.readNotice(m).text().contains("particle")) ruleTold = true;
        check(noOwn && ruleTold, "own outfit with particles isn't shared when particles are off, and the player is told");
        core.command(null, new String[]{"set", "own_particles", "true"});
        core.command(null, new String[]{"set", "max_layers", "2"});
        core.tick();
        boolean noOwn2 = false;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_WEARING) { var x = NetProtocol.readWearing(m); if (x.player().equals(alice) && x.worn().stream().noneMatch(wn -> wn.kind() == NetProtocol.KIND_OWN)) noOwn2 = true; }
        check(noOwn2, "more layers than max_layers isn't shared");
        core.command(null, new String[]{"set", "max_layers", "64"});
        core.tick();
        boolean back = false;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_WEARING) { var x = NetProtocol.readWearing(m); if (x.player().equals(alice) && x.worn().stream().anyMatch(wn -> wn.kind() == NetProtocol.KIND_OWN)) back = true; }
        check(back, "within the rules it's shared again");
        // join message, log
        core.command(null, new String[]{"set", "join_message", "Welcome", "to", "the", "server!"});
        UUID dana = UUID.randomUUID(); f.names.put(dana, "Dana");
        core.playerJoined(dana); core.rainiumJoined(dana); core.tick();
        check(f.take(dana).stream().anyMatch(m -> { try { return NetProtocol.type(m) == NetProtocol.S_NOTICE && NetProtocol.readNotice(m).text().equals("Welcome to the server!"); } catch (Exception e) { return false; } }), "join message");
        List<String> log = core.command(null, new String[]{"log"});
        check(log.size() > 3 && log.get(log.size() - 1).contains("join_message") && Files.readString(dir.resolve("actions.log")).contains("everyone gold_halo on"), "actions are logged");
        String st2 = core.adminState(List.of());
        check(st2.contains("\"world\"") && st2.contains("\"everyone\"") && st2.contains("\"log\"") && st2.contains("\"layers\": 3"), "admin state has the new facts");
        // leaving clears
        core.playerLeft(alice); core.tick();
        boolean cleared = false;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_WEARING) { var x = NetProtocol.readWearing(m); if (x.player().equals(alice) && x.worn().isEmpty()) cleared = true; }
        check(cleared, "a player leaving is cleared for others");
        check(core.complete(new String[]{"gi"}).equals(List.of("give", "giveall")) && core.complete(new String[]{"give", "Carl", "go"}).equals(List.of("gold_halo")), "completion");
        // garbage doesn't throw
        core.received(bob, new byte[]{3, 1, 2}); core.received(bob, new byte[0]); core.received(bob, new byte[]{99});
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
