package io.github.raincandyfx.rainium;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The first-run tour and the "what's new" popup. Both start on the title screen (see TutorialHooks); the tour then
 * follows the player into Mods > Rainium and through the editor. This class holds the state, the saved flags and
 * draws the overlay (dimmed screen with holes, numbered pins and one card with the progress and the buttons);
 * it knows nothing about where the highlighted things are: the screen it is drawn on hands over their rectangles
 * ("marks", as {x, y, w, h}).
 */
final class Tutorial {
    private Tutorial() {}

    /** What the tour may ask of the editor it is drawn in. Title-screen hosts only implement openEditor. */
    interface Host {
        void tab(String name);          // "edit", "add" or "settings"
        void ensureHalo();              // add the Gold halo if the player has none
        void selectHalo();
        void ensureParticle();          // add a particle starter if the player has none, and select one
        void openEditor();
    }

    private record Step(int num, String id, String tab, String[] spots, String[] pins, String focus, String enter) {}

    /** The tour. Several entries can share a number: it shows as one step. */
    private static final int TOTAL = 7;
    private static final Step[] STEPS = {
        new Step(1, "open", null, new String[]{"mods", "configure"}, new String[]{}, null, null),
        new Step(2, "tour", "edit", new String[]{"tabs", "layers", "panel"}, new String[]{"tabs:1", "layers:2", "preview:3", "panel:4"}, null, null),
        new Step(3, "own", "add", new String[]{"yourmodels"}, new String[]{}, "yourmodels", null),
        new Step(3, "halo", "add", new String[]{"preset_halo"}, new String[]{}, "preset_halo", null),
        new Step(4, "shape", "edit", new String[]{"panel"}, new String[]{}, null, "selectHalo"),
        new Step(5, "layers", "edit", new String[]{"layers", "undoredo"}, new String[]{"eye:1", "fold:2", "updown:3", "undoredo:4"}, null, null),
        new Step(6, "particles", "add", new String[]{"starters"}, new String[]{}, "starters", null),
        new Step(6, "hue", "edit", new String[]{"hue", "tint"}, new String[]{}, "hue", "ensureParticle"),
        new Step(7, "settings", "settings", new String[]{"panel"}, new String[]{}, null, null),
    };

    private enum Mode { NONE, INVITE, NEWS, TOUR }

    private static final int INK = 0xFF050B33, SEA = 0xFF1436C9, CYAN = 0xFF48E6FF, PAPER = 0xFFFFFFFF, TEXT = 0xFFF4F7FF,
        HAZE = 0xFFC9D8FF, EMPH = 0xFF8FF0FF, FAINT = 0xFF8590C4, CARD = 0xF4050B33, DIM = 0xA6030826;

    private static Mode mode = Mode.NONE;
    private static int idx;
    private static boolean confirm, promptedThisSession;
    private static int entered = -1;           // the step whose tab/enter actions have run
    private static boolean focusDone;
    private static Host host;

    // ------------------------------------------------------------------ saved flags
    // Kept in the rainium folder (not in layers.json, so Undo can't bring the tour back, and updates never touch it).
    private static Path stateFile;
    private static boolean done;
    private static String newsSeen = "";

    static void init(Path dir) {
        stateFile = dir.resolve("tutorial.state");
        try {
            if (Files.isRegularFile(stateFile)) {
                for (String line : Files.readString(stateFile, StandardCharsets.UTF_8).split("\\R")) {
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                    if (k.equals("done")) done = v.equals("true");
                    else if (k.equals("news_seen")) newsSeen = v;
                }
            }
        } catch (IOException | RuntimeException e) {
            Assets.LOG.warn("[rainium] could not read the tutorial flag: " + e);
        }
    }

