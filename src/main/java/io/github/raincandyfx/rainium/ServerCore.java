package io.github.raincandyfx.rainium;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The server side of Rainium in multiplayer, the same on Fabric and Paper (each supplies a
 * {@link Platform}). It keeps who wears what and hands it to every player who has Rainium:
 * <ul>
 *   <li>A player's own outfit: the layers they show on themselves, uploaded once and kept by hash
 *       (only in "own" mode, and only with the rainium.upload permission).</li>
 *   <li>Cosmetics the server installed (zips in cosmetics/), worn by a player who was given one with
 *       /rainiumserver give, or who has the permission rainium.cosmetic.&lt;id&gt; (LuckPerms or any
 *       permissions plugin).</li>
 * </ul>
 * Everything runs on the server's main thread.
 */
public final class ServerCore {
    /** What the server software provides. */
    public interface Platform {
        void send(UUID player, byte[] msg);
        /** TRUE or FALSE when a permissions system decided this node for the player, null when none did. */
        Boolean permission(UUID player, String node);
        boolean isOp(UUID player);
        void tell(UUID player, String text);
        String name(UUID player);
        /** An online player by name (or UUID text), or null. */
        UUID find(String nameOrUuid);
        Collection<String> onlineNames();
        Path dataDir();
        void info(String msg);
        void warn(String msg);
        String serverName();
        /** The world a player is in: a dimension id on Fabric (minecraft:the_nether), the world's name on Paper (world_nether). */
        String world(UUID player);
    }

    public static final String NODE_ADMIN = "rainium.admin", NODE_UPLOAD = "rainium.upload", NODE_COSMETIC = "rainium.cosmetic.";
    private static final int MAX_WORN = 16;

    /** The settings in config.json. */
    public static final class Config {
        public boolean ownOutfits = true;       // "mode": "own" (players' own + server cosmetics) or "server" (server cosmetics only)
        public boolean uploadsByDefault = true; // rainium.upload when no permissions plugin says otherwise
        public int maxOutfitKb = 2048;
        public int sendKbPerTick = 128;         // per player
        public int uploadCacheMb = 512;
        public boolean clientCosmetics = true;  // players' own (client-side) cosmetics may show here at all, even to themselves
        public int maxLayers = 64;              // a player's own outfit with more layers isn't shared
        public boolean ownParticles = true;     // players' own outfits may have particle layers
        public List<String> worldsOff = new ArrayList<>();   // no cosmetics at all in these worlds (minigames, arenas...)
        public String joinMessage = "";         // shown to players with Rainium when they join

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", ownOutfits ? "own" : "server");
            m.put("uploads_by_default", uploadsByDefault);
            m.put("max_outfit_kb", maxOutfitKb);
            m.put("send_kb_per_tick", sendKbPerTick);
            m.put("upload_cache_mb", uploadCacheMb);
            m.put("client_cosmetics", clientCosmetics);
            m.put("max_layers", maxLayers);
            m.put("own_particles", ownParticles);
            m.put("cosmetics_off_in_worlds", new ArrayList<Object>(worldsOff));
            m.put("join_message", joinMessage);
            return m;
        }

