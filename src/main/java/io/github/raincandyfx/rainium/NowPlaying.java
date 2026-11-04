package io.github.raincandyfx.rainium;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * What's playing on this computer, asked about once a second on a background thread and
 * only while lyrics are switched on:
 *   Windows: the system media session (a small PowerShell script, any player),
 *   Linux:   playerctl (MPRIS, any player),
 *   macOS:   Spotify or Apple Music (osascript; asks for Automation permission once).
 * The song position keeps moving between answers by itself.
 */
final class NowPlaying {
    record Track(String title, String artist, String album, double length, double position, boolean playing, String player, long sampledNanos) {
        double positionNow() { return playing ? position + (System.nanoTime() - sampledNanos) / 1e9 : position; }
        String key() { return artist + "\u0001" + title; }
        boolean sameSong(Track o) { return o != null && key().equals(o.key()); }
    }

    /** Which player to listen to: "any", "spotify" or a name the player typed. */
    static volatile String filter = "any";
    static volatile Track current;
    /** Why nothing can be read, as a lang key suffix ("playerctl", "windows", "mac", "error"), or null. */
    static volatile String problem;
    static volatile boolean demo;   // the built-in demo song instead of the computer's player

    private static Thread thread;
    private static volatile boolean wanted;
    private static Process helper;
    private static volatile Track helperTrack;
    private static long demoStart;

    private NowPlaying() {}

    static synchronized void want(boolean on) {
        wanted = on;
        if (on && (thread == null || !thread.isAlive())) {
            thread = new Thread(NowPlaying::loop, "Rainium now playing");
            thread.setDaemon(true);
            thread.start();
        }
        if (!on) { stopHelper(); current = null; }
    }

    static void startDemo() { demo = true; demoStart = System.nanoTime(); }
    static void stopDemo() { demo = false; }

    /** The demo song's track, looping. */
    static Track demoTrack() {
        double t = ((System.nanoTime() - demoStart) / 1e9) % LyricsLrc.DEMO_LENGTH;
        return new Track(LyricsLrc.DEMO_TITLE, LyricsLrc.DEMO_ARTIST, "", LyricsLrc.DEMO_LENGTH, t, true, "demo", System.nanoTime());
    }

    static Track now() { return demo ? demoTrack() : current; }

    private static void loop() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        while (wanted) {
            try {
                if (demo) { Thread.sleep(500); continue; }
                Track t;
                if (os.contains("win")) t = windows();
                else if (os.contains("mac")) t = mac();
                else t = linux();
                current = t;
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                current = null;
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
        stopHelper();
    }

    // ------------------------------------------------------------------ Linux: playerctl
    private static final String SEP = "\u001f";

    private static Track linux() throws Exception {
        String players = switch (filter) { case "any" -> "spotify,%any"; case "spotify" -> "spotify"; default -> filter; };
        String fmt = String.join(SEP, "{{status}}", "{{xesam:title}}", "{{xesam:artist}}", "{{xesam:album}}", "{{mpris:length}}", "{{position}}", "{{playerName}}");
        List<String> out;
        try { out = run(List.of("playerctl", "-p", players, "metadata", "--format", fmt)); }
        catch (java.io.IOException e) { problem = "playerctl"; return null; }
        problem = null;
        if (out.isEmpty()) return null;
        String[] f = out.get(0).split(SEP, -1);
        if (f.length < 7 || f[1].isBlank() || f[0].equalsIgnoreCase("Stopped")) return null;
        double len = num(f[4]) / 1e6, pos = num(f[5]) / 1e6;
        return new Track(f[1], f[2], f[3], len, pos, f[0].equalsIgnoreCase("Playing"), f[6], System.nanoTime());
    }

    // ------------------------------------------------------------------ macOS: Spotify or Apple Music
    private static final String MAC_SCRIPT = String.join("\n",
        "set sep to character id 31",
        "if application \"Spotify\" is running then",
        "  tell application \"Spotify\"",
        "    if player state is not stopped then",
        "      set t to current track",
        "      return (player state as string) & sep & (name of t) & sep & (artist of t) & sep & (album of t) & sep & ((duration of t) / 1000 as string) & sep & (player position as string) & sep & \"spotify\"",
        "    end if",
        "  end tell",
        "end if",
        "if application \"Music\" is running then",
        "  tell application \"Music\"",
        "    if player state is not stopped then",
        "      set t to current track",
        "      return (player state as string) & sep & (name of t) & sep & (artist of t) & sep & (album of t) & sep & ((duration of t) as string) & sep & (player position as string) & sep & \"music\"",
        "    end if",
        "  end tell",
        "end if",
        "return \"\"");

    private static Track mac() throws Exception {
        List<String> out;
        try { out = run(List.of("osascript", "-e", MAC_SCRIPT)); }
        catch (java.io.IOException e) { problem = "mac"; return null; }
        problem = null;
        if (out.isEmpty() || out.get(0).isBlank()) return null;
        String[] f = out.get(0).split(SEP, -1);
        if (f.length < 7) return null;
        if (filter.equals("spotify") && !f[6].equals("spotify")) return null;
        return new Track(f[1], f[2], f[3], num(f[4]), num(f[5]), f[0].strip().equalsIgnoreCase("playing"), f[6], System.nanoTime());
    }

    // ------------------------------------------------------------------ Windows: the media session, through a helper script
    private static Track windows() throws Exception {
        if (helper == null || !helper.isAlive()) startHelper();
        return helperTrack;
    }

    private static void startHelper() throws Exception {
        Path dir = RainiumClient.ASSETS.dir().resolve("lyrics");
        Files.createDirectories(dir);
        Path script = dir.resolve("now_playing.ps1");
        try (InputStream in = NowPlaying.class.getResourceAsStream("/assets/rainium/lyrics/now_playing.ps1")) {
            if (in == null) { problem = "windows"; return; }
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        String want = switch (filter) { case "any" -> ""; case "spotify" -> "Spotify"; default -> filter; };
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden", "-File", script.toString(), "-ParentPid", String.valueOf(ProcessHandle.current().pid()), "-Filter", want);
        pb.redirectErrorStream(true);
        try { helper = pb.start(); }
        catch (java.io.IOException e) { problem = "windows"; return; }
        problem = null;
        Process p = helper;
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) helperTrack = parseHelper(line);
            } catch (Exception ignored) { }
        }, "Rainium now playing (Windows)");
        reader.setDaemon(true);
        reader.start();
    }

    /** One line from the Windows helper → a track (or null for "nothing playing"). */
    static Track parseHelper(String line) {
        try {
            if (!(Json.parse(line.strip()) instanceof Map<?, ?> m) || m.containsKey("none") || m.containsKey("error")) return null;
            String title = str(m.get("title"));
            if (title.isBlank()) return null;
            return new Track(title, str(m.get("artist")), str(m.get("album")), dbl(m.get("length")), dbl(m.get("position")),
                Boolean.TRUE.equals(m.get("playing")), str(m.get("app")), System.nanoTime());
        } catch (Exception e) {
            return null;
        }
    }

    private static synchronized void stopHelper() {
        if (helper != null) { helper.destroy(); helper = null; }
        helperTrack = null;
    }

    /** Restarts the helper so a new player choice applies. */
    static void filterChanged() { stopHelper(); current = null; }

    // ------------------------------------------------------------------ helpers
    private static List<String> run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        List<String> out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            out = r.lines().toList();
        }
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
        return out;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static double dbl(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    static double num(String s) {
        try { return Double.parseDouble(s.strip().replace(',', '.')); } catch (Exception e) { return 0; }
    }
}
