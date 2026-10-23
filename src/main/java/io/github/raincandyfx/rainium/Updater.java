package io.github.raincandyfx.rainium;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Keeps the mod up to date from the GitHub releases of raincandyfx/rainium.
 *
 * Once per launch (when enabled) it looks for the newest release with a jar built for the running
 * Minecraft version (named rainium-&lt;version&gt;+&lt;minecraft&gt;.jar), downloads it into
 * config/rainium/update/, checks it, and swaps it into the mods folder after the game closes.
 * The swap runs in a small separate Java process, because Windows doesn't allow replacing a jar the
 * game still has open. Only player releases (versions ending in .0, e.g. 0.3.0) are installed:
 * dev builds (0.3.1, 0.3.2 ...) are published as pre-releases for testing and skipped here, as are
 * drafts. Nothing is ever downgraded.
 */
final class Updater {
    static final String REPO = "raincandyfx/rainium";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases?per_page=30";
    private static final Pattern ASSET = Pattern.compile("rainium-(\\d+(?:\\.\\d+)*)\\+(.+)\\.jar");

    enum State { IDLE, DISABLED, UNAVAILABLE, CHECKING, UP_TO_DATE, READY, FAILED }

    private static volatile State state = State.IDLE;
    private static volatile String detail = "";      // version for READY, reason for FAILED/UNAVAILABLE
    private static volatile boolean swapScheduled, swapCancelled;
    private static volatile Path pendingOld, pendingNew;    // what the exit hook installs
    private static volatile boolean includeDev;             // also take dev builds (pre-releases, x.y.1+)

    private Updater() {}

    static State state() { return state; }
    static String detail() { return detail; }

    /** A release jar for this Minecraft version that is newer than the running one. */
    record Pick(String version, String name, String url, long size, String sha256) {}

    // ------------------------------------------------------------------ pure logic (tested offline)

