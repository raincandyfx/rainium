package io.github.raincandyfx.rainium;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Installs a downloaded update after the game has closed. Runs as its own small Java program
 * (started by {@link Updater}); uses only the JDK.
 *
 * Args: gamePid oldJar newJar. Waits for the game process to end, deletes the old jar, then moves
 * the new one into the same folder. If the old jar can't be removed, the new one is left where it
 * is so the mods folder never ends up with two copies (and if the old one was already gone, it is
 * restored from the copy this program runs from); the next launch tries again.
 */
public final class UpdateSwap {
    private UpdateSwap() {}

    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        Path oldJar = Path.of(args[1]), newJar = Path.of(args[2]);
        log("waiting for the game (pid " + pid + ") to close");
        Optional<ProcessHandle> game = ProcessHandle.of(pid);
        if (game.isPresent()) {
            try { game.get().onExit().get(10, TimeUnit.MINUTES); }
            catch (Exception e) { log("gave up waiting: " + e); return; }
        }
        System.exit(swap(oldJar, newJar) ? 0 : 1);
    }

    /** Replaces oldJar with newJar (moved next to it). Retries for a while: Windows can hold files briefly. */
    static boolean swap(Path oldJar, Path newJar) throws InterruptedException {
        if (!Files.isRegularFile(newJar)) { log("nothing to install"); return false; }
        Path dest = oldJar.resolveSibling(newJar.getFileName().toString());
        for (int i = 0; i < 40; i++) {
            try {
                if (!dest.equals(oldJar)) Files.deleteIfExists(oldJar);
                Files.move(newJar, dest, StandardCopyOption.REPLACE_EXISTING);
                log("installed " + dest.getFileName());
                return true;
            } catch (Exception e) {
                if (i == 39) {
                    log("couldn't replace the old jar: " + e);
                    restore(oldJar);
                    return false;
                }
                Thread.sleep(250);
            }
        }
        return false;
    }

    /** If the old jar was already deleted but the new one couldn't be moved in, put the old one back
     *  (this program runs from a copy of it), so the mod doesn't disappear. */
    private static void restore(Path oldJar) {
        if (Files.exists(oldJar)) return;
        try {
            Path self = Path.of(UpdateSwap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(self)) { Files.copy(self, oldJar); log("put the old jar back"); }
        } catch (Exception e) {
            log("couldn't put the old jar back: " + e);
        }
    }

    private static void log(String s) { System.out.println(LocalDateTime.now() + " " + s); }
}
