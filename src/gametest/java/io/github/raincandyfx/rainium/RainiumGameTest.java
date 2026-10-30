package io.github.raincandyfx.rainium;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the mod in the real game: a world with the halo, particles and a named armor stand, the
 * editor, a picture and a short recording. Writes results and pictures to rainium-test-out/.
 */
public class RainiumGameTest implements FabricClientGameTest {
    private final List<String> log = new ArrayList<>();
    private int fails;
    private Path out;
    private volatile String done;

    private void check(boolean ok, String what) {
        log.add((ok ? "ok   " : "FAIL ") + what);
        if (!ok) fails++;
    }

    private void shot(ClientGameTestContext ctx, String name) {
        try {
            Path p = ctx.takeScreenshot(name);
            Files.copy(p, out.resolve(name + ".png"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            log.add("screenshot " + name + " failed: " + t);
        }
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        out = FabricLoader.getInstance().getGameDir().resolve("rainium-test-out");
        try { Files.createDirectories(out); } catch (Exception ignored) {}
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getConnection().waitForChunksRender();
            check(RainiumClient.ASSETS.lastError() == null, "config loads (" + RainiumClient.ASSETS.lastError() + ")");

            // the halo on the player, seen from the front
            ctx.runOnClient(mc -> {
                mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                LayerConfig c = RainiumClient.ASSETS.editable();
                c.folder("Gold halo").enabled = true;
                c.armorStands = true;
                RainiumClient.ASSETS.save();
            });
            ctx.waitTicks(40);
            check(RainiumClient.ASSETS.lastError() == null && RainiumClient.ASSETS.editable().folder("Gold halo").enabled, "halo switched on and saved");
            shot(ctx, "1_halo");

            // particles
            ctx.runOnClient(mc -> {
                RainiumClient.ASSETS.editable().layers.add(ParticleStarters.make("sparkles", "Sparkles"));
                RainiumClient.ASSETS.save();
            });
            ctx.waitTicks(60);
            check(RainiumClient.ASSETS.lastError() == null, "particle layer loads");
            shot(ctx, "2_particles");

            // a named armor stand in front of the player
            sp.getServer().runCommand("execute as @a at @s run summon minecraft:armor_stand ^2 ^ ^4 {CustomName:\"Rainium\",ShowArms:1b,Rotation:[180f,0f]}");
            ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            ctx.waitTicks(40);
            shot(ctx, "3_armor_stand");

            // the editor, in a 1280 x 720 window: every tab at GUI scale 2 and 3, after the selection has settled
            ctx.getInput().resizeWindow(1280, 720);
            ctx.setScreen(RainiumScreen::new);
            ctx.waitTicks(30);
            shot(ctx, "4_editor");
            for (int scale : new int[]{2, 3}) {
                final int sc = scale;
                ctx.runOnClient(mc -> { mc.options.guiScale().set(sc); mc.resizeGui(); });
                for (String tb : new String[]{"EDIT", "ADD", "SETTINGS"}) {
                    ctx.runOnClient(mc -> setTab(mc.gui.screen(), tb));
                    ctx.waitTicks(12);
                    shot(ctx, "ui" + sc + "_" + tb.toLowerCase());
                }
            }
            // a row under the mouse (the white band), and a folder selected
            ctx.runOnClient(mc -> { mc.options.guiScale().set(2); mc.resizeGui(); setTab(mc.gui.screen(), "EDIT"); });
            ctx.getInput().setCursorPos(1280 * 0.86, 720 * 0.40);
            ctx.waitTicks(12);
            shot(ctx, "ui2_hover");
            ctx.getInput().setCursorPos(640, 700);
            ctx.runOnClient(mc -> {
                java.lang.reflect.Method m = RainiumScreen.class.getDeclaredMethod("selectFolder", String.class);
                m.setAccessible(true);
                m.invoke(mc.gui.screen(), "Gold halo");
            });
            ctx.waitTicks(12);
            shot(ctx, "ui2_folder");
            check(true, "editor screenshots taken");

            // typing into a field: letters, shift, number row, keypad, minus, Enter
            String[] typed = {null};
            ctx.runOnClient(mc -> {
                RainiumScreen scr = (RainiumScreen) mc.gui.screen();
                java.lang.reflect.Method begin = RainiumScreen.class.getDeclaredMethod("beginEdit", String.class, String.class, boolean.class, java.util.function.Consumer.class);
                begin.setAccessible(true);
                begin.invoke(scr, "test", "old", false, (java.util.function.Consumer<String>) v -> typed[0] = v);
                int[] keys = new int[]{4, 5, 30, 31, 89, 45, 44, 29, 42, 40};   // a b 1 2 keypad-1 - space z, then backspace, Enter
                for (int k : keys) scr.keyPressed(new net.minecraft.client.input.KeyEvent(k, 0, 0));
            });
            check("ab121- ".equals(typed[0]), "typing in the editor (got '" + typed[0] + "')");

            // a picture
            Path caps = RainiumClient.ASSETS.dir().resolve("captures");
            done = null;
            ctx.runOnClient(mc -> Recorder.picture(caps, 2, m -> done = m));
            int t = ctx.waitFor(mc -> done != null, 400);
            check(done != null && !done.startsWith("!"), "picture saved (" + done + ", " + t + " ticks)");
            if (done != null && !done.startsWith("!")) copy(caps.resolve(done), "5_picture.png");

            // a short recording, driven by the game clock
            done = null;
            boolean[] driven = {false};
            ctx.runOnClient(mc -> Recorder.start(caps, 1, 30, System.nanoTime(), m -> done = m));
            ctx.waitTicks(30);
            ctx.runOnClient(mc -> { driven[0] = Recorder.driven(); Recorder.stop(); });
            ctx.waitFor(mc -> done != null, 1200);
            check(driven[0], "recording drives the game clock (timer hook applied)");
            check(done != null && !done.startsWith("!"), "recording saved (" + done + ")");
            if (done != null && !done.startsWith("!")) {
                Path dir = caps.resolve(done);
                long n;
                try (var s = Files.list(dir)) { n = s.count(); } catch (Exception e) { n = -1; }
                check(n >= 10, "recording has frames (" + n + ")");
                copy(dir.resolve(Recorder.frameName(0)), "6_frame_first.png");
                copy(dir.resolve(Recorder.frameName((int) Math.max(0, n - 1))), "7_frame_last.png");
            }
            ctx.setScreen(() -> null);
            ctx.waitTicks(5);
        } catch (Throwable e) {
            check(false, "test crashed: " + e);
        }
        log.add(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        try { Files.write(out.resolve("results.txt"), log); } catch (Exception ignored) {}
        System.out.println("[rainium-test]\n" + String.join("\n", log));
    }

    private static void setTab(Object screen, String name) throws Exception {
        java.lang.reflect.Field f = RainiumScreen.class.getDeclaredField("tab");
        f.setAccessible(true);
        @SuppressWarnings({"unchecked", "rawtypes"}) Object v = Enum.valueOf((Class) f.getType(), name);
        f.set(screen, v);
    }

    private void copy(Path from, String name) {
        try { Files.copy(from, out.resolve(name), StandardCopyOption.REPLACE_EXISTING); }
        catch (Exception e) { log.add("copy " + name + " failed: " + e); }
    }
}