    private static void save() {
        if (stateFile == null) return;
        try {
            Files.writeString(stateFile, "# Rainium: set done=false to see the tutorial again (or use Settings > Replay tutorial)\ndone=" + done + "\nnews_seen=" + newsSeen + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            Assets.LOG.warn("[rainium] could not save the tutorial flag: " + e);
        }
    }

    /** The mod's version without the Minecraft part ("0.8.0+26.3" gives "0.8.0"), or null. */
    private static String version() {
        try {
            String v = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("rainium")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
            return v == null ? null : v.split("[+-]", 2)[0];
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ entry points
    /** Called when the title screen opens: the first launch gets the invitation, a new main release (x.y.0) its popup. */
    static void onTitle() {
        if (promptedThisSession) return;
        promptedThisSession = true;
        String v = version();
        if (!done) {
            mode = Mode.INVITE;
        } else if (v != null && Updater.isStable(v) && !v.equals(newsSeen)) {
            mode = Mode.NEWS;
            newsSeen = v;
            save();
        }
    }

    /** Does the overlay want this kind of screen right now? */
    static boolean wants(boolean title, boolean mods) {
        if (title && (mode == Mode.INVITE || mode == Mode.NEWS)) return true;
        return (title || mods) && mode == Mode.TOUR && idx == 0;
    }

    static boolean active() { return mode == Mode.TOUR; }

    /** The editor opened: step 1 (finding it) is over. */
    static void editorOpened() {
        if (mode == Mode.TOUR && idx == 0) go(1);
    }

    /** Settings > Replay tutorial, from inside the editor. */
    static void replay() {
        mode = Mode.TOUR;
        confirm = false;
        go(1);
    }

    static void presetAdded(String id) {
        if (mode == Mode.TOUR && STEPS[idx].id().equals("halo") && id.equals("halo")) go(idx + 1);
    }

    static void particleAdded() {
        if (mode == Mode.TOUR && STEPS[idx].id().equals("particles")) go(idx + 1);
    }

    /** The mark the current step wants scrolled into view, or null. */
    static String pendingFocus() {
        if (mode != Mode.TOUR || focusDone) return null;
        return STEPS[idx].focus();
    }

    static void focusDone() { focusDone = true; }

    private static void go(int i) {
        idx = Math.max(0, Math.min(STEPS.length - 1, i));
        confirm = false;
        focusDone = false;
        entered = -1;
    }

    private static void next() {
        Step st = STEPS[idx];
        if (idx == STEPS.length - 1) { finish(); return; }
        if (st.id().equals("open")) { if (host != null) host.openEditor(); return; }
        int before = idx;
        if (host != null) {
            if (st.id().equals("halo")) host.ensureHalo();
            else if (st.id().equals("particles")) host.ensureParticle();
        }
        if (idx == before) go(idx + 1);   // adding it may already have moved the tour on
    }

    private static void finish() { mode = Mode.NONE; confirm = false; }

    private static void answerInvite(boolean start) {
        done = true;
        String v = version();
        if (v != null && Updater.isStable(v)) newsSeen = v;
        save();
        if (start) { mode = Mode.TOUR; go(0); } else mode = Mode.NONE;
    }

    // ------------------------------------------------------------------ input
    private record Btn(int x, int y, int w, int h, Runnable run) {}
    private static final List<Btn> btns = new ArrayList<>();
    private static int[] cardRect = {0, 0, 0, 0};
    private static final List<int[]> holes = new ArrayList<>();
    private static boolean blocking;

    /** Returns true when the click was for the overlay (a button, the card, or the dimmed area outside the holes). */
    static boolean click(double mx, double my) {
        if (mode == Mode.NONE) return false;
        for (int i = btns.size() - 1; i >= 0; i--) {
            Btn b = btns.get(i);
            if (in(mx, my, b.x, b.y, b.w, b.h)) { b.run.run(); return true; }
        }
        if (in(mx, my, cardRect[0], cardRect[1], cardRect[2], cardRect[3])) return true;
        return blocks(mx, my);
    }

    /** Scroll, drag and the like: swallowed where a click would be. */
    static boolean blocks(double mx, double my) {
        if (mode == Mode.NONE) return false;
        if (!blocking) return false;
        for (int[] h : holes) if (in(mx, my, h[0] - 2, h[1] - 2, h[2] + 4, h[3] + 4)) return false;
        return true;
    }

    /** Esc: asks to leave the tour (or dismisses a popup). Other keys are swallowed when swallowOthers. */
    static boolean key(int keyCode, boolean swallowOthers) {
        if (mode == Mode.NONE) return false;
        if (RainiumScreen.isEsc(keyCode)) {
            if (mode == Mode.TOUR) confirm = !confirm;
            else if (mode == Mode.INVITE) answerInvite(false);
            else mode = Mode.NONE;
            return true;
        }
        return swallowOthers;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ drawing
    /** Draws the overlay on top of everything else. marks: {x, y, w, h} by name. blockOutside: clicks outside the holes are swallowed. */
    static void draw(GuiGraphicsExtractor g, Font font, int W, int H, int mx, int my, Map<String, int[]> marks, Host h, boolean blockOutside) {
        if (mode == Mode.NONE) return;
        host = h;
        btns.clear();
        holes.clear();
        Step st = mode == Mode.TOUR ? STEPS[idx] : null;

        if (st != null && entered != idx) {   // first frame of a step: bring the editor to what it shows
            entered = idx;
            if (h != null) {
                if (st.tab() != null) h.tab(st.tab());
                if ("selectHalo".equals(st.enter())) h.selectHalo();
                else if ("ensureParticle".equals(st.enter())) h.ensureParticle();
            }
        }
        if (st != null && !confirm) for (String s : st.spots()) {
            int[] m = marks.get(s);
            if (m != null && m[2] > 0 && m[3] > 0) holes.add(m);
        }
        boolean modal = mode != Mode.TOUR || confirm;
        blocking = modal || (blockOutside && !holes.isEmpty());

        if (modal) g.fill(0, 0, W, H, DIM);
        else if (blockOutside && !holes.isEmpty()) dim(g, W, H, holes);
        for (int[] m : holes) outline(g, m[0] - 1, m[1] - 1, m[2] + 2, m[3] + 2, CYAN);

        if (st != null && !confirm) for (String p : st.pins()) {
            int c = p.indexOf(':');
            int[] m = marks.get(p.substring(0, c));
            if (m != null && m[2] > 0 && m[3] > 0) pin(g, font, m, H, p.substring(c + 1));
        }
        card(g, font, W, H, mx, my, marks, st);
    }

    private static void dim(GuiGraphicsExtractor g, int W, int H, List<int[]> hs) {
        TreeSet<Integer> ys = new TreeSet<>(List.of(0, H));
        for (int[] m : hs) { ys.add(Math.max(0, Math.min(H, m[1]))); ys.add(Math.max(0, Math.min(H, m[1] + m[3]))); }
        Integer prev = null;
        for (int y : ys) {
            if (prev != null && y > prev) {
                int y0 = prev, y1 = y;
                List<int[]> row = new ArrayList<>();
                for (int[] m : hs) if (m[1] <= y0 && m[1] + m[3] >= y1) row.add(new int[]{Math.max(0, m[0]), Math.min(W, m[0] + m[2])});
                row.sort((a, b) -> Integer.compare(a[0], b[0]));
                int x = 0;
                for (int[] r : row) {
                    if (r[0] > x) g.fill(x, y0, r[0], y1, DIM);
                    x = Math.max(x, r[1]);
                }
                if (x < W) g.fill(x, y0, W, y1, DIM);
            }
            prev = y;
        }
    }

    private static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }

    private static void pin(GuiGraphicsExtractor g, Font font, int[] m, int H, String label) {
        int cx, cy;
        if (m[3] <= 20) {   // a small control: the pin sits just below it (above, at the bottom edge)
            cx = m[0] + Math.min(m[2] / 2, 8);
            cy = m[1] + m[3] + 8;
            if (cy + 8 > H) cy = m[1] - 8;
        } else {
            cx = m[0] + 9;
            cy = m[1] + 9;
        }
        disc(g, cx, cy, 7.5f, INK);   // a dark blue outline
        disc(g, cx, cy, 6, CYAN);
        int w = font.width(RainiumScreen.styled(label, true, true));
        g.text(font, RainiumScreen.styled(label, true, true), cx - w / 2, cy - 4, INK, false);
    }

    private static float guiScale() {
        try { return Math.max(1f, (float) Minecraft.getInstance().getWindow().getGuiScale()); } catch (Throwable t) { return 1f; }
    }

    /** Drawn in real screen pixels, row by row, so round and slanted shapes stay crisp at any GUI scale. */
    private interface Span { float[] at(float y); }

    private static void shape(GuiGraphicsExtractor g, float top, float bottom, Span span, int color) {
        float s = guiScale();
        int r0 = (int) Math.floor(top * s), r1 = (int) Math.ceil(bottom * s);
        g.pose().pushMatrix();
        g.pose().scale(1f / s, 1f / s);
        for (int r = r0; r < r1; r++) {
            float[] e = span.at((r + 0.5f) / s);
            if (e == null) continue;
            int a = Math.round(e[0] * s), b = Math.round(e[1] * s);
            if (b > a) g.fill(a, r, b, r + 1, color);
        }
        g.pose().popMatrix();
    }

    private static void disc(GuiGraphicsExtractor g, float cx, float cy, float r, int color) {
        shape(g, cy - r, cy + r, y -> {
            float dy = y - cy, d = r * r - dy * dy;
            if (d <= 0) return null;
            float hw = (float) Math.sqrt(d);
            return new float[]{cx - hw, cx + hw};
        }, color);
    }

    private static void slant(GuiGraphicsExtractor g, int x, int y, int w, int h, int cut, int color) {
        shape(g, y, y + h, yy -> new float[]{x, x + w - cut * (yy - y) / h}, color);
    }

    // ------------------------------------------------------------------ the card
    private record Seg(String t, boolean bold, int x) {}

    private static net.minecraft.network.chat.Component face(String s, boolean bold) {
        return RainiumScreen.body(s);   // emphasis is a colour, not Minecraft's bold: that draws custom fonts twice and leaves gaps
    }

    /** Word-wraps text with **bold** parts. Lines are lists of segments with x offsets from the line start. */
    private static List<List<Seg>> wrap(Font font, String text, int maxW) {
        List<List<Seg>> lines = new ArrayList<>();
        List<Seg> cur = new ArrayList<>();
        int space = Math.max(2, font.width(RainiumScreen.body(" ")));
        int[] x = {0};
        boolean bold = false, gap = false;
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            boolean end = i == text.length();
            char c = end ? ' ' : text.charAt(i);
            boolean toggle = !end && c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '*';
            if (end || c == ' ' || toggle) {
                if (word.length() > 0) {   // place the word; a space before it only if the text had one
                    String w = word.toString();
                    int ww = font.width(face(w, bold)), sp = gap && x[0] > 0 ? space : 0;
                    if (x[0] > 0 && x[0] + sp + ww > maxW) { lines.add(cur); cur = new ArrayList<>(); x[0] = 0; sp = 0; }
                    cur.add(new Seg(w, bold, x[0] + sp));
                    x[0] += sp + ww;
                    word.setLength(0);
                    gap = false;
                }
                if (c == ' ' && !end) gap = true;
                if (toggle) { bold = !bold; i++; }
            } else {
                word.append(c);
            }
        }
        if (!cur.isEmpty() || lines.isEmpty()) lines.add(cur);
        return lines;
    }

    private static String t(String id, String part, Object... args) {
        return Lang.t("tutorial." + id + "." + part, args);
    }

    private static void card(GuiGraphicsExtractor g, Font font, int W, int H, int mx, int my, Map<String, int[]> marks, Step st) {
        int cw = Math.min(214, W - 12);
        int pad = 8, tw = cw - pad * 2, LH = 10;

        String id, tag, title;
        String[] paras;
        String note = null;
        String primary, secondary = null;
        Runnable onPrimary, onSecondary = null;
        boolean tour = mode == Mode.TOUR && !confirm;
        String ver = version();
        if (confirm) {
            id = "skip";
            tag = t(id, "tag"); title = t(id, "title");
            paras = new String[]{Lang.t("tutorial.skip.body", st.num(), TOTAL)};
            note = t(id, "note");
            primary = Lang.t("tutorial.button.skip"); onPrimary = Tutorial::finish;
            secondary = Lang.t("tutorial.button.keep"); onSecondary = () -> confirm = false;
        } else if (mode == Mode.INVITE) {
            id = "invite";
            tag = t(id, "tag"); title = t(id, "title");
            paras = t(id, "body").split("\n");
            note = t(id, "note");
            primary = Lang.t("tutorial.button.start"); onPrimary = () -> answerInvite(true);
            secondary = Lang.t("tutorial.button.later"); onSecondary = () -> answerInvite(false);
        } else if (mode == Mode.NEWS) {
            id = "news";
            tag = t(id, "tag"); title = t(id, "title", ver == null ? "" : ver);
            paras = t(id, "body", ver == null ? "" : ver).split("\n");
            primary = Lang.t("tutorial.button.gotit"); onPrimary = () -> mode = Mode.NONE;
            secondary = Lang.t("tutorial.button.tour"); onSecondary = () -> { mode = Mode.TOUR; go(0); };
        } else {
            id = st.id();
            tag = t(id, "tag"); title = t(id, "title");
            paras = t(id, "body").split("\n");
            note = Lang.optional("tutorial." + id + ".note");
            boolean last = idx == STEPS.length - 1;
            primary = Lang.t(id.equals("open") ? "tutorial.button.open" : last ? "tutorial.button.finish" : "tutorial.button.next");
            onPrimary = Tutorial::next;
            if (idx > 0) { secondary = Lang.t("tutorial.button.back"); final int prev = idx - 1; onSecondary = () -> go(prev); }
        }

        // lay the text out to know the card's height
        List<List<List<Seg>>> body = new ArrayList<>();
        List<Boolean> bullet = new ArrayList<>();
        int textH = 0;
        for (String p : paras) {
            boolean b = p.startsWith("- ");
            String s = b ? p.substring(2) : p;
            List<List<Seg>> ls = wrap(font, s, tw - (b ? 7 : 0));
            body.add(ls);
            bullet.add(b);
            textH += ls.size() * LH + 3;
        }
        List<List<Seg>> noteLines = note == null ? List.of() : wrap(font, note, tw);
        int h = pad + 12 + 6 + (tour ? 10 : 0) + 13 + 3 + textH + (noteLines.isEmpty() ? 0 : noteLines.size() * LH + 3) + 5 + 17 + pad;

        // where: beside the highlighted things, not on top of them
        int x = (W - cw) / 2, y = (H - h) / 2;
        if (tour && !holes.isEmpty()) {
            int[][] cand = new int[holes.size() * 4 + 2][];
            int k = 0;
            for (int[] m : holes) {
                cand[k++] = new int[]{m[0] + m[2] + 8, Math.max(m[1], 8)};
                cand[k++] = new int[]{m[0] - cw - 8, Math.max(m[1], 8)};
                cand[k++] = new int[]{m[0] + (m[2] - cw) / 2, m[1] + m[3] + 8};
                cand[k++] = new int[]{m[0] + (m[2] - cw) / 2, m[1] - h - 8};
            }
            int[] pv = marks.get("preview");
            cand[k++] = pv != null ? new int[]{pv[0] + (pv[2] - cw) / 2, pv[1] + pv[3] - h - 28} : new int[]{x, y};
            cand[k] = new int[]{x, H - h - 8};
            int[] pick = null;
            long best = Long.MAX_VALUE;
            for (int[] c : cand) {   // the first spot that clears every highlight; failing that, the one that covers the least
                if (c == null) continue;
                int cx = Math.max(6, Math.min(W - cw - 6, c[0])), cy = Math.max(6, Math.min(H - h - 6, c[1]));
                long cover = 0;
                for (int[] m : holes) {
                    long ox = Math.min(cx + cw, m[0] + m[2] + 4) - Math.max(cx, m[0] - 4), oy = Math.min(cy + h, m[1] + m[3] + 4) - Math.max(cy, m[1] - 4);
                    if (ox > 0 && oy > 0) cover += ox * oy;
                }
                if (cover < best) { best = cover; pick = new int[]{cx, cy}; }
                if (cover == 0) break;
            }
            if (pick == null) pick = new int[]{x, y};
            x = pick[0]; y = pick[1];
        } else if (tour) {
            y = Math.max(6, H - h - 14);   // nothing to point at (yet): along the bottom
        }
        x = Math.max(6, Math.min(W - cw - 6, x));
        y = Math.max(6, Math.min(H - h - 6, y));
        cardRect = new int[]{x, y, cw, h};

        g.fill(x, y, x + cw, y + h, CARD);
        outline(g, x, y, cw, h, 0xE0FFFFFF);
        g.fill(x, y, x + 2, y + h, CYAN);

        int cy = y + pad;
        // progress row: Step n / 7, the bar, and the skip chip
        if (tour) {
            String step = Lang.t("tutorial.step", st.num(), TOTAL).toUpperCase(Locale.ROOT);
            g.text(font, RainiumScreen.styled(step, true, true), x + pad, cy + 1, CYAN, false);
            String skip = Lang.t("tutorial.button.skip_chip").toUpperCase(Locale.ROOT);
            int sw = font.width(RainiumScreen.styled(skip, true, true)) + 10, sx = x + cw - pad - sw;
            boolean sh = in(mx, my, sx, cy - 1, sw, 12);
            slant(g, sx, cy - 1, sw, 12, 3, sh ? CYAN : PAPER);
            g.text(font, RainiumScreen.styled(skip, true, true), sx + 5, cy + 1, INK, false);
            btns.add(new Btn(sx, cy - 1, sw, 12, () -> confirm = true));
            int bx = x + pad + font.width(RainiumScreen.styled(step, true, true)) + 8, bw = sx - 6 - bx;
            int seg = Math.max(4, (bw - (TOTAL - 1) * 2) / TOTAL);
            for (int i = 0; i < TOTAL; i++) {
                int sx0 = bx + i * (seg + 2);
                g.fill(sx0, cy + 3, sx0 + seg, cy + 6, i + 1 < st.num() ? CYAN : i + 1 == st.num() ? PAPER : 0x55FFFFFF);
            }
        } else {
            g.text(font, RainiumScreen.styled(tag.toUpperCase(Locale.ROOT), true, true), x + pad, cy + 1, CYAN, false);
        }
        cy += 12 + 6;
        if (tour) {
            g.text(font, RainiumScreen.styled(tag.toUpperCase(Locale.ROOT), true, true), x + pad, cy, CYAN, false);
            cy += 10;
        }
        g.text(font, RainiumScreen.styled(fit(font, title.toUpperCase(Locale.ROOT), tw), true, true), x + pad, cy, PAPER, false);
        cy += 13 + 3;
        for (int i = 0; i < body.size(); i++) {
            boolean b = bullet.get(i);
            if (b) g.fill(x + pad + 1, cy + 3, x + pad + 4, cy + 6, CYAN);
            for (List<Seg> line : body.get(i)) {
                for (Seg s : line) g.text(font, face(s.t, s.bold), x + pad + (b ? 7 : 0) + s.x, cy, s.bold ? EMPH : TEXT, false);
                cy += LH;
            }
            cy += 3;
        }
        for (List<Seg> line : noteLines) {
            for (Seg s : line) g.text(font, face(s.t, s.bold), x + pad + s.x, cy, s.bold ? EMPH : HAZE, false);
            cy += LH;
        }
        if (!noteLines.isEmpty()) cy += 3;

        // buttons along the bottom: [Skip tutorial link] ... [secondary] [primary]
        int by = y + h - pad - 15;
        int rx = x + cw - pad;
        String pl = primary.toUpperCase(Locale.ROOT);
        int pw = font.width(RainiumScreen.styled(pl, true, true)) + 16;
        boolean ph = in(mx, my, rx - pw, by, pw, 15);
        slant(g, rx - pw, by, pw, 15, 4, ph ? 0xFFA6F5FF : CYAN);
        g.text(font, RainiumScreen.styled(pl, true, true), rx - pw + 7, by + 4, INK, false);
        final Runnable p1 = onPrimary;
        btns.add(new Btn(rx - pw, by, pw, 15, p1));
        rx -= pw + 5;
        if (secondary != null) {
            String sl = secondary.toUpperCase(Locale.ROOT);
            int sw = font.width(RainiumScreen.styled(sl, true, true)) + 16;
            boolean sh = in(mx, my, rx - sw, by, sw, 15);
            slant(g, rx - sw, by, sw, 15, 4, sh ? 0xFF2D52E0 : SEA);
            g.text(font, RainiumScreen.styled(sl, true, true), rx - sw + 7, by + 4, PAPER, false);
            final Runnable p2 = onSecondary;
            btns.add(new Btn(rx - sw, by, sw, 15, p2));
        }
        if (tour) {
            String link = Lang.t("tutorial.button.skip_link");
            int lw = font.width(RainiumScreen.body(link));
            boolean lh = in(mx, my, x + pad - 1, by + 2, lw + 2, 12);
            g.text(font, RainiumScreen.body(link), x + pad, by + 4, lh ? PAPER : FAINT, false);
            btns.add(new Btn(x + pad - 1, by + 2, lw + 2, 12, () -> confirm = true));
        }
    }

    private static String fit(Font font, String s, int w) {
        if (font.width(RainiumScreen.styled(s, true, true)) <= w) return s;
        int n = s.length();
        while (n > 0 && font.width(RainiumScreen.styled(s.substring(0, n) + "…", true, true)) > w) n--;
        return s.substring(0, n) + "…";
    }
}
