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
        final Map<UUID, String> names = new HashMap<>(); final Set<UUID> ops = new HashSet<>(); final List<String> told = new ArrayList<>();
        Fake(Path d) { dir = d; }
        public void send(UUID p, byte[] m) { sent.computeIfAbsent(p, k -> new ArrayList<>()).add(m); }
        public Boolean permission(UUID p, String node) { return perms.get(p + " " + node); }
        public boolean isOp(UUID p) { return ops.contains(p); }
        public void tell(UUID p, String t) { told.add(t); }
        public String name(UUID p) { return names.get(p); }
        public UUID find(String n) { for (var e : names.entrySet()) if (e.getValue().equalsIgnoreCase(n)) return e.getKey(); return null; }
        public Collection<String> onlineNames() { return names.values(); }
        public Path dataDir() { return dir; }
        public void info(String m) { } public void warn(String m) { System.out.println("warn " + m); }
        public String serverName() { return "test"; }
        List<byte[]> take(UUID u) { List<byte[]> l = sent.getOrDefault(u, new ArrayList<>()); sent.put(u, new ArrayList<>()); return l; }
    }

    static byte[] zip(int size, long seed) throws Exception {
        var bo = new java.io.ByteArrayOutputStream();
        try (var z = new java.util.zip.ZipOutputStream(bo)) {
            z.putNextEntry(new java.util.zip.ZipEntry("set.json"));
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
        var wl = new NetProtocol.Welcome(1, NetProtocol.MODE_OWN, true, 2048 << 10, "srv");
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
        f.perms.put(alice + " rainium.upload", false);
        for (int i = 0; i < 200; i++) core.tick();
        boolean ownGone = false;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_WEARING) { var x = NetProtocol.readWearing(m); if (x.player().equals(alice) && x.worn().size() == 1 && x.worn().get(0).kind() == NetProtocol.KIND_SERVER) ownGone = true; }
        check(ownGone, "without rainium.upload the own outfit stops showing");
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
        // leaving clears
        core.playerLeft(alice); core.tick();
        boolean cleared = false;
        for (byte[] m : f.take(bob)) if (NetProtocol.type(m) == NetProtocol.S_WEARING) { var x = NetProtocol.readWearing(m); if (x.player().equals(alice) && x.worn().isEmpty()) cleared = true; }
        check(cleared, "a player leaving is cleared for others");
        check(core.complete(new String[]{"gi"}).equals(List.of("give")) && core.complete(new String[]{"give", "Carl", "go"}).equals(List.of("gold_halo")), "completion");
        // garbage doesn't throw
        core.received(bob, new byte[]{3, 1, 2}); core.received(bob, new byte[0]); core.received(bob, new byte[]{99});
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