    /** Compares dotted version numbers ("1.10.0" > "1.9.2"); anything after '+' or '-' is ignored. */
    static int compare(String a, String b) {
        String[] x = a.split("[+-]", 2)[0].split("\\."), y = b.split("[+-]", 2)[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*", "")); } catch (NumberFormatException e) { return 0; }
    }

    /** Player releases end in .0 (0.2.0, 1.3.0); anything else (0.2.1, 1.3.4) is a dev build. */
    static boolean isStable(String version) {
        String[] p = version.split("[+-]", 2)[0].split("\\.");
        return p.length < 3 || num(p[2]) == 0;
    }

    /** From GitHub's release list, the newest jar for this Minecraft version that beats current, or null.
     *  Dev builds (pre-releases and x.y.1+ versions) only count when includeDev is on; drafts never do. */
    @SuppressWarnings("unchecked")
    static Pick choose(Object releases, String current, String minecraft, boolean includeDev) {
        if (!(releases instanceof List)) return null;
        Pick best = null;
        for (Object r : (List<Object>) releases) {
            if (!(r instanceof Map)) continue;
            Map<String, Object> rel = (Map<String, Object>) r;
            if (Boolean.TRUE.equals(rel.get("draft"))) continue;
            if (!includeDev && Boolean.TRUE.equals(rel.get("prerelease"))) continue;
            if (!(rel.get("assets") instanceof List)) continue;
            for (Object a : (List<Object>) rel.get("assets")) {
                if (!(a instanceof Map)) continue;
                Map<String, Object> asset = (Map<String, Object>) a;
                String name = String.valueOf(asset.get("name"));
                Matcher m = ASSET.matcher(name);
                if (!m.matches() || !m.group(2).equals(minecraft)) continue;
                String v = m.group(1);
                if (!includeDev && !isStable(v)) continue;   // x.y.1+ are dev builds; by default only x.y.0 releases
                if (compare(v, current) <= 0 || (best != null && compare(v, best.version()) <= 0)) continue;
                String digest = asset.get("digest") instanceof String d && d.startsWith("sha256:") ? d.substring(7) : null;
                long size = asset.get("size") instanceof Number n ? n.longValue() : -1;
                best = new Pick(v, name, String.valueOf(asset.get("browser_download_url")), size, digest);
            }
        }
        return best;
    }

    /** True if the file is a Rainium jar of the given version (so a bad download never reaches mods/). */
    @SuppressWarnings("unchecked")
    static boolean looksRight(Path jar, String version) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            var e = z.getEntry("fabric.mod.json");
            if (e == null) return false;
            try (InputStream in = z.getInputStream(e)) {
                Object o = Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!(o instanceof Map)) return false;
                Map<String, Object> m = (Map<String, Object>) o;
                return "rainium".equals(m.get("id")) && compare(String.valueOf(m.get("version")), version) == 0;
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ in the game

    /** Starts a background check (unless disabled). Safe to call more than once; one check runs at a time. */
    static synchronized void checkAsync(boolean enabled, boolean dev) {
        includeDev = dev;
        if (!enabled) { state = State.DISABLED; detail = ""; swapCancelled = true; return; }   // also drops a pending install
        if (state == State.CHECKING || state == State.READY) return;
        state = State.CHECKING;
        detail = "";
        Thread t = new Thread(Updater::check, "rainium-updater");
        t.setDaemon(true);
        t.start();
    }

    private static void check() {
        try {
            Path ownJar = ownJar();
            if (ownJar == null) { finish(State.UNAVAILABLE, "not running from a jar in the mods folder"); return; }
            FabricLoader fl = FabricLoader.getInstance();
            String current = fl.getModContainer("rainium").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
            String minecraft = fl.getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
            if (current == null || minecraft == null) { finish(State.UNAVAILABLE, "unknown version"); return; }

            Path dir = fl.getConfigDir().resolve("rainium/update");
            Files.createDirectories(dir);

            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(API))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Rainium-updater/" + current)
                .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) { finish(State.FAILED, "GitHub answered " + res.statusCode()); return; }

            Pick pick = choose(Json.parse(res.body()), current, minecraft, includeDev);
            if (pick == null) { finish(State.UP_TO_DATE, current); return; }

            Path target = dir.resolve(pick.name());
            if (!(Files.isRegularFile(target) && looksRight(target, pick.version()))) {
                Path part = dir.resolve(pick.name() + ".part");
                HttpResponse<Path> dl = http.send(HttpRequest.newBuilder(URI.create(pick.url()))
                    .header("User-Agent", "Rainium-updater/" + current)
                    .timeout(Duration.ofMinutes(2)).build(), HttpResponse.BodyHandlers.ofFile(part));
                if (dl.statusCode() != 200) { Files.deleteIfExists(part); finish(State.FAILED, "download answered " + dl.statusCode()); return; }
                if (pick.size() >= 0 && Files.size(part) != pick.size()) { Files.deleteIfExists(part); finish(State.FAILED, "download was incomplete"); return; }
                if (pick.sha256() != null && !pick.sha256().equalsIgnoreCase(sha256(part))) { Files.deleteIfExists(part); finish(State.FAILED, "download didn't match its checksum"); return; }
                if (!looksRight(part, pick.version())) { Files.deleteIfExists(part); finish(State.FAILED, "downloaded file isn't a Rainium jar"); return; }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // remove older downloads
            try (var ds = Files.newDirectoryStream(dir, "rainium-*.jar")) {
                for (Path p : ds) if (!p.equals(target)) Files.deleteIfExists(p);
            }
            scheduleSwap(ownJar, target);
            finish(State.READY, pick.version());
            Assets.LOG.info("[rainium] update {} downloaded; it will be installed when the game closes", pick.version());
        } catch (Exception e) {
            finish(State.FAILED, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    /** After the update settings change: drop any pending install and check again with the new settings. */
    static synchronized void recheck(boolean enabled, boolean dev) {
        if (state == State.CHECKING) { includeDev = dev; return; }
        swapCancelled = true;
        if (state == State.READY) state = State.IDLE;
        checkAsync(enabled, dev);
    }

    private static void finish(State s, String d) {
        state = s;
        detail = d == null ? "" : d;
        if (s == State.FAILED || s == State.UNAVAILABLE) Assets.LOG.warn(Privacy.redactForLog("[rainium] update check: " + detail));
    }

    /** The jar this mod was loaded from, if it's a plain .jar file (not a dev setup). */
    static Path ownJar() {
        try {
            var src = Updater.class.getProtectionDomain().getCodeSource();
            if (src == null) return null;
            Path p = Path.of(src.getLocation().toURI());
            return Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar") ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /**
     * When the game exits, start a separate Java process (running UpdateSwap from a copy of this jar)
     * that waits for the game to close and then replaces the old jar with the new one.
     */
    private static synchronized void scheduleSwap(Path oldJar, Path newJar) throws IOException {
        pendingOld = oldJar;
        pendingNew = newJar;
        swapCancelled = false;
        if (swapScheduled) return;   // the hook below always installs the latest pendingNew
        Path helper = newJar.resolveSibling("swap-helper.jar");
        Files.copy(oldJar, helper, StandardCopyOption.REPLACE_EXISTING);
        String java = ProcessHandle.current().info().command().orElse(null);
        if (java == null) throw new IOException("can't find the Java runtime");
        long pid = ProcessHandle.current().pid();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (swapCancelled || pendingNew == null) return;
            List<String> cmd = List.of(java, "-cp", helper.toString(), UpdateSwap.class.getName(),
                String.valueOf(pid), pendingOld.toString(), pendingNew.toString());
            try {
                new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(helper.resolveSibling("update.log").toFile()))
                    .start();
            } catch (IOException ignored) { }
        }, "rainium-update-swap"));
        swapScheduled = true;
    }
}