        static Config fromJson(Object o) {
            Config c = new Config();
            if (!(o instanceof Map<?, ?> m)) return c;
            if (m.get("mode") instanceof String s) c.ownOutfits = !s.trim().equalsIgnoreCase("server");
            if (m.get("uploads_by_default") instanceof Boolean b) c.uploadsByDefault = b;
            if (m.get("max_outfit_kb") instanceof Number n) c.maxOutfitKb = clamp(n.intValue(), 16, NetProtocol.HARD_MAX_BLOB >> 10);
            if (m.get("send_kb_per_tick") instanceof Number n) c.sendKbPerTick = clamp(n.intValue(), 8, 4096);
            if (m.get("upload_cache_mb") instanceof Number n) c.uploadCacheMb = clamp(n.intValue(), 16, 1 << 16);
            if (m.get("client_cosmetics") instanceof Boolean b) c.clientCosmetics = b;
            if (m.get("max_layers") instanceof Number n) c.maxLayers = clamp(n.intValue(), 1, 512);
            if (m.get("own_particles") instanceof Boolean b) c.ownParticles = b;
            if (m.get("cosmetics_off_in_worlds") instanceof List<?> l) for (Object o2 : l) { String w = String.valueOf(o2).trim(); if (!w.isEmpty()) c.worldsOff.add(w); }
            if (m.get("join_message") instanceof String j) c.joinMessage = j.length() > 300 ? j.substring(0, 300) : j;
            return c;
        }
    }

    /** A cosmetic installed on the server. */
    public record Cosmetic(String id, String hash, Path file, int size) {}

    private static final class Session {
        final UUID id;
        boolean rainium;                // has the mod and the channel
        String ownHash = "";
        int ownSize;
        boolean ownReady, waitingUpload, toldNoUpload, toldTooBig, toldRules;
        String world = "";              // where they are, for the worlds with cosmetics off
        String welcomed = "";           // the mode and upload right the client was last told
        final ArrayDeque<byte[]> out = new ArrayDeque<>();
        long queuedBytes;
        final Set<String> queuedBlobs = new HashSet<>();
        final NetProtocol.Assembler upload = new NetProtocol.Assembler(NetProtocol.HARD_MAX_BLOB, 1);
        List<NetProtocol.Worn> worn = List.of();
        Session(UUID id) { this.id = id; }
    }

    private final Platform p;
    private Config config = new Config();
    private final Map<String, Cosmetic> cosmetics = new TreeMap<>();
    private final Map<String, Cosmetic> byHash = new HashMap<>();
    private final Map<UUID, Set<String>> grants = new HashMap<>();
    private final Map<UUID, String> grantNames = new HashMap<>();
    private final Set<UUID> locked = new HashSet<>();   // players whose own cosmetics are turned off here
    private final Map<String, String> displayNames = new TreeMap<>();   // cosmetic id -> the name players see (cosmetics.json)
    private final Set<String> everyone = new java.util.TreeSet<>();      // cosmetics every player wears
    private final Map<String, Shape> shapes = new HashMap<>();          // what's in each uploaded outfit, read once
    private final ArrayDeque<String> actions = new ArrayDeque<>();      // recent admin actions, newest last

    /** What an uploaded outfit holds, for the upload rules. */
    record Shape(int layers, boolean particles) {}
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private long ticks;

    public ServerCore(Platform platform) { this.p = platform; }

    public Config config() { return config; }
    public Collection<Cosmetic> cosmetics() { return cosmetics.values(); }

    // ------------------------------------------------------------------ files

    private Path dir() { return p.dataDir(); }
    private Path cosmeticsDir() { return dir().resolve("cosmetics"); }
    private Path uploadsDir() { return dir().resolve("uploads"); }

    /** Reads config.json, grants.json and the cosmetics folder (creating them the first time). */
    public void load() {
        try {
            Files.createDirectories(cosmeticsDir());
            Files.createDirectories(uploadsDir());
            Path cfg = dir().resolve("config.json");
            if (Files.isRegularFile(cfg)) config = Config.fromJson(Json.parse(Files.readString(cfg, StandardCharsets.UTF_8)));
            saveConfig();   // writes any setting that's missing
            Path readme = cosmeticsDir().resolve("README.txt");
            if (!Files.exists(readme)) Files.writeString(readme, COSMETICS_README, StandardCharsets.UTF_8);
        } catch (Exception e) {
            p.warn("[rainium] could not read config.json, using defaults: " + e.getMessage());
        }
        loadGrants();
        loadCosmetics();
        loadCosmeticSettings();
        pruneUploads();
        p.info("[rainium] multiplayer cosmetics ready: mode " + (config.ownOutfits ? "own" : "server") + ", " + cosmetics.size() + " installed cosmetic(s)");
    }

    private void saveConfig() throws IOException {
        Files.createDirectories(dir());
        Files.writeString(dir().resolve("config.json"), JsonWriter.write(config.toJson()), StandardCharsets.UTF_8);
    }

    private void loadCosmetics() {
        cosmetics.clear();
        byHash.clear();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(cosmeticsDir(), "*.zip")) {
            for (Path f : ds) {
                String id = idOf(f.getFileName().toString());
                if (id.isEmpty()) continue;
                long size = Files.size(f);
                if (size > NetProtocol.HARD_MAX_BLOB) { p.warn("[rainium] cosmetic " + id + " is over 32 MB, skipped"); continue; }
                byte[] data = Files.readAllBytes(f);
                if (!isZip(data)) { p.warn("[rainium] cosmetic " + id + " is not a zip, skipped"); continue; }
                Cosmetic c = new Cosmetic(id, NetProtocol.hash(data), f, data.length);
                cosmetics.put(id, c);
                byHash.put(c.hash(), c);
            }
        } catch (IOException e) {
            p.warn("[rainium] could not read the cosmetics folder: " + e.getMessage());
        }
    }

    private void loadGrants() {
        grants.clear();
        grantNames.clear();
        locked.clear();
        Path f = dir().resolve("grants.json");
        if (!Files.isRegularFile(f)) return;
        try {
            if (!(Json.parse(Files.readString(f, StandardCharsets.UTF_8)) instanceof Map<?, ?> root)) return;
            if (!(root.get("players") instanceof Map<?, ?> players)) return;
            for (Map.Entry<?, ?> e : players.entrySet()) {
                UUID u;
                try { u = UUID.fromString(String.valueOf(e.getKey())); } catch (IllegalArgumentException ex) { continue; }
                if (!(e.getValue() instanceof Map<?, ?> m)) continue;
                Set<String> ids = new java.util.TreeSet<>();
                if (m.get("cosmetics") instanceof List<?> l) for (Object o : l) { String id = idOf(String.valueOf(o)); if (!id.isEmpty()) ids.add(id); }
                if (!ids.isEmpty()) grants.put(u, ids);
                if (m.get("name") instanceof String n) grantNames.put(u, n);
                if (Boolean.TRUE.equals(m.get("locked"))) locked.add(u);
            }
        } catch (Exception e) {
            p.warn("[rainium] could not read grants.json: " + e.getMessage());
        }
    }

    private void saveGrants() {
        Map<String, Object> players = new TreeMap<>();
        Set<UUID> all = new HashSet<>(grants.keySet());
        all.addAll(locked);
        for (UUID u : all) {
            Set<String> g = grants.getOrDefault(u, Set.of());
            if (g.isEmpty() && !locked.contains(u)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            String n = grantNames.get(u);
            if (n != null) m.put("name", n);
            m.put("cosmetics", new ArrayList<Object>(g));
            if (locked.contains(u)) m.put("locked", true);
            players.put(u.toString(), m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("players", players);
        try { Files.writeString(dir().resolve("grants.json"), JsonWriter.write(root), StandardCharsets.UTF_8); }
        catch (IOException e) { p.warn("[rainium] could not write grants.json: " + e.getMessage()); }
    }

    private void loadCosmeticSettings() {
        displayNames.clear();
        everyone.clear();
        Path f = dir().resolve("cosmetics.json");
        if (!Files.isRegularFile(f)) return;
        try {
            if (!(Json.parse(Files.readString(f, StandardCharsets.UTF_8)) instanceof Map<?, ?> root)) return;
            for (Map.Entry<?, ?> e : root.entrySet()) {
                String id = idOf(String.valueOf(e.getKey()));
                if (id.isEmpty() || !(e.getValue() instanceof Map<?, ?> m)) continue;
                if (m.get("name") instanceof String n && !n.isBlank()) displayNames.put(id, n.length() > 48 ? n.substring(0, 48) : n);
                if (Boolean.TRUE.equals(m.get("everyone"))) everyone.add(id);
            }
        } catch (Exception e) {
            p.warn("[rainium] could not read cosmetics.json: " + e.getMessage());
        }
    }

    private void saveCosmeticSettings() {
        Map<String, Object> root = new TreeMap<>();
        Set<String> ids = new java.util.TreeSet<>(displayNames.keySet());
        ids.addAll(everyone);
        for (String id : ids) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (displayNames.containsKey(id)) m.put("name", displayNames.get(id));
            if (everyone.contains(id)) m.put("everyone", true);
            root.put(id, m);
        }
        try { Files.writeString(dir().resolve("cosmetics.json"), JsonWriter.write(root), StandardCharsets.UTF_8); }
        catch (IOException e) { p.warn("[rainium] could not write cosmetics.json: " + e.getMessage()); }
    }

    /** The name players see for a cosmetic. */
    private String shown(String id) { return displayNames.getOrDefault(id, id); }

    /** How many layers an uploaded outfit has and whether it has particles (read once per outfit; null if unreadable). */
    Shape shape(String hash) {
        if (hash.isEmpty()) return null;
        Shape sh = shapes.get(hash);
        if (sh != null) return sh;
        Path f = uploadsDir().resolve(hash + ".zip");
        if (!Files.isRegularFile(f)) return null;
        sh = new Shape(Integer.MAX_VALUE, true);   // what can't be read counts as breaking every rule
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(Files.newInputStream(f))) {
            java.util.zip.ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                boolean outfit = e.getName().equalsIgnoreCase("outfit.json"), set = e.getName().equalsIgnoreCase("set.json");
                if (!outfit && !set) continue;
                byte[] b = z.readNBytes(8 << 20);
                Object root = Json.parse(new String(b, StandardCharsets.UTF_8));
                Object layers = root instanceof Map<?, ?> rm ? (outfit ? (rm.get("config") instanceof Map<?, ?> c ? c.get("layers") : null) : rm.get("layers")) : null;
                if (layers instanceof List<?> ls) {
                    boolean particles = false;
                    for (Object o : ls) if (o instanceof Map<?, ?> lm && lm.get("particles") != null) particles = true;
                    sh = new Shape(ls.size(), particles);
                }
                break;
            }
        } catch (Exception ignored) { }
        if (shapes.size() > 4096) shapes.clear();
        shapes.put(hash, sh);
        return sh;
    }

    /** Why an uploaded outfit breaks this server's rules, or null when it doesn't. */
    private String brokenRule(String hash) {
        Shape sh = shape(hash);
        if (sh == null) return null;
        if (sh.layers() > config.maxLayers) return sh.layers() == Integer.MAX_VALUE ? "it couldn't be read" : "it has " + sh.layers() + " layers and the limit here is " + config.maxLayers;
        if (sh.particles() && !config.ownParticles) return "particle layers aren't allowed here";
        return null;
    }

    /** Whether the player is in a world where cosmetics are off. */
    private boolean inWorldOff(Session s) {
        if (s == null || s.world.isEmpty()) return false;
        for (String w : config.worldsOff) if (sameWorld(s.world, w)) return true;
        return false;
    }

    static boolean sameWorld(String world, String entry) {
        String a = world.toLowerCase(Locale.ROOT).trim(), b = entry.toLowerCase(Locale.ROOT).trim();
        if (a.equals(b)) return true;
        String ap = a.substring(a.indexOf(':') + 1), bp = b.substring(b.indexOf(':') + 1);
        return ap.equals(bp);
    }

    private void logAction(UUID sender, String[] args) {
        String who = sender == null ? "console" : String.valueOf(p.name(sender));
        String line = java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ') + "  " + who + ": /rainiumserver " + String.join(" ", args);
        actions.add(line);
        while (actions.size() > 50) actions.poll();
        try { Files.writeString(dir().resolve("actions.log"), line + System.lineSeparator(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
        catch (IOException ignored) { }
    }

    /** Keeps the folder of uploaded outfits under its size limit, oldest first (never one being worn). */
    private void pruneUploads() {
        try {
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(uploadsDir(), "*.zip")) { for (Path f : ds) files.add(f); }
            long total = 0;
            Map<Path, Long> times = new HashMap<>();
            for (Path f : files) { total += Files.size(f); times.put(f, Files.getLastModifiedTime(f).toMillis()); }
            long limit = (long) config.uploadCacheMb << 20;
            if (total <= limit) return;
            files.sort((a, b) -> Long.compare(times.get(a), times.get(b)));
            Set<String> inUse = new HashSet<>();
            for (Session s : sessions.values()) if (!s.ownHash.isEmpty()) inUse.add(s.ownHash);
            for (Path f : files) {
                if (total <= limit) break;
                String h = f.getFileName().toString().replace(".zip", "");
                if (inUse.contains(h)) continue;
                total -= Files.size(f);
                Files.deleteIfExists(f);
            }
        } catch (IOException e) {
            p.warn("[rainium] could not tidy the uploads folder: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ players coming and going

    /** Any player joined (with or without Rainium): what they wear is shown to everyone who has it. */
    public void playerJoined(UUID u) {
        sessions.computeIfAbsent(u, Session::new);
        refresh(u, true);
    }

    /** The player's client has Rainium and listens on the channel: say hello and tell them who wears what. */
    public void rainiumJoined(UUID u) {
        Session s = sessions.computeIfAbsent(u, Session::new);
        if (s.rainium) return;
        s.rainium = true;
        s.world = String.valueOf(p.world(u));
        welcome(s);
        if (!config.joinMessage.isBlank()) queue(s, NetProtocol.notice(config.joinMessage));
        for (Session o : sessions.values()) {
            if (o.worn.isEmpty() && o != s) continue;
            queue(s, NetProtocol.wearing(new NetProtocol.Wearing(o.id, o.worn)));
        }
    }

    /** Tells the client the mode and whether it may share its own cosmetics (again, when either changed). */
    private void welcome(Session s) {
        s.welcomed = welcomeKey(s.id);
        queue(s, NetProtocol.welcome(new NetProtocol.Welcome(NetProtocol.VERSION, config.ownOutfits ? NetProtocol.MODE_OWN : NetProtocol.MODE_SERVER_ONLY,
            sharesOwn(s.id), config.maxOutfitKb << 10, p.serverName(), ownAllowed(s.id), isAdmin(s.id))));
    }

    /** What the client was told, to notice when it changes. */
    private String welcomeKey(UUID u) { return config.ownOutfits + "/" + sharesOwn(u) + "/" + ownAllowed(u) + "/" + isAdmin(u) + "/" + config.maxOutfitKb; }

    /** Whether the player's own cosmetics may show here at all (the server can turn them off, for everyone or one player). */
    public boolean ownAllowed(UUID u) { return config.clientCosmetics && !locked.contains(u) && !inWorldOff(sessions.get(u)); }

    /** Whether the player's own cosmetics are shared with others. */
    private boolean sharesOwn(UUID u) { return config.ownOutfits && ownAllowed(u) && canUpload(u); }

    public void playerLeft(UUID u) {
        Session s = sessions.remove(u);
        if (s == null) return;
        if (!s.worn.isEmpty()) broadcast(NetProtocol.wearing(new NetProtocol.Wearing(u, List.of())));
    }

    // ------------------------------------------------------------------ messages

    public void received(UUID u, byte[] msg) {
        Session s = sessions.get(u);
        if (s == null) { playerJoined(u); s = sessions.get(u); }
        if (!s.rainium) rainiumJoined(u);   // a client that talks to us has the channel
        try {
            switch (NetProtocol.type(msg)) {
                case NetProtocol.C_HELLO -> { /* the version is in the welcome; nothing more to do */ }
                case NetProtocol.C_OUTFIT -> outfit(s, NetProtocol.readOutfit(msg));
                case NetProtocol.C_PART -> uploadPart(s, NetProtocol.readPart(msg));
                case NetProtocol.C_FETCH -> fetch(s, NetProtocol.readHash(msg));
                case NetProtocol.C_ADMIN_OPEN -> { if (isAdmin(u)) queue(s, NetProtocol.admin(adminState(List.of()))); }
                case NetProtocol.C_ADMIN_RUN -> {
                    if (!isAdmin(u)) break;
                    List<String> said = command(u, NetProtocol.readAdminRun(msg).toArray(new String[0]));
                    queue(s, NetProtocol.admin(adminState(said)));
                }
                default -> { }
            }
        } catch (IOException | RuntimeException e) {
            // a bad message from one client is ignored; it never stops the server
        }
    }

    private void outfit(Session s, NetProtocol.Outfit o) {
        String hash = o.hash();
        if (!hash.isEmpty() && !NetProtocol.isHash(hash)) return;
        s.upload.clear();
        s.waitingUpload = false;
        s.ownHash = hash;
        s.ownSize = Math.max(0, o.size());
        s.ownReady = false;
        s.toldRules = false;
        if (hash.isEmpty() || !config.ownOutfits) { refresh(s.id, false); return; }
        if (!ownAllowed(s.id)) { refresh(s.id, false); return; }
        if (!canUpload(s.id)) {
            if (!s.toldNoUpload) queue(s, NetProtocol.notice("This server doesn't share your own cosmetics with others. Cosmetics the server gives you still show."));
            s.toldNoUpload = true;
            refresh(s.id, false);
            return;
        }
        if (o.size() > config.maxOutfitKb << 10) {
            if (!s.toldTooBig) queue(s, NetProtocol.notice("Your cosmetics are too big to share here (" + (o.size() >> 10) + " KB, the limit is " + config.maxOutfitKb + " KB). Others won't see them."));
            s.toldTooBig = true;
            refresh(s.id, false);
            return;
        }
        Path f = uploadsDir().resolve(hash + ".zip");
        if (Files.isRegularFile(f)) {
            try { Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())); } catch (IOException ignored) { }
            s.ownReady = true;
            refresh(s.id, false);
            return;
        }
        s.waitingUpload = true;
        queue(s, NetProtocol.need(hash));
        refresh(s.id, false);
    }

    private void uploadPart(Session s, NetProtocol.Part part) {
        if (!s.waitingUpload || !part.hash().equals(s.ownHash) || part.size() > config.maxOutfitKb << 10) return;
        byte[] whole = s.upload.add(part, System.currentTimeMillis());
        if (whole == null) return;
        s.waitingUpload = false;
        if (!isZip(whole)) return;
        try {
            Path tmp = uploadsDir().resolve(s.ownHash + ".part");
            Files.write(tmp, whole);
            Files.move(tmp, uploadsDir().resolve(s.ownHash + ".zip"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            p.warn("[rainium] could not store an outfit: " + e.getMessage());
            return;
        }
        s.ownReady = true;
        refresh(s.id, false);
        if (ticks % 20 == 0) pruneUploads();
    }

    private void fetch(Session s, String hash) {
        if (!NetProtocol.isHash(hash) || s.queuedBlobs.contains(hash)) return;
        if (s.queuedBytes > 64L << 20) return;   // already sending this player plenty
        boolean worn = false;
        for (Session o : sessions.values()) for (NetProtocol.Worn w : o.worn) if (w.hash().equals(hash)) { worn = true; break; }
        if (!worn) return;   // only what someone wears right now can be fetched
        byte[] data = blob(hash);
        if (data == null) return;
        s.queuedBlobs.add(hash);
        for (byte[] m : NetProtocol.split(NetProtocol.S_PART, hash, data)) queue(s, m);
    }

    private byte[] blob(String hash) {
        try {
            Cosmetic c = byHash.get(hash);
            Path f = c != null ? c.file() : uploadsDir().resolve(hash + ".zip");
            if (!Files.isRegularFile(f)) return null;
            byte[] data = Files.readAllBytes(f);
            return NetProtocol.hash(data).equals(hash) ? data : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ who wears what

    private boolean allowed(UUID u, String node, boolean dflt) {
        Boolean b = p.permission(u, node);
        return b != null ? b : dflt;
    }

    public boolean canUpload(UUID u) { return allowed(u, NODE_UPLOAD, config.uploadsByDefault); }

    public boolean isAdmin(UUID u) { return u == null || allowed(u, NODE_ADMIN, p.isOp(u)); }

    private List<NetProtocol.Worn> wornBy(Session s) {
        List<NetProtocol.Worn> out = new ArrayList<>();
        if (inWorldOff(s)) return List.of();   // no cosmetics at all in this world
        if (s.ownReady && !s.ownHash.isEmpty() && sharesOwn(s.id)) {
            String broken = brokenRule(s.ownHash);
            if (broken == null) out.add(new NetProtocol.Worn(NetProtocol.KIND_OWN, s.ownHash, s.ownSize, ""));
            else if (!s.toldRules) {
                s.toldRules = true;
                queue(s, NetProtocol.notice("Your own cosmetics aren't shared here: " + broken + "."));
            }
        }
        Set<String> given = grants.getOrDefault(s.id, Set.of());
        for (Cosmetic c : cosmetics.values()) {
            if (out.size() >= MAX_WORN) break;
            if (everyone.contains(c.id()) || given.contains(c.id()) || allowed(s.id, NODE_COSMETIC + c.id(), false))
                out.add(new NetProtocol.Worn(NetProtocol.KIND_SERVER, c.hash(), c.size(), shown(c.id())));
        }
        return List.copyOf(out);
    }

    /** Works out what a player wears and tells everyone when it changed (or always, when force). */
    private void refresh(UUID u, boolean force) {
        Session s = sessions.get(u);
        if (s == null) return;
        List<NetProtocol.Worn> now = wornBy(s);
        if (!force && now.equals(s.worn)) return;
        boolean wasEmpty = s.worn.isEmpty();
        s.worn = now;
        if (force && now.isEmpty() && wasEmpty) return;
        broadcast(NetProtocol.wearing(new NetProtocol.Wearing(u, now)));
    }

    private void refreshAll() {
        for (Session s : sessions.values()) {
            if (!s.rainium) continue;
            if (!s.welcomed.equals(welcomeKey(s.id))) welcome(s);   // the mode, a lock, or their permissions changed
        }
        for (UUID u : new ArrayList<>(sessions.keySet())) refresh(u, false);
    }

    // ------------------------------------------------------------------ sending

    private void queue(Session s, byte[] msg) {
        if (!s.rainium) return;
        s.out.add(msg);
        s.queuedBytes += msg.length;
    }

    private void broadcast(byte[] msg) { for (Session s : sessions.values()) queue(s, msg); }

    /** Every server tick: send what's queued (a fair share per player), and now and then re-check permissions. */
    public void tick() {
        ticks++;
        int budget = config.sendKbPerTick << 10;
        for (Session s : sessions.values()) {
            int sent = 0;
            while (!s.out.isEmpty() && (sent == 0 || sent + s.out.peek().length <= budget)) {
                byte[] m = s.out.poll();
                s.queuedBytes -= m.length;
                sent += m.length;
                p.send(s.id, m);
            }
            if (s.out.isEmpty()) s.queuedBlobs.clear();
        }
        if (ticks % 100 == 0) {
            long now = System.currentTimeMillis();
            for (Session s : sessions.values()) {
                s.upload.expire(now, 60_000);
                if (s.waitingUpload && !s.upload.receiving(s.ownHash) && ticks % 1200 == 0) s.waitingUpload = false;
            }
        }
        if (ticks % 20 == 0 && !config.worldsOff.isEmpty()) {   // moving between worlds: cosmetics go off or on
            boolean moved = false;
            for (Session s : sessions.values()) {
                String w = String.valueOf(p.world(s.id));
                if (!w.equals(s.world)) { s.world = w; moved = true; }
            }
            if (moved) refreshAll();
        }
        if (ticks % 200 == 0) refreshAll();   // LuckPerms changes show up within ten seconds
    }

    // ------------------------------------------------------------------ /rainiumserver

    /** Runs /rainiumserver with these words; sender is null for the console. Returns the lines to show. */
    public List<String> command(UUID sender, String[] args) {
        List<String> out = new ArrayList<>();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!isAdmin(sender)) {
            out.add("You need the permission " + NODE_ADMIN + " (or to be an operator) to manage Rainium.");
            return out;
        }
        if (Set.of("mode", "give", "take", "giveall", "takeall", "everyone", "rename", "install", "remove", "lock", "unlock", "set", "reload").contains(sub) && args.length > 1 || sub.equals("reload"))
            logAction(sender, args);
        switch (sub) {
            case "mode" -> {
                if (args.length < 2) { out.add("Mode: " + (config.ownOutfits ? "own" : "server") + ". Use /rainiumserver mode own|server."); break; }
                String m = args[1].toLowerCase(Locale.ROOT);
                if (!m.equals("own") && !m.equals("server")) { out.add("Mode is 'own' (players' own cosmetics and server ones) or 'server' (server cosmetics only)."); break; }
                config.ownOutfits = m.equals("own");
                try { saveConfig(); } catch (IOException e) { out.add("Could not save config.json: " + e.getMessage()); }
                refreshAll();
                out.add("Mode set to " + m + ". Players who rejoin get the new mode's settings.");
            }
            case "list" -> {
                if (cosmetics.isEmpty()) { out.add("No cosmetics installed. Put Rainium set zips in " + cosmeticsDir().getFileName() + "/ and run /rainiumserver reload, or use /rainiumserver install."); break; }
                out.add(cosmetics.size() + " installed cosmetic(s):");
                for (Cosmetic c : cosmetics.values()) {
                    int n = 0;
                    for (Set<String> g : grants.values()) if (g.contains(c.id())) n++;
                    out.add(" - " + c.id() + (displayNames.containsKey(c.id()) ? " \"" + shown(c.id()) + "\"" : "") + " (" + kb(c.size()) + ", " + (everyone.contains(c.id()) ? "everyone wears it" : "given to " + n) + "; permission " + NODE_COSMETIC + c.id() + ")");
                }
            }
            case "give", "take" -> {
                if (args.length < 3) { out.add("Use /rainiumserver " + sub + " <player> <cosmetic" + (sub.equals("take") ? "|all" : "") + ">."); break; }
                UUID who = target(args[1]);
                if (who == null) { out.add("No player " + args[1] + " is online (a UUID works too)."); break; }
                String id = idOf(args[2]);
                Set<String> g = grants.computeIfAbsent(who, k -> new java.util.TreeSet<>());
                String name = p.name(who);
                if (name != null) grantNames.put(who, name);
                String shown = name != null ? name : who.toString();
                if (sub.equals("give")) {
                    if (!cosmetics.containsKey(id)) { out.add("No cosmetic called " + args[2] + ". See /rainiumserver list."); break; }
                    out.add(g.add(id) ? "Gave " + id + " to " + shown + "." : shown + " already has " + id + ".");
                } else if (args[2].equalsIgnoreCase("all")) {
                    out.add("Took " + g.size() + " cosmetic(s) from " + shown + ".");
                    g.clear();
                } else {
                    out.add(g.remove(id) ? "Took " + id + " from " + shown + "." : shown + " wasn't given " + id + ". (A permission " + NODE_COSMETIC + id + " is changed in your permissions plugin.)");
                }
                saveGrants();
                refresh(who, false);
            }
            case "set" -> {
                if (args.length < 3) { out.add("Use /rainiumserver set <setting> <value>. Settings: client_cosmetics, uploads_by_default, own_particles (true/false); max_outfit_kb, send_kb_per_tick, upload_cache_mb, max_layers (numbers); worlds_off (world names, comma-separated, or - for none); join_message (text, or - for none)."); break; }
                String key = args[1].toLowerCase(Locale.ROOT), v = args[2].toLowerCase(Locale.ROOT);
                String raw = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
                Boolean b = v.equals("true") || v.equals("on") || v.equals("yes") ? Boolean.TRUE : v.equals("false") || v.equals("off") || v.equals("no") ? Boolean.FALSE : null;
                Integer n = null;
                try { n = Integer.parseInt(v); } catch (NumberFormatException ignored) { }
                switch (key) {
                    case "client_cosmetics" -> { if (b == null) { out.add("client_cosmetics is true or false."); break; } config.clientCosmetics = b; out.add(b ? "Players' own cosmetics show here again." : "Players' own cosmetics are turned off here (they keep them; they just don't show). Server cosmetics still show."); }
                    case "uploads_by_default" -> { if (b == null) { out.add("uploads_by_default is true or false."); break; } config.uploadsByDefault = b; out.add("Without a permission set, players " + (b ? "may" : "may not") + " share their own cosmetics."); }
                    case "max_outfit_kb" -> { if (n == null) { out.add("max_outfit_kb is a number."); break; } config.maxOutfitKb = clamp(n, 16, NetProtocol.HARD_MAX_BLOB >> 10); out.add("Largest outfit: " + config.maxOutfitKb + " KB."); }
                    case "send_kb_per_tick" -> { if (n == null) { out.add("send_kb_per_tick is a number."); break; } config.sendKbPerTick = clamp(n, 8, 4096); out.add("Sending up to " + config.sendKbPerTick + " KB a tick to each player."); }
                    case "upload_cache_mb" -> { if (n == null) { out.add("upload_cache_mb is a number."); break; } config.uploadCacheMb = clamp(n, 16, 1 << 16); pruneUploads(); out.add("Upload cache: " + config.uploadCacheMb + " MB."); }
                    case "max_layers" -> { if (n == null) { out.add("max_layers is a number."); break; } config.maxLayers = clamp(n, 1, 512); out.add("Players' own outfits with more than " + config.maxLayers + " layers aren't shared."); }
                    case "own_particles" -> { if (b == null) { out.add("own_particles is true or false."); break; } config.ownParticles = b; out.add(b ? "Players' own outfits may have particles." : "Players' own outfits with particle layers aren't shared."); }
                    case "worlds_off", "cosmetics_off_in_worlds" -> {
                        config.worldsOff.clear();
                        if (!raw.equals("-")) for (String w : raw.split("[,\\s]+")) if (!w.isBlank()) config.worldsOff.add(w.trim());
                        out.add(config.worldsOff.isEmpty() ? "Cosmetics show in every world." : "No cosmetics in: " + String.join(", ", config.worldsOff) + ".");
                    }
                    case "join_message" -> {
                        config.joinMessage = raw.equals("-") ? "" : raw.length() > 300 ? raw.substring(0, 300) : raw;
                        out.add(config.joinMessage.isEmpty() ? "No join message." : "Players with Rainium are told when they join: " + config.joinMessage);
                    }
                    default -> out.add("No setting called " + args[1] + ".");
                }
                try { saveConfig(); } catch (IOException e) { out.add("Could not save config.json: " + e.getMessage()); }
                refreshAll();
            }
            case "giveall", "takeall" -> {
                if (args.length < 2) { out.add("Use /rainiumserver " + sub + " <cosmetic>: " + (sub.equals("giveall") ? "gives it to" : "takes it from") + " everyone online now."); break; }
                String id = idOf(args[1]);
                if (sub.equals("giveall") && !cosmetics.containsKey(id)) { out.add("No cosmetic called " + args[1] + ". See /rainiumserver list."); break; }
                int n = 0;
                for (Session s : sessions.values()) {
                    Set<String> g = grants.computeIfAbsent(s.id, k -> new java.util.TreeSet<>());
                    String name = p.name(s.id);
                    if (name != null) grantNames.put(s.id, name);
                    if (sub.equals("giveall") ? g.add(id) : g.remove(id)) n++;
                }
                saveGrants();
                refreshAll();
                out.add((sub.equals("giveall") ? "Gave " + id + " to " : "Took " + id + " from ") + n + " player(s) online." + (sub.equals("giveall") ? " (For everyone, now and later: /rainiumserver everyone " + id + " on.)" : ""));
            }
            case "everyone" -> {
                if (args.length < 3) { out.add("Use /rainiumserver everyone <cosmetic> on|off: every player wears it, now and later."); break; }
                String id = idOf(args[1]);
                if (!cosmetics.containsKey(id)) { out.add("No cosmetic called " + args[1] + "."); break; }
                boolean on = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true");
                if (on) everyone.add(id); else everyone.remove(id);
                saveCosmeticSettings();
                refreshAll();
                out.add(on ? "Everyone wears " + id + " now." : id + " is worn only by those given it.");
            }
            case "rename" -> {
                if (args.length < 3) { out.add("Use /rainiumserver rename <cosmetic> <name players see> (or - to use its id)."); break; }
                String id = idOf(args[1]);
                if (!cosmetics.containsKey(id)) { out.add("No cosmetic called " + args[1] + "."); break; }
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
                if (name.equals("-") || name.isEmpty()) displayNames.remove(id);
                else displayNames.put(id, name.length() > 48 ? name.substring(0, 48) : name);
                saveCosmeticSettings();
                refreshAll();
                out.add("Players now see " + id + " as \"" + shown(id) + "\".");
            }
            case "log" -> {
                if (actions.isEmpty()) { out.add("No admin actions since the server started (older ones are in actions.log)."); break; }
                out.add("Recent admin actions:");
                List<String> all = new ArrayList<>(actions);
                for (String l : all.subList(Math.max(0, all.size() - 10), all.size())) out.add(" " + l);
            }
            case "lock", "unlock" -> {
                if (args.length < 2) { out.add("Use /rainiumserver " + sub + " <player>: turns " + (sub.equals("lock") ? "off" : "back on") + " that player's own cosmetics here."); break; }
                UUID who = target(args[1]);
                if (who == null) { out.add("No player " + args[1] + " is online (a UUID works too)."); break; }
                String name = p.name(who);
                if (name != null) grantNames.put(who, name);
                String shown = name != null ? name : who.toString();
                if (sub.equals("lock")) out.add(locked.add(who) ? "Turned off " + shown + "'s own cosmetics here. They keep them; they just don't show." : shown + "'s own cosmetics were already off.");
                else out.add(locked.remove(who) ? shown + "'s own cosmetics show again." : shown + "'s own cosmetics weren't turned off.");
                saveGrants();
                refreshAll();
            }
            case "wearing" -> {
                if (args.length < 2) { out.add("Use /rainiumserver wearing <player>."); break; }
                UUID who = target(args[1]);
                Session s = who == null ? null : sessions.get(who);
                if (s == null) { out.add("No player " + args[1] + " is online."); break; }
                out.add(p.name(who) + (s.rainium ? " has Rainium." : " doesn't have Rainium (others with it still see their server cosmetics)."));
                if (s.worn.isEmpty()) out.add("Wearing nothing that others see.");
                for (NetProtocol.Worn w : s.worn) out.add(" - " + (w.kind() == NetProtocol.KIND_OWN ? "their own cosmetics" : w.name()) + " (" + kb(w.size()) + ")");
                if (!ownAllowed(who)) out.add(" (their own cosmetics are turned off here" + (locked.contains(who) ? ": /rainiumserver unlock " + p.name(who) : ": client_cosmetics is false") + ")");
                else if (config.ownOutfits && !s.ownHash.isEmpty() && !s.ownReady) out.add(canUpload(who) ? " (their own cosmetics are still uploading or too big)" : " (not allowed to share their own: " + NODE_UPLOAD + ")");
            }
            case "install" -> {
                if (args.length < 3) { out.add("Use /rainiumserver install <new id> <player>: saves what that player wears of their own as a server cosmetic."); break; }
                String id = idOf(args[1]);
                if (id.isEmpty()) { out.add("Cosmetic ids use letters, digits, - and _."); break; }
                UUID who = target(args[2]);
                Session s = who == null ? null : sessions.get(who);
                if (s == null || s.ownHash.isEmpty() || !s.ownReady) { out.add((who == null ? args[2] : p.name(who)) + " isn't wearing an uploaded outfit of their own right now. They need Rainium, the 'own' mode and " + NODE_UPLOAD + "."); break; }
                try {
                    Files.copy(uploadsDir().resolve(s.ownHash + ".zip"), cosmeticsDir().resolve(id + ".zip"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) { out.add("Could not copy it: " + e.getMessage()); break; }
                loadCosmetics();
                refreshAll();
                out.add("Installed " + id + ". Give it with /rainiumserver give <player> " + id + " or the permission " + NODE_COSMETIC + id + ".");
            }
            case "remove" -> {
                if (args.length < 2) { out.add("Use /rainiumserver remove <cosmetic>."); break; }
                Cosmetic c = cosmetics.get(idOf(args[1]));
                if (c == null) { out.add("No cosmetic called " + args[1] + "."); break; }
                try {
                    Path bin = cosmeticsDir().resolve("removed");
                    Files.createDirectories(bin);
                    Files.move(c.file(), bin.resolve(c.file().getFileName()), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) { out.add("Could not move it: " + e.getMessage()); break; }
                loadCosmetics();
                refreshAll();
                out.add("Removed " + c.id() + " (moved to cosmetics/removed/). Grants for it stay, in case you put it back.");
            }
            case "reload" -> {
                load();
                refreshAll();
                out.add("Reloaded: mode " + (config.ownOutfits ? "own" : "server") + ", " + cosmetics.size() + " cosmetic(s).");
            }
            default -> {
                out.add("Rainium server commands:");
                out.add(" /rainiumserver mode own|server  - share players' own cosmetics, or only the server's");
                out.add(" /rainiumserver list  - installed cosmetics");
                out.add(" /rainiumserver give|take <player> <cosmetic>  - give or take a server cosmetic");
                out.add(" /rainiumserver install <id> <player>  - save what a player wears as a server cosmetic");
                out.add(" /rainiumserver remove <cosmetic>  - uninstall one");
                out.add(" /rainiumserver wearing <player>  - what someone wears");
                out.add(" /rainiumserver lock|unlock <player>  - turn a player's own cosmetics off or back on here");
                out.add(" /rainiumserver set <setting> <value>  - client_cosmetics, uploads_by_default, max_outfit_kb, send_kb_per_tick, upload_cache_mb");
                out.add(" /rainiumserver giveall|takeall <cosmetic>  - give it to, or take it from, everyone online");
                out.add(" /rainiumserver everyone <cosmetic> on|off  - every player wears it, now and later");
                out.add(" /rainiumserver rename <cosmetic> <name>  - the name players see");
                out.add(" /rainiumserver log  - recent admin actions (all of them are in actions.log)");
                out.add(" Or use the Server tab in the Rainium editor (K).");
                out.add(" /rainiumserver reload  - re-read config.json, grants.json and cosmetics/");
            }
        }
        return out;
    }

    /** Suggestions for the word being typed (args has at least one, possibly empty, word). */
    public List<String> complete(String[] args) {
        List<String> opts = new ArrayList<>();
        if (args.length <= 1) opts.addAll(List.of("mode", "list", "give", "take", "giveall", "takeall", "everyone", "rename", "install", "remove", "wearing", "lock", "unlock", "set", "log", "reload", "help"));
        else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 2) {
                switch (sub) {
                    case "mode" -> opts.addAll(List.of("own", "server"));
                    case "give", "take", "wearing", "lock", "unlock" -> opts.addAll(p.onlineNames());
                    case "set" -> opts.addAll(List.of("client_cosmetics", "uploads_by_default", "own_particles", "max_outfit_kb", "send_kb_per_tick", "upload_cache_mb", "max_layers", "worlds_off", "join_message"));
                    case "remove", "giveall", "takeall", "everyone", "rename" -> opts.addAll(cosmetics.keySet());
                    default -> { }
                }
            } else if (args.length == 3) {
                switch (sub) {
                    case "give" -> opts.addAll(cosmetics.keySet());
                    case "take" -> {
                        UUID who = target(args[1]);
                        if (who != null) opts.addAll(grants.getOrDefault(who, Set.of()));
                        opts.add("all");
                    }
                    case "install" -> opts.addAll(p.onlineNames());
                    case "set" -> { if (Set.of("client_cosmetics", "uploads_by_default", "own_particles").contains(args[1].toLowerCase(Locale.ROOT))) opts.addAll(List.of("true", "false")); }
                    case "everyone" -> opts.addAll(List.of("on", "off"));
                    default -> { }
                }
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        opts.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return opts;
    }

    private UUID target(String s) {
        UUID u = p.find(s);
        if (u != null) return u;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    /** The admin panel's contents: the settings, the cosmetics and the players online (and what an action just said). */
    String adminState(List<String> said) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> cfg = config.toJson();
        root.put("config", cfg);
        List<Object> cs = new ArrayList<>();
        for (Cosmetic c : cosmetics.values()) {
            int n = 0;
            for (Set<String> g : grants.values()) if (g.contains(c.id())) n++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("kb", Math.max(1, c.size() >> 10));
            m.put("given", n);
            m.put("name", shown(c.id()));
            m.put("renamed", displayNames.containsKey(c.id()));
            m.put("everyone", everyone.contains(c.id()));
            cs.add(m);
        }
        root.put("cosmetics", cs);
        List<Object> ps = new ArrayList<>();
        for (Session s : sessions.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", s.id.toString());
            String name = p.name(s.id);
            m.put("name", name == null ? s.id.toString().substring(0, 8) : name);
            m.put("rainium", s.rainium);
            m.put("locked", locked.contains(s.id));
            m.put("world", s.world.isEmpty() ? String.valueOf(p.world(s.id)) : s.world);
            String own;
            Shape sh = s.ownReady ? shape(s.ownHash) : null;
            if (sh != null && sh.layers() != Integer.MAX_VALUE) m.put("layers", sh.layers());
            if (inWorldOff(s)) own = "world_off";
            else if (!ownAllowed(s.id)) own = "off";
            else if (!config.ownOutfits) own = "private";
            else if (!canUpload(s.id)) own = "not_allowed";
            else if (s.ownHash.isEmpty()) own = s.rainium ? "none" : "no_mod";
            else if (s.ownReady) own = brokenRule(s.ownHash) == null ? "shared" : "rules";
            else if (s.ownSize > config.maxOutfitKb << 10) own = "too_big";
            else own = "uploading";
            m.put("own", own);
            m.put("given", new ArrayList<Object>(grants.getOrDefault(s.id, Set.of())));
            List<Object> byPerm = new ArrayList<>();
            for (Cosmetic c : cosmetics.values()) if (allowed(s.id, NODE_COSMETIC + c.id(), false)) byPerm.add(c.id());
            m.put("by_permission", byPerm);
            ps.add(m);
        }
        root.put("players", ps);
        root.put("said", new ArrayList<Object>(said));
        List<String> recent = new ArrayList<>(actions);
        root.put("log", new ArrayList<Object>(recent.subList(Math.max(0, recent.size() - 8), recent.size())));
        Set<String> worlds = new java.util.TreeSet<>();
        for (Session s : sessions.values()) if (!s.world.isEmpty()) worlds.add(s.world);
        root.put("worlds", new ArrayList<Object>(worlds));
        return JsonWriter.write(root);
    }

    // ------------------------------------------------------------------ small things

    static String idOf(String fileName) {
        String s = fileName.toLowerCase(Locale.ROOT);
        if (s.endsWith(".zip")) s = s.substring(0, s.length() - 4);
        s = s.replaceAll("[^a-z0-9_-]", "_");
        if (s.length() > 48) s = s.substring(0, 48);
        return s.replaceAll("^_+|_+$", "");
    }

    static boolean isZip(byte[] d) { return d.length >= 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4; }

    private static String kb(int bytes) { return Math.max(1, bytes >> 10) + " KB"; }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static final String COSMETICS_README = """
        Rainium server cosmetics
        ========================
        Each .zip here is a cosmetic the server can give to players. Its name (without .zip) is its id.

        Make one in the game: build it in the Rainium editor (K), put it in a folder, and click the
        folder's Export as zip; the zip lands in config/rainium/exports/. Copy it here and run
        /rainiumserver reload. Or wear it yourself on this server and run
        /rainiumserver install <id> <your name>.

        Give one with /rainiumserver give <player> <id>, or with the permission rainium.cosmetic.<id>
        in LuckPerms (for a whole group, for example). Players with Rainium see it on the wearer.
        """;
}
