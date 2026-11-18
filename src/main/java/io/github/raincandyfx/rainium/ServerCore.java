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

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", ownOutfits ? "own" : "server");
            m.put("uploads_by_default", uploadsByDefault);
            m.put("max_outfit_kb", maxOutfitKb);
            m.put("send_kb_per_tick", sendKbPerTick);
            m.put("upload_cache_mb", uploadCacheMb);
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
        boolean ownReady, waitingUpload, toldNoUpload, toldTooBig;
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
            }
        } catch (Exception e) {
            p.warn("[rainium] could not read grants.json: " + e.getMessage());
        }
    }

    private void saveGrants() {
        Map<String, Object> players = new TreeMap<>();
        for (Map.Entry<UUID, Set<String>> e : grants.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            String n = grantNames.get(e.getKey());
            if (n != null) m.put("name", n);
            m.put("cosmetics", new ArrayList<Object>(e.getValue()));
            players.put(e.getKey().toString(), m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("players", players);
        try { Files.writeString(dir().resolve("grants.json"), JsonWriter.write(root), StandardCharsets.UTF_8); }
        catch (IOException e) { p.warn("[rainium] could not write grants.json: " + e.getMessage()); }
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
        welcome(s);
        for (Session o : sessions.values()) {
            if (o.worn.isEmpty() && o != s) continue;
            queue(s, NetProtocol.wearing(new NetProtocol.Wearing(o.id, o.worn)));
        }
    }

    /** Tells the client the mode and whether it may share its own cosmetics (again, when either changed). */
    private void welcome(Session s) {
        boolean up = config.ownOutfits && canUpload(s.id);
        s.welcomed = config.ownOutfits + "/" + up;
        queue(s, NetProtocol.welcome(new NetProtocol.Welcome(NetProtocol.VERSION, config.ownOutfits ? NetProtocol.MODE_OWN : NetProtocol.MODE_SERVER_ONLY,
            up, config.maxOutfitKb << 10, p.serverName())));
    }

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
        if (hash.isEmpty() || !config.ownOutfits) { refresh(s.id, false); return; }
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
        if (config.ownOutfits && s.ownReady && !s.ownHash.isEmpty() && canUpload(s.id))
            out.add(new NetProtocol.Worn(NetProtocol.KIND_OWN, s.ownHash, s.ownSize, ""));
        Set<String> given = grants.getOrDefault(s.id, Set.of());
        for (Cosmetic c : cosmetics.values()) {
            if (out.size() >= MAX_WORN) break;
            if (given.contains(c.id()) || allowed(s.id, NODE_COSMETIC + c.id(), false))
                out.add(new NetProtocol.Worn(NetProtocol.KIND_SERVER, c.hash(), c.size(), c.id()));
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
            boolean up = config.ownOutfits && canUpload(s.id);
            if (!s.welcomed.equals(config.ownOutfits + "/" + up)) welcome(s);   // the mode or their upload permission changed
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
                    out.add(" - " + c.id() + " (" + kb(c.size()) + ", given to " + n + "; permission " + NODE_COSMETIC + c.id() + ")");
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
            case "wearing" -> {
                if (args.length < 2) { out.add("Use /rainiumserver wearing <player>."); break; }
                UUID who = target(args[1]);
                Session s = who == null ? null : sessions.get(who);
                if (s == null) { out.add("No player " + args[1] + " is online."); break; }
                out.add(p.name(who) + (s.rainium ? " has Rainium." : " doesn't have Rainium (others with it still see their server cosmetics)."));
                if (s.worn.isEmpty()) out.add("Wearing nothing that others see.");
                for (NetProtocol.Worn w : s.worn) out.add(" - " + (w.kind() == NetProtocol.KIND_OWN ? "their own cosmetics" : w.name()) + " (" + kb(w.size()) + ")");
                if (config.ownOutfits && !s.ownHash.isEmpty() && !s.ownReady) out.add(canUpload(who) ? " (their own cosmetics are still uploading or too big)" : " (not allowed to share their own: " + NODE_UPLOAD + ")");
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
                out.add(" /rainiumserver reload  - re-read config.json, grants.json and cosmetics/");
            }
        }
        return out;
    }

    /** Suggestions for the word being typed (args has at least one, possibly empty, word). */
    public List<String> complete(String[] args) {
        List<String> opts = new ArrayList<>();
        if (args.length <= 1) opts.addAll(List.of("mode", "list", "give", "take", "install", "remove", "wearing", "reload", "help"));
        else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 2) {
                switch (sub) {
                    case "mode" -> opts.addAll(List.of("own", "server"));
                    case "give", "take", "wearing" -> opts.addAll(p.onlineNames());
                    case "remove" -> opts.addAll(cosmetics.keySet());
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
