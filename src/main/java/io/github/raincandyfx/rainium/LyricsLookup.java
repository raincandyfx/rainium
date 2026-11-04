package io.github.raincandyfx.rainium;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds timed lyrics for a song, in this order: your own .lrc file in config/rainium/lyrics,
 * then the copy saved from an earlier lookup, then LRCLIB (lrclib.net, a free lyrics site),
 * only if you allowed it in Settings. Only the title, artist, album and length are sent.
 */
final class LyricsLookup {
    enum Status { FOUND, NONE, PLAIN_ONLY, INSTRUMENTAL, OFFLINE, FAILED }

    record Found(Status status, String synced, String plain, String from) {}

    static final String SITE = "https://lrclib.net";
    private static final long MISS_DAYS_MS = 24L * 3600 * 1000;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Rainium lyrics lookup");
        t.setDaemon(true);
        return t;
    });
    private static HttpClient http;

    private LyricsLookup() {}

    // ------------------------------------------------------------------ pure helpers (tested)
    /** "Song (feat. X) - Remastered 2011" → "Song". Spotify-style extras confuse lyrics sites. */
    static String cleanTitle(String t) {
        if (t == null) return "";
        String s = t.replaceAll("(?i)\\s*[(\\[](feat\\.?|ft\\.?|featuring|with|from)\\s[^)\\]]*[)\\]]", "");
        String cut = s.replaceAll("\\s+-\\s+.*$", "");
        if (!cut.isBlank()) s = cut;
        return s.replace('’', '\'').replaceAll("\\s+", " ").strip();
    }

    /** "A, B & C" → "A". */
    static String firstArtist(String a) {
        if (a == null) return "";
        String s = a.split("(?i)\\s*(,|;|&|/|\\sfeat\\.?\\s|\\sft\\.?\\s|\\sfeaturing\\s|\\sx\\s|\\svs\\.?\\s)\\s*")[0];
        return s.strip();
    }

    /** The saved-copy name for a song. */
    static String key(String title, String artist) {
        String k = (firstArtist(artist) + "\u0001" + cleanTitle(title)).toLowerCase(Locale.ROOT);
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(k.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(k.hashCode());
        }
    }

    /** A safe file name: "Artist - Title". */
    static String fileName(String title, String artist) {
        String n = firstArtist(artist) + " - " + cleanTitle(title);
        return n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
    }

    /** One LRCLIB record → what it has. */
    static Found fromRecord(Object o, String from) {
        if (!(o instanceof Map<?, ?> m)) return new Found(Status.NONE, null, null, from);
        String synced = m.get("syncedLyrics") instanceof String s && !s.isBlank() ? s : null;
        String plain = m.get("plainLyrics") instanceof String s && !s.isBlank() ? s : null;
        if (synced != null) return new Found(Status.FOUND, synced, plain, from);
        if (Boolean.TRUE.equals(m.get("instrumental"))) return new Found(Status.INSTRUMENTAL, null, null, from);
        return new Found(plain != null ? Status.PLAIN_ONLY : Status.NONE, null, plain, from);
    }

    /** From LRCLIB search results: the timed one whose length is closest (within 3 s when the length is known). */
    static Object pickSearch(Object results, double length) {
        if (!(results instanceof List<?> list)) return null;
        Object best = null;
        double bestD = Double.MAX_VALUE;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m) || !(m.get("syncedLyrics") instanceof String s) || s.isBlank()) continue;
            double d = length > 0 && m.get("duration") instanceof Number n ? Math.abs(n.doubleValue() - length) : 0;
            if (length > 0 && d > 3) continue;
            if (d < bestD) { bestD = d; best = o; }
        }
        return best;
    }

    static String query(Map<String, String> q) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            if (b.length() > 0) b.append('&');
            b.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ lookup
    /** Looks a song up off the game thread. `online` = the player allowed LRCLIB. */
    static CompletableFuture<Found> find(Path dir, String title, String artist, String album, double length, boolean online) {
        return CompletableFuture.supplyAsync(() -> {
            try { return findNow(dir, title, artist, album, length, online); }
            catch (Exception e) {
                Assets.LOG.warn("[rainium] lyrics lookup failed: " + e);
                return new Found(Status.FAILED, null, null, "");
            }
        }, POOL);
    }

    static Found findNow(Path dir, String title, String artist, String album, double length, boolean online) throws Exception {
        Path lyrics = dir.resolve("lyrics");
        // 1. your own file
        for (String n : new String[]{fileName(title, artist), (artist + " - " + title).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip()}) {
            Path p = lyrics.resolve(n + ".lrc");
            if (Files.isRegularFile(p)) return new Found(Status.FOUND, Files.readString(p, StandardCharsets.UTF_8), null, "file");
        }
        // 2. a saved copy
        Path cache = lyrics.resolve("cache").resolve(key(title, artist) + ".json");
        if (Files.isRegularFile(cache)) {
            try {
                Object o = Json.parse(Files.readString(cache, StandardCharsets.UTF_8));
                if (o instanceof Map<?, ?> m) {
                    if (m.get("miss") instanceof Number t) {
                        if (System.currentTimeMillis() - t.longValue() < MISS_DAYS_MS) return new Found(Status.NONE, null, null, "saved");
                    } else {
                        Found f = fromRecord(m, "saved");
                        if (f.status() != Status.NONE) return f;
                    }
                }
            } catch (Exception ignored) { }   // a broken saved copy is looked up again
        }
        // 3. LRCLIB
        if (!online) return new Found(Status.OFFLINE, null, null, "");
        String t = cleanTitle(title), a = firstArtist(artist);
        Map<String, String> q = new LinkedHashMap<>();
        q.put("track_name", t);
        q.put("artist_name", a);
        q.put("album_name", album);
        if (length >= 1 && length <= 3600) q.put("duration", String.valueOf(Math.round(length)));
        Found f = fromRecord(get("/api/get?" + query(q)), "lrclib");
        if (f.status() != Status.FOUND) {
            q.remove("album_name");
            Found g = fromRecord(get("/api/get?" + query(q)), "lrclib");
            if (g.status() == Status.FOUND || f.status() == Status.NONE) f = g;
        }
        if (f.status() != Status.FOUND && f.status() != Status.INSTRUMENTAL) {
            Map<String, String> sq = new LinkedHashMap<>();
            sq.put("track_name", t);
            sq.put("artist_name", a);
            Object pick = pickSearch(get("/api/search?" + query(sq)), length);
            if (pick != null) f = fromRecord(pick, "lrclib");
        }
        save(cache, f);
        return f;
    }

    private static void save(Path cache, Found f) {
        try {
            Files.createDirectories(cache.getParent());
            Map<String, Object> m = new LinkedHashMap<>();
            if (f.status() == Status.NONE) m.put("miss", System.currentTimeMillis());
            else {
                if (f.synced() != null) m.put("syncedLyrics", f.synced());
                if (f.plain() != null) m.put("plainLyrics", f.plain());
                if (f.status() == Status.INSTRUMENTAL) m.put("instrumental", true);
            }
            Files.writeString(cache, JsonWriter.write(m), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Assets.LOG.warn("[rainium] couldn't save lyrics: " + e);
        }
    }

    /** GET from LRCLIB; null on "not found". Retries once when the site is busy. */
    private static Object get(String path) throws Exception {
        if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(SITE + path)).timeout(Duration.ofSeconds(10))
            .header("User-Agent", "Rainium/" + version() + " (https://github.com/raincandyfx/rainium)").GET().build();
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() == 200) return Json.parse(r.body());
            if (r.statusCode() == 404) return null;
            if (r.statusCode() == 503 || r.statusCode() == 429) { Thread.sleep(1200); continue; }
            throw new IllegalStateException("lrclib answered " + r.statusCode());
        }
        return null;
    }

    private static String version() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("rainium")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        } catch (Throwable t) { return "dev"; }
    }

    /** Deletes the saved copies (not your own .lrc files). Returns how many. */
    static int clearSaved(Path dir) {
        Path cache = dir.resolve("lyrics").resolve("cache");
        int n = 0;
        try (var s = Files.list(cache)) {
            for (Path p : (Iterable<Path>) s::iterator) if (p.toString().endsWith(".json")) { Files.deleteIfExists(p); n++; }
        } catch (Exception ignored) { }
        savedAt = 0;
        return n;
    }

    private static int savedCache;
    private static long savedAt;

    /** How many saved copies there are (checked at most once a second; the editor asks every frame). */
    static int savedCount(Path dir) {
        long now = System.nanoTime();
        if (now - savedAt < 1_000_000_000L && savedAt != 0) return savedCache;
        savedAt = now;
        try (var s = Files.list(dir.resolve("lyrics").resolve("cache"))) { savedCache = (int) s.filter(p -> p.toString().endsWith(".json")).count(); }
        catch (Exception e) { savedCache = 0; }
        return savedCache;
    }
}
