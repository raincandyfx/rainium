package io.github.raincandyfx.rainium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.DoubleConsumer;
import java.util.function.Consumer;
import java.util.ArrayDeque;
import java.util.Deque;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;

/**
 * In-game editor. Layers on the left, a live preview of your player in the middle, and
 * Edit / Add / Settings on the right. Every change is saved to layers.json at once and
 * shows on the player straight away.
 *
 * Drawn from plain fills and text with a tiny immediate-mode widget set (no vanilla widget
 * classes); values change with buttons, arrows and the scroll wheel.
 */
public final class RainiumScreen extends Screen {
    /** Render state of the player drawn inside the editor, so the layer can tell it apart. */
    static volatile Object previewState;

    // ---- palette (ARGB): the website's (docs/index.html), after Persona 3 Reload's menus -------
    private static final int INK = 0xFF050B33, SEA = 0xFF1436C9, BRIGHT = 0xFF1F7CFF, CYAN = 0xFF48E6FF, MENU = 0xFF8FF0FF,
        HAZE = 0xFFC9D8FF, RED = 0xFFF2163A, PAPER = 0xFFFFFFFF;
    private static final int PANEL = 0xD8050B33, PANEL_EDGE = 0xE0FFFFFF, GLASS = 0x5C1436C9, RIBBON = 0xD81436C9;
    private static final int FIELD = 0xF2050B33, FIELD_HOVER = 0xFF1A2D86, FIELD_EDGE = 0xFF3C4C90;
    private static final int TEXT = 0xFFF4F7FF, MUTED = HAZE, FAINT = 0xFF8590C4;
    private static final int ACCENT = CYAN, ACCENT_HOVER = 0xFFA6F5FF, ACCENT_DIM = SEA, ACCENT_INK = INK;
    private static final int WARN = 0xFFFFC266, DANGER = 0xFFFF6A86, HOVER = 0x24FFFFFF;
    private static final int OVERLAY = 0xF4050B33;   // tooltip, toast and info boxes

    private static final int ROW = 14, GAP = 3, PAD = 7;
    private static final double[] STEPS = {0.01, 0.05, 0.25};
    private static final float[] ROT_STEPS = {1, 5, 15};
    private static final float[] HUE_STEPS = {5, 15, 45};
    private static final String[] STEP_NAMES = {"fine", "normal", "coarse"};

    private enum Tab { EDIT, ADD, SETTINGS }

    private record Hit(int x, int y, int w, int h, Runnable click, IntConsumer scroll) {}

    private final List<Hit> hits = new ArrayList<>();
    private final Assets assets = RainiumClient.ASSETS;
    private Tab tab = Tab.EDIT;
    private int selected = 0;
    private int confirmDelete = -1;
    private String selectedFolder;
    private boolean confirmFolderDelete;
    private boolean importAsFolder = true;
    private boolean keepConfirm;
    private int stepIndex = 1;
    private int rightScroll, rightContent, leftScroll;

    private String toast = "";
    private int toastColor = TEXT;
    private long toastUntil;

    // preview camera: yaw 0 = facing you, 180 = back view
    private float previewYaw = 160f, previewPitch = -8f, previewZoom = 1f;
    // pictures and recordings
    private static volatile String captureDone;   // set when saving finishes: file/folder name, or "!error"
    private static boolean captureWasRecording;
    private float spinFrom, recordAge0 = Float.NaN;
    private boolean dragging;

    // import state (Add tab)
    private final ObjImport.Options imp = new ObjImport.Options();
    private String impFile;
    private ObjImport.Parsed impParsed;
    private byte[] impPng;                                  // texture found inside a glTF/GLB being imported
    private final List<ObjImport.Warning> impNotes = new ArrayList<>();
    private ObjImport.Result impResult;
    private String impError;

    private long filesCheckedAt;
    private List<String> objFiles = List.of(), meshFiles = List.of(), pngFiles = List.of();
    private List<Presets.Info> presetList = List.of();
    private List<String> setZips = List.of();

    // text editing (rename / type exact numbers)
    private String editId;                 // field being edited, null when none
    private final StringBuilder editBuf = new StringBuilder();
    private boolean editReplace, editNumeric;
    private Consumer<String> editCommit;
    private int[] editRect;                // x, y, w, h of the active field (this frame)
    private DoubleConsumer nextExact;      // exact-value setter for the next stepper drawn

    // undo / redo of layers.json
    private final Deque<String> undoStack = new ArrayDeque<>(), redoStack = new ArrayDeque<>();
    private String currentSnapshot;
    private long lastUndoPush;
    private boolean scrolling, lastSaveWasScroll;   // scroll-wheel bursts merge into one undo step

    // tooltips
    private String tipCandidate, tipShown;
    private long tipSince;
    private int tipMx, tipMy;

    /** Style settings copied with "Copy style". */
    private static LayerConfig.Layer styleClipboard;

    /** Screen to go back to when the editor closes (e.g. the mod list), or null for the game. */
    private final Screen parent;

    public RainiumScreen() { this(null); }

    public RainiumScreen(Screen parent) {
        super(Component.translatable("rainium.name"));
        this.parent = parent;
        currentSnapshot = assets.snapshot();
    }

    private static boolean updateAnnounced;

    /** Puts text on the system clipboard (error messages, so they can be pasted into a bug report). */
    private void copyText(String text) {
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
            say(Lang.t("msg.copied"), MUTED);
        } catch (Throwable t) {
            say(Lang.t("msg.copy_failed"), DANGER);
        }
    }
    private static boolean showMotionDetails;

    private void close() {
        commitEdit();
        Recorder.stop();
        Recorder.cancel();
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // the game behind the editor, blurred like other menus (follows the Menu Background Blur setting),
        // under the website's water; the preview is drawn later, so it stays sharp
        boolean inWorld = Minecraft.getInstance().level != null;
        if (!inWorld) extractPanorama(g, partialTick);   // opened from the mod list: the menu panorama, not an empty screen
        extractBlurredBackground(g);
        if (inWorld) g.fillGradient(0, 0, width, height, 0x7010237F, 0xB8040A2E);
        else g.fillGradient(0, 0, width, height, 0x6022B8F0, 0x901436C9);   // brighter, like the site's daytime water
        caustics(g, inWorld ? 1f : 1.4f);
    }

    private static final Identifier CAUSTICS = Identifier.fromNamespaceAndPath("rainium", "textures/gui/caustics.png");

    /** Faint light from the water's surface, like the site's: two layers of a looping caustics animation
     *  (24 frames whose cells shift and re-form), each fading frame to frame and drifting a different way. */
    private void caustics(GuiGraphicsExtractor g, float strength) {
        float t = (System.currentTimeMillis() % 3_600_000L) / 1000f, gs = guiScale();
        causticLayer(g, 2.2f / gs, t * 4.0f, t * 2.2f, t / 9f, Math.round(0x26 * strength));
        causticLayer(g, 1.5f / gs, -t * 3.0f, t * 3.6f, t / 6.5f + 0.37f, Math.round(0x1A * strength));
    }

    private static final int CAUS_FRAMES = 24, CAUS_COLS = 6, CAUS_TILE = 256, CAUS_W = 1536, CAUS_H = 1024;

    /** One layer; scale = GUI units per texture pixel, phase = position in the animation loop (1 = once round). */
    private void causticLayer(GuiGraphicsExtractor g, float scale, float dx, float dy, float phase, int alpha) {
        float fpos = (((phase % 1f) + 1f) % 1f) * CAUS_FRAMES;
        int f0 = (int) fpos % CAUS_FRAMES, f1 = (f0 + 1) % CAUS_FRAMES;
        float mix = fpos - (int) fpos;
        causticTiles(g, scale, dx, dy, f0, Math.round(alpha * (1f - mix)));
        causticTiles(g, scale, dx, dy, f1, Math.round(alpha * mix));
    }

    private void causticTiles(GuiGraphicsExtractor g, float scale, float dx, float dy, int frame, int alpha) {
        if (alpha <= 0) return;
        int tile = CAUS_TILE;
        float ox = ((dx % tile) + tile) % tile, oy = ((dy % tile) + tile) % tile;
        float u = (frame % CAUS_COLS) * tile, v = (frame / CAUS_COLS) * tile;
        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        g.pose().translate(ox - tile, oy - tile);
        int cols = (int) Math.ceil(width / scale / tile) + 2, rows = (int) Math.ceil(height / scale / tile) + 2;
        int color = (Math.min(255, alpha) << 24) | 0xFFFFFF;
        for (int i = 0; i < cols; i++)
            for (int j = 0; j < rows; j++)
                g.blit(RenderPipelines.GUI_TEXTURED, CAUSTICS, i * tile, j * tile, u, v, tile, tile, tile, tile, CAUS_W, CAUS_H, color);
        g.pose().popMatrix();
    }

    // ------------------------------------------------------------------- tab change
    private Tab shownTab;
    private long tabSince;

    /** How far the tab-change transition is, 0 at the switch to 1 when done (eased out). */
    private float tabIn() {
        float p = Math.min(1f, (System.currentTimeMillis() - tabSince) / 240f), q = 1f - p;
        return 1f - q * q * q;
    }

    /** The current tab's name, huge and faded behind everything, like the game's CONFIG and ITEM screens. */
    private void watermark(GuiGraphicsExtractor g) {
        String word = Lang.t("tab." + tab.name().toLowerCase(Locale.ROOT)).toUpperCase(Locale.ROOT);
        float s = Math.max(3f, height * 0.34f / 9f);
        float e = tabIn();
        g.pose().pushMatrix();
        g.pose().translate(-6f - (1f - e) * 60f, height + s * 1.2f);
        g.pose().rotate(-0.07f);
        g.pose().scale(s, s);
        g.text(font, styled(word, true, true), 0, -9, (Math.round(0x17 * e) << 24) | 0xFFFFFF, false);
        g.pose().popMatrix();
    }

    // =================================================================== frame
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        super.extractRenderState(g, mx, my, pt);
        hits.clear();
        tipCandidate = null;
        String cap = captureDone;
        if (cap != null) {
            captureDone = null;
            if (cap.startsWith("!")) say(Lang.t("msg.capture_failed", cap.substring(1)), DANGER);
            else if (captureWasRecording) say(Lang.t("msg.recording_saved", Recorder.framesWritten(), "captures/" + cap + "/"), ACCENT);
            else say(Lang.t("msg.picture_saved", "captures/" + cap), ACCENT);
        }
        if (!updateAnnounced && Updater.state() == Updater.State.READY) {
            updateAnnounced = true;
            say(Lang.t("update.ready", Updater.detail()), ACCENT);
        }
        try {
            net.minecraft.world.entity.player.Player me = Self.previewPlayer(assets.editable());   // not the replay camera
            if (me != null) Privacy.ingameName = me.getName().getString();
        } catch (Throwable ignored) { }
        editRect = null;
        refreshFiles(false);
        if (!(tab == Tab.ADD && impFile != null)) assets.clearPreview();

        int W = width, H = height;
        int lw = clamp(W * 2 / 7, W < 380 ? 100 : 112, 156);
        int rw = clamp(W * 2 / 5, 158, 214);
        if (W - lw - rw - 24 < 80) rw = Math.max(136, W - lw - 24 - 80);
        int top = 30, bottom = H - 7;

        if (tab != shownTab) { shownTab = tab; tabSince = System.currentTimeMillis(); }
        watermark(g);
        header(g, mx, my, W);
        layersPanel(g, mx, my, 6, top, lw, bottom - top);
        sidePanel(g, mx, my, W - 6 - rw, top, rw, bottom - top);
        int pvx = 6 + lw + 6, pvw = W - lw - rw - 24;
        preview(g, mx, my, pvx, top, pvw, bottom - top);
        toast(g, pvx, pvw, bottom);
        errorBanner(g, mx, my, pvx, top, pvw);
        captureMenu(g, mx, my);
        tooltip(g, mx, my);
    }

    /** Config problems stay visible above the preview until fixed. */
    private void errorBanner(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w) {
        String err = assets.lastError();
        if (err == null) return;
        List<String> lines = wrapLines(err, w - 12, 3);
        int h = 16 + lines.size() * 10 + 4;
        g.fill(x, y, x + w, y + h, 0xF03A1838);
        g.fill(x, y, x + 2, y + h, DANGER);
        g.text(font, body(fit(Lang.t("error.banner"), w - 50)), x + 6, y + 4, DANGER, false);
        for (int i = 0; i < lines.size(); i++) g.text(font, body(lines.get(i)), x + 6, y + 15 + i * 10, TEXT, false);
        String rl = Lang.t("button.reload");
        int rw = font.width(body(rl)) + 8, rx = x + w - rw - 4;
        boolean hov = in(mx, my, rx, y + 3, rw, 11);
        g.fill(rx, y + 3, rx + rw, y + 14, hov ? FIELD_HOVER : FIELD);
        g.text(font, body(rl), rx + 4, y + 4, TEXT, false);
        hits.add(new Hit(rx, y + 3, rw, 11, () -> { assets.reload(); refreshFiles(true); currentSnapshot = assets.snapshot(); }, null));
        String cl = Lang.t("button.copy");
        int cw = font.width(body(cl)) + 8, cx = rx - cw - 3;
        boolean ch = in(mx, my, cx, y + 3, cw, 11);
        g.fill(cx, y + 3, cx + cw, y + 14, ch ? FIELD_HOVER : FIELD);
        g.text(font, body(cl), cx + 4, y + 4, TEXT, false);
        hits.add(new Hit(cx, y + 3, cw, 11, () -> copyText(err), null));
        tip(mx, my, cx, y + 3, cw, 11, "copy_error");
    }

    // =================================================================== tooltips

    /** Shows the tooltip "tip.&lt;id&gt;" (if there is one) while the mouse is over the area. */
    private void tip(double mx, double my, int x, int y, int w, int h, String id) {
        if (!in(mx, my, x, y, w, h)) return;
        String t = Lang.optional("tip." + id);
        if (t != null) tipCandidate = t;
    }

    private void tooltip(GuiGraphicsExtractor g, int mx, int my) {
        long now = System.currentTimeMillis();
        if (tipCandidate == null || dragging || editId != null) { tipShown = null; return; }
        if (!tipCandidate.equals(tipShown) || Math.abs(mx - tipMx) > 3 || Math.abs(my - tipMy) > 3) {
            tipShown = tipCandidate; tipSince = now; tipMx = mx; tipMy = my;
            return;
        }
        if (now - tipSince < 450) return;
        int maxW = Math.min(190, width - 20);
        List<String> lines = wrapLines(tipShown, maxW - 10, 6);
        int tw = 0;
        for (String ln : lines) tw = Math.max(tw, font.width(body(ln)));
        int bw = tw + 10, bh = lines.size() * 10 + 8;
        int bx = Math.min(mx + 10, width - bw - 4), by = my + 14;
        if (by + bh > height - 4) by = my - bh - 6;
        bx = Math.max(4, bx);
        g.fill(bx, by, bx + bw, by + bh, OVERLAY);
        g.outline(bx, by, bw, bh, PANEL_EDGE);
        for (int i = 0; i < lines.size(); i++) g.text(font, body(lines.get(i)), bx + 5, by + 5 + i * 10, TEXT, false);
    }

    // =================================================================== header
    private void header(GuiGraphicsExtractor g, int mx, int my, int W) {
        String title = Lang.t("name").toUpperCase(Locale.ROOT);
        g.text(font, styled(title, true, true), 8, 10, TEXT, false);

        // the tabs, as the site's menu: the open one gets the triangle
        int x = 8 + strongWidth(title) + 14;
        for (Tab t : Tab.values()) {
            String n = Lang.t("tab." + t.name().toLowerCase(Locale.ROOT)).toUpperCase(Locale.ROOT);
            int tw = strongWidth(n);
            boolean on = tab == t, hov = !on && in(mx, my, x - 3, 4, tw + 6, 18);
            if (on) selection(g, "tab", t, n, x, 10);
            else g.text(font, styled(n, true, true), x, 10, hov ? PAPER : MENU, false);
            hits.add(new Hit(x - 3, 4, tw + 6, 18, () -> { tab = t; rightScroll = 0; if (t == Tab.ADD) refreshFiles(true); }, null));
            x += tw + 14;
        }

        // the site's button hints: Showing, Undo, Redo, then Done
        LayerConfig c = assets.editable();
        int rx = W - 6;
        String done = Lang.t("button.done").toUpperCase(Locale.ROOT);
        int dw = strongWidth(done) + 16;
        boolean dh = in(mx, my, rx - dw, 6, dw, 15);
        slant(g, rx - dw, 6, dw, 15, 4, dh ? ACCENT_HOVER : PAPER);
        g.text(font, styled(done, true, true), rx - dw + 6, 10, INK, false);
        hits.add(new Hit(rx - dw, 6, dw, 15, this::close, null));
        rx -= dw + 10;
        String show = Lang.t(c.enabled ? "header.showing" : "header.hidden");
        int full = 3 * 24 + strongWidth(Lang.t("button.redo").toUpperCase(Locale.ROOT)) + strongWidth(Lang.t("button.undo").toUpperCase(Locale.ROOT))
            + strongWidth(show.toUpperCase(Locale.ROOT));
        boolean compact = rx - full < x;   // not enough room: keys only (their labels show as tooltips)
        rx = hint(g, mx, my, rx, "↻", Lang.t("button.redo"), !redoStack.isEmpty(), compact, this::redo);
        rx = hint(g, mx, my, rx, "↺", Lang.t("button.undo"), !undoStack.isEmpty(), compact, this::undo);
        hint(g, mx, my, rx, c.enabled ? "●" : "○", show, true, compact,
            () -> { c.enabled = !c.enabled; save(); say(Lang.t(c.enabled ? "msg.layers_shown" : "msg.layers_hidden"), MUTED); });
        g.fill(Math.max(x, W * 11 / 20), 24, W, 25, 0xB0FFFFFF);
    }

    /** One button hint, right-aligned at rx: a round white key with a symbol, then the label. Returns the next rx. */
    private int hint(GuiGraphicsExtractor g, int mx, int my, int rx, String key, String label, boolean enabled, boolean compact, Runnable click) {
        String l = compact ? "" : label.toUpperCase(Locale.ROOT);
        int lw = compact ? -3 : strongWidth(l), kw = 11, w = kw + 3 + lw, x = rx - w;
        if (compact && in(mx, my, x - 2, 5, w + 4, 16)) tipCandidate = label;
        boolean hov = enabled && in(mx, my, x - 2, 5, w + 4, 16);
        capsule(g, x, 8, kw, 11, enabled ? (hov ? CYAN : PAPER) : FAINT);
        int kx = x + (kw - font.width(body(key))) / 2 + 1;
        g.text(font, body(key), kx, 10, INK, false);
        g.text(font, styled(l, true, true), x + kw + 3, 10, !enabled ? FAINT : hov ? CYAN : TEXT, false);
        if (enabled) hits.add(new Hit(x - 2, 5, w + 4, 16, click, null));
        return x - 10;
    }

    // =================================================================== left: layers
    private void layersPanel(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h) {
        // no box: dark ribbons over the water, like the game's party list
        LayerConfig cfg = assets.editable();
        List<LayerConfig.Layer> layers = cfg.layers;
        if (selected >= layers.size()) selected = Math.max(0, layers.size() - 1);
        if (selectedFolder != null && cfg.members(selectedFolder).isEmpty()) selectedFolder = null;
        int tagW = nameTag(g, x, y, Lang.t("section.layers").toUpperCase(Locale.ROOT));
        String count = String.valueOf(layers.size());
        int cw = font.width(body(count)) + 8;
        capsule(g, x + tagW + 4, y + 1, cw, 9, PAPER);
        g.text(font, body(count), x + tagW + 8, y + 2, INK, false);

        int footer = ROW * 2 + GAP + 10;
        int listTop = y + 16, listBottom = y + h - footer;
        if (layers.isEmpty()) {
            g.text(font, body(Lang.t("layers.empty")), x + PAD, listTop + 4, MUTED, false);
            wrapped(g, x + PAD, listTop + 16, w - PAD * 2, Lang.t("layers.empty_hint"), FAINT);
        }

        int before = hits.size();
        g.enableScissor(x, listTop, x + w, listBottom);
        int ry = listTop - leftScroll, startY = ry;
        int rx = x, rwid = w - 4;
        for (LayerConfig.Entry e : cfg.entries()) {
            if (e.isFolder()) {
                ry = folderRow(g, mx, my, rx, ry, rwid, e);
                LayerConfig.Folder f = cfg.folder(e.folder());
                if (!f.collapsed) {
                    int top = ry;
                    for (LayerConfig.Layer l : e.members()) ry = partRow(g, mx, my, rx + 9, ry, rwid - 9, l, layers.indexOf(l), f.enabled);
                    g.fill(rx + 4, top - 2, rx + 5, ry - 3, 0x70FFFFFF);   // guide line for the folder's parts
                }
            } else {
                LayerConfig.Layer l = e.members().get(0);
                ry = layerRow(g, mx, my, rx, ry, rwid, l, layers.indexOf(l), true);
            }
        }
        g.disableScissor();
        int contentH = ry - startY;
        for (int i = hits.size() - 1; i >= before; i--) {
            Hit hh = hits.get(i);
            if (hh.y() + hh.h() <= listTop || hh.y() >= listBottom) hits.remove(i);
        }
        int viewH = listBottom - listTop;
        leftScroll = clamp(leftScroll, 0, Math.max(0, contentH - viewH));
        if (contentH > viewH) {
            int barH = Math.max(12, viewH * viewH / contentH);
            int barY = listTop + (viewH - barH) * leftScroll / Math.max(1, contentH - viewH);
            g.fill(x + w - 1, barY, x + w, barY + barH, 0xD048E6FF);
        }
        final int maxScroll = Math.max(0, contentH - viewH);
        hits.add(new Hit(x, listTop, w, viewH, null, d -> leftScroll = clamp(leftScroll - d * 12, 0, maxScroll)));

        int by = y + h - footer + 4;
        primaryButton(g, mx, my, x, by, w - 4, Lang.t("button.add_layer"), () -> { tab = Tab.ADD; impFile = null; rightScroll = 0; refreshFiles(true); });
        by += ROW + GAP + 2;
        boolean has = !layers.isEmpty();
        int half = (w - 4 - GAP) / 2;
        iconButton(g, mx, my, x, by, half, Lang.t("button.up"), has, () -> move(-1));
        iconButton(g, mx, my, x + half + GAP, by, w - 4 - half - GAP, Lang.t("button.down"), has, () -> move(1));
    }

    /** A layer-list row: a dark ribbon; under the mouse, a cyan bar and a lighter ribbon. */
    private void ribbon(GuiGraphicsExtractor g, int rx, int ry, int rwid, int rh, boolean sel, boolean hov) {
        // the site's blues: lighter than the panels, so the selection's dark letters stay readable
        slant(g, rx, ry, rwid, rh, 4, sel ? 0xF41F7CFF : hov ? 0xE81C5AE6 : RIBBON);
        g.fill(rx, ry, rx + rwid - 1, ry + 1, sel ? 0x66FFFFFF : 0x2EFFFFFF);   // a thin light top edge
        if (hov && !sel) {
            for (int i = 0; i < 6; i++) g.fill(rx + 2 + i * rwid / 8, ry, rx + 2 + (i + 1) * rwid / 8, ry + rh, ((0x34 - i * 8) << 24) | 0x48E6FF);
            g.fill(rx, ry, rx + 2, ry + rh, CYAN);
        }
    }

    /** A row's name: the triangle when selected, else italic. */
    private void rowName(GuiGraphicsExtractor g, String key, Object what, String name, int x, int y, int w, boolean sel, boolean on, boolean hov) {
        if (sel) selection(g, key, what, fitStrong(name, w), x, y);
        else g.text(font, styled(fitStrong(name, w), true, true), x, y, !on ? 0xFF8FA6E8 : hov ? PAPER : MENU, false);
    }

    private int folderRow(GuiGraphicsExtractor g, int mx, int my, int rx, int ry, int rwid, LayerConfig.Entry e) {
        LayerConfig.Folder f = assets.editable().folder(e.folder());
        int rh = 18;
        boolean sel = e.folder().equals(selectedFolder), hov = in(mx, my, rx, ry, rwid, rh - 2);
        ribbon(g, rx, ry, rwid, rh - 2, sel, hov);
        // collapse arrow
        boolean ah = in(mx, my, rx + 2, ry, 12, rh - 2);
        float ax = rx + 8f, ay = ry + 8f;   // drawn, not a font symbol: right when folded, down when open
        if (f.collapsed) triangle(g, ax - 2f, ay - 3.5f, ax - 2f, ay + 3.5f, ax + 2.5f, ay, ah ? PAPER : CYAN);
        else triangle(g, ax - 3.5f, ay - 2f, ax + 3.5f, ay - 2f, ax, ay + 2.5f, ah ? PAPER : CYAN);
        hits.add(new Hit(rx + 2, ry, 12, rh - 2, () -> { f.collapsed = !f.collapsed; save(); }, null));
        rowName(g, "layer", "folder:" + e.folder(), e.folder(), rx + 16, ry + 4, rwid - 38, sel, f.enabled, hov);
        visibilityBox(g, mx, my, rx + rwid - 18, ry + 4, f.enabled, () -> {
            f.enabled = !f.enabled;
            save();
            say(Lang.t(f.enabled ? "msg.folder_shown" : "msg.folder_hidden", e.folder()), MUTED);
        });
        hits.add(new Hit(rx + 14, ry, rwid - 32, rh - 2, () -> selectFolder(e.folder()), null));
        return ry + rh;
    }

    private int layerRow(GuiGraphicsExtractor g, int mx, int my, int rx, int ry, int rwid, LayerConfig.Layer l, int idx, boolean parentOn) {
        int rh = 18;
        boolean on = l.enabled && parentOn;
        boolean sel = selectedFolder == null && idx == selected, hov = in(mx, my, rx, ry, rwid, rh - 2);
        ribbon(g, rx, ry, rwid, rh - 2, sel, hov);
        g.fill(rx + 6, ry + 3, rx + 10, ry + 13, on ? (l.color | 0xFF000000) : FAINT);
        rowName(g, "layer", l, l.label, rx + 16, ry + 4, rwid - 38, sel, on, hov);
        visibilityBox(g, mx, my, rx + rwid - 18, ry + 4, l.enabled, () -> { l.enabled = !l.enabled; save(); });
        hits.add(new Hit(rx, ry, rwid - 22, rh - 2, () -> selectLayer(idx), null));
        return ry + rh;
    }

    /** Compact one-line row for a part inside a folder. */
    private int partRow(GuiGraphicsExtractor g, int mx, int my, int rx, int ry, int rwid, LayerConfig.Layer l, int idx, boolean parentOn) {
        int rh = 16;
        boolean on = l.enabled && parentOn;
        boolean sel = selectedFolder == null && idx == selected, hov = in(mx, my, rx, ry, rwid, rh - 2);
        ribbon(g, rx, ry, rwid, rh - 2, sel, hov);
        g.fill(rx + 6, ry + 3, rx + 9, ry + 11, on ? (l.color | 0xFF000000) : FAINT);
        rowName(g, "layer", l, l.label, rx + 14, ry + 3, rwid - 36, sel, on, hov);
        visibilityBox(g, mx, my, rx + rwid - 18, ry + 3, l.enabled, () -> { l.enabled = !l.enabled; save(); });
        hits.add(new Hit(rx, ry, rwid - 22, rh - 2, () -> selectLayer(idx), null));
        return ry + rh;
    }

    /** Show / hide: a small ring, filled when shown. */
    private void visibilityBox(GuiGraphicsExtractor g, int mx, int my, int ex, int ey, boolean on, Runnable flip) {
        boolean eh = in(mx, my, ex - 3, ey - 3, 14, 14);
        ring(g, ex + 4f, ey + 4f, 4.5f, 1.2f, eh ? PAPER : on ? CYAN : FAINT);
        if (on) disc(g, ex + 4f, ey + 4f, 2.2f, eh ? PAPER : CYAN);
        hits.add(new Hit(ex - 3, ey - 3, 14, 14, flip, null));
    }

    private void selectLayer(int idx) {
        if (idx != selected || selectedFolder != null) rightScroll = 0;
        selected = idx;
        selectedFolder = null;
        confirmDelete = -1;
        if (tab != Tab.EDIT) { tab = Tab.EDIT; rightScroll = 0; }
    }

    private void selectFolder(String name) {
        selectedFolder = name;
        confirmDelete = -1;
        if (tab != Tab.EDIT) tab = Tab.EDIT;
        rightScroll = 0;
    }

    /** Up/Down: moves the selected folder as a block, or the selected layer within its folder. */
    private void move(int dir) {
        LayerConfig cfg = assets.editable();
        if (selectedFolder != null) {
            if (cfg.moveFolder(selectedFolder, dir)) save();
            return;
        }
        if (cfg.layers.isEmpty()) return;
        LayerConfig.Layer l = cfg.layers.get(selected);
        if (cfg.moveLayer(l, dir)) {
            selected = cfg.layers.indexOf(l);
            save();
        }
    }

    // =================================================================== right: tabs
    private void sidePanel(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h) {
        glassPanel(g, x, y, w, h);
        // a name tag on the top edge: what the panel is showing
        LayerConfig cfg = assets.editable();
        String what = Lang.t("tab." + tab.name().toLowerCase(Locale.ROOT));
        if (tab == Tab.EDIT && selectedFolder != null) what = selectedFolder;
        else if (tab == Tab.EDIT && !cfg.layers.isEmpty()) what = cfg.layers.get(Math.min(selected, cfg.layers.size() - 1)).label;
        nameTag(g, x + 8, y - 5, fitStrong(what.toUpperCase(Locale.ROOT), w - 40));

        int cx = x + PAD, cw = w - PAD * 2 - 3, cTop = y + 10, cBottom = y + h - 8;
        int viewH = cBottom - cTop;
        rightScroll = clamp(rightScroll, 0, Math.max(0, rightContent - viewH));
        int before = hits.size();
        g.enableScissor(x + 1, cTop, x + w - 1, cBottom);
        float e = tabIn();
        g.pose().pushMatrix();
        g.pose().translate((1f - e) * 28f, 0f);   // a new tab's contents slide in from the right
        int cy = cTop - rightScroll, start = cy;
        cy = switch (tab) {
            case EDIT -> editTab(g, mx, my, cx, cy, cw);
            case ADD -> impFile != null ? importView(g, mx, my, cx, cy, cw) : addTab(g, mx, my, cx, cy, cw);
            case SETTINGS -> settingsTab(g, mx, my, cx, cy, cw);
        };
        g.pose().popMatrix();
        g.disableScissor();
        if (e < 1f) {
            // a white slanted wipe sweeps across the panel, with a cyan one just behind it
            float lin = Math.min(1f, (System.currentTimeMillis() - tabSince) / 240f);
            float sx = x - 40 + (w + 80) * lin, top = y + 1, bot = y + h - 1, lean = 18f;
            int a = Math.round(0xC8 * (1f - lin));
            g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
            fillShape(g, top, bot, yy -> { float k = (yy - top) / (bot - top) * lean; return new float[]{sx - 26 - k, sx - 18 - k}; }, (Math.round(a * 0.7f) << 24) | 0x48E6FF);
            fillShape(g, top, bot, yy -> { float k = (yy - top) / (bot - top) * lean; return new float[]{sx - 16 - k, sx - k}; }, (a << 24) | 0xFFFFFF);
            g.disableScissor();
        }
        rightContent = cy - start + 6;
        for (int i = hits.size() - 1; i >= before; i--) {
            Hit hh = hits.get(i);
            if (hh.y() + hh.h() <= cTop || hh.y() >= cBottom) hits.remove(i);
        }
        if (rightContent > viewH) {
            int barH = Math.max(14, viewH * viewH / rightContent);
            int barY = cTop + (viewH - barH) * rightScroll / Math.max(1, rightContent - viewH);
            g.fill(x + w - 4, barY, x + w - 3, barY + barH, 0xD048E6FF);
            // more above or below: the content fades out at that edge
            if (rightScroll > 0) g.fillGradient(x + 1, cTop, x + w - 5, cTop + 8, 0x90050B33, 0x00050B33);
            if (rightScroll < rightContent - viewH) g.fillGradient(x + 1, cBottom - 10, x + w - 5, cBottom, 0x00050B33, 0x90050B33);
        }
        hits.add(new Hit(x, cTop, w, viewH, null, d -> rightScroll = clamp(rightScroll - d * 14, 0, Math.max(0, rightContent - viewH))));
    }

    // ------------------------------------------------------------------- Edit
    private int editTab(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w) {
        if (selectedFolder != null) return folderView(g, mx, my, x, y, w);
        List<LayerConfig.Layer> layers = assets.editable().layers;
        if (layers.isEmpty()) {
            g.text(font, body(Lang.t("edit.empty")), x, y + 2, TEXT, false);
            y += 13;
            y = wrapped(g, x, y, w, Lang.t("edit.empty_hint"), MUTED);
            return y;
        }
        LayerConfig.Layer l = layers.get(selected);
        textField(g, mx, my, x, y - 2, w, 12, "name:layer:" + selected, l.label, false, TEXT, v -> {
            if (!v.isBlank()) { l.label = v.trim(); save(); }
        });
        y += 14;
        // one row: Duplicate · Copy style · Paste style (tooltips explain them)
        int third = (w - GAP * 2) / 3, bx2 = x + third + GAP, bx3 = x + (third + GAP) * 2, w3 = w - (third + GAP) * 2;
        secondaryButton(g, mx, my, x, y, third, Lang.t("button.duplicate"), () -> {
            LayerConfig.Layer c = assets.editable().copyLayer(l);
            c.label = Lang.t("layer.copy_name", c.label);
            layers.add(selected + 1, c);
            selected++;
            save();
            say(Lang.t("msg.duplicated", l.label), MUTED);
        });
        tip(mx, my, x, y, third, ROW, "duplicate");
        secondaryButton(g, mx, my, bx2, y, third, Lang.t("button.copy_style"), () -> {
            styleClipboard = assets.editable().copyLayer(l);
            say(Lang.t("msg.style_copied", l.label), MUTED);
        });
        tip(mx, my, bx2, y, third, ROW, "copy_style");
        if (styleClipboard != null) {
            secondaryButton(g, mx, my, bx3, y, w3, Lang.t("button.paste_style"), () -> {
                LayerConfig.copyStyle(styleClipboard, l);
                save();
                say(Lang.t("msg.style_pasted", l.label), MUTED);
            });
        } else {
            iconButton(g, mx, my, bx3, y, w3, Lang.t("button.paste_style"), false, null);
        }
        tip(mx, my, bx3, y, w3, ROW, "paste_style");
        y += ROW + GAP;
        y += 3;

        List<String> folderOpts = new ArrayList<>();
        folderOpts.add("");
        folderOpts.addAll(assets.editable().folderNames());
        if (folderOpts.size() > 1 || l.folder != null) {
            y = cycle(g, mx, my, x, y, w, "folder", l.folder == null ? Lang.t("value.none") : l.folder, d -> {
                String cur = l.folder == null ? "" : l.folder;
                String next = folderOpts.get(Math.floorMod(folderOpts.indexOf(cur) + d, folderOpts.size()));
                l.folder = next.isEmpty() ? null : next;
                LayerConfig cfg = assets.editable();
                if (l.folder != null) {
                    // keep the folder's parts together: move this layer right after the folder's last part
                    cfg.layers.remove(l);
                    int at = -1;
                    for (int i = 0; i < cfg.layers.size(); i++) if (l.folder.equals(cfg.layers.get(i).folder)) at = i;
                    cfg.layers.add(at + 1, l);
                }
                selected = cfg.layers.indexOf(l);
                save();
            });
            y += 2;
        }

        y = section(g, x, y, w, "look");
        if (l.isParticles()) {
            y = particleTexture(g, mx, my, x, y, w, l);
        } else {
        List<String> groups = assets.groupsOf(l.mesh);
        String part = l.groups == null ? Lang.t("value.whole_model") : String.join(", ", l.groups);
        y = cycle(g, mx, my, x, y, w, "part", part, d -> {
            List<String> opts = new ArrayList<>();
            opts.add("*");
            opts.addAll(groups);
            String cur = l.groups == null ? "*" : (l.groups.size() == 1 ? l.groups.get(0) : "*");
            String next = opts.get(Math.floorMod(opts.indexOf(cur) + d, opts.size()));
            l.groups = next.equals("*") ? null : new ArrayList<>(List.of(next));
            save();
        });
        y = cycle(g, mx, my, x, y, w, "model", shortFile(l.mesh), d -> {
            if (meshFiles.isEmpty()) return;
            int i = meshFiles.indexOf(l.mesh);
            l.mesh = meshFiles.get(Math.floorMod(i + d, meshFiles.size()));
            l.groups = null;
            save();
        });
        y = cycle(g, mx, my, x, y, w, "texture", l.texture == null ? Lang.t("value.none") : shortFile(l.texture), d -> {
            List<String> opts = new ArrayList<>();
            opts.add("");
            opts.addAll(pngFiles);
            if (l.texture != null && !opts.contains(l.texture)) opts.add(l.texture);
            String cur = l.texture == null ? "" : l.texture;
            String next = opts.get(Math.floorMod(opts.indexOf(cur) + d, opts.size()));
            l.texture = next.isEmpty() ? null : next;
            save();
        });
        }
        y = cycle(g, mx, my, x, y, w, "style", styleName(l.render), d -> { l.render = cyc(LayerConfig.Render.values(), l.render, d); save(); });
        y = hue(g, mx, my, x, y, w, l);
        y = tint(g, mx, my, x, y, w, l);
        int alpha = (l.color >>> 24) & 0xFF;
        exact(v -> { int a = clamp((int) Math.round(v * 2.55), 0, 255); l.color = (a << 24) | (l.color & 0xFFFFFF); save(); });
        y = stepper(g, mx, my, x, y, w, "opacity", Math.round(alpha * 100f / 255f) + "%", d -> {
            int a = clamp(((l.color >>> 24) & 0xFF) + d * 13, 0, 255);
            l.color = (a << 24) | (l.color & 0xFFFFFF);
            save();
        });
        y = toggle(g, mx, my, x, y, w, "ignore_world_light", l.fullbright, () -> { l.fullbright = !l.fullbright; save(); });

        if (l.isParticles()) y = particleSettings(g, mx, my, x, y + 4, w, l.particles);

        y = section(g, x, y + 4, w, "placement");
        y = cycle(g, mx, my, x, y, w, "attach_to", anchorName(l.anchor), d -> { l.anchor = cyc(LayerConfig.Anchor.values(), l.anchor, d); save(); });
        double step = STEPS[stepIndex];
        if (!l.isParticles()) {
            exact(v -> { float s = (float) Math.max(0.01, v / 100.0); l.scale = new float[]{s, s, s}; save(); });
            y = stepper(g, mx, my, x, y, w, "size", Math.round(l.scale[0] * 100) + "%", d -> {
                float s = (float) Math.max(0.01, round(l.scale[0] + d * step));
                l.scale = new float[]{s, s, s};
                save();
            });
        }
        String[] move = {"left", "down", "back"};
        for (int a = 0; a < 3; a++) {
            final int ai = a;
            exact(v -> { l.offset[ai] = (float) round(v); save(); });
            y = stepper(g, mx, my, x, y, w, move[a], fmt(l.offset[a]), d -> { l.offset[ai] = (float) round(l.offset[ai] + d * step); save(); });
        }
        String[] rot = {"tilt", "turn", "roll"};
        for (int a = 0; a < 3; a++) {
            final int ai = a;
            exact(v -> { double r = ((v + 180) % 360 + 360) % 360 - 180; l.rotation[ai] = (float) round(r); save(); });
            y = stepper(g, mx, my, x, y, w, rot[a], fmt(l.rotation[a]) + "°", d -> {
                float r = l.rotation[ai] + d * ROT_STEPS[stepIndex];
                if (r > 180) r -= 360;
                if (r < -180) r += 360;
                l.rotation[ai] = r;
                save();
            });
        }
        y = cycle(g, mx, my, x, y, w, "step_size", Lang.t("value.step." + STEP_NAMES[stepIndex]), d -> stepIndex = Math.floorMod(stepIndex + d, STEPS.length));
        y = secondaryButton(g, mx, my, x, y, w, Lang.t("button.reset_placement"), () -> {
            l.offset = new float[3]; l.rotation = new float[3]; l.scale = new float[]{1, 1, 1}; save();
        });

        y = section(g, x, y + 4, w, "behaviour");
        y = toggle(g, mx, my, x, y, w, "mirrored_copy", l.mirror, () -> { l.mirror = !l.mirror; save(); });
        y = toggle(g, mx, my, x, y, w, "sway_with_movement", l.physics, () -> { l.physics = !l.physics; save(); });
        y = toggle(g, mx, my, x, y, w, "hide_while_gliding", l.hideWhenFlying, () -> { l.hideWhenFlying = !l.hideWhenFlying; save(); });
        y = audience(g, mx, my, x, y + 4, w, l.show, l.folder == null ? null : assets.editable().folders.get(l.folder));
        if (!l.isParticles()) y = cycle(g, mx, my, x, y, w, "model_axes", Lang.t(l.yUp ? "value.axes.blender" : "value.axes.minecraft"), d -> { l.yUp = !l.yUp; save(); });

        y = section(g, x, y + 4, w, "layer");
        int half = (w - GAP) / 2, dx = x, dw = w;
        if (l.preset != null) {
            secondaryButton(g, mx, my, x, y, half, Lang.t("button.reset_preset"), () -> {
                LayerConfig.Layer t = assets.presets.template(l);
                if (t == null) { say(Lang.t("msg.preset_missing"), DANGER); return; }
                LayerConfig.copyStyle(t, l);
                l.mesh = t.mesh; l.groups = t.groups; l.texture = t.texture;
                save();
                say(Lang.t("msg.reset_preset", l.label), MUTED);
            });
            tip(mx, my, x, y, half, ROW, "reset_preset");
            dx = x + half + GAP; dw = w - half - GAP;
        }
        boolean arm = confirmDelete == selected;
        boolean dh = in(mx, my, dx, y, dw, ROW);
        g.fill(dx, y, dx + dw, y + ROW, arm ? 0xFF5A2338 : dh ? FIELD_HOVER : FIELD);
        g.outline(dx, y, dw, ROW, arm ? DANGER : FIELD_EDGE);
        String dl = fit(Lang.t(arm ? "button.confirm" : "button.remove"), dw - 4);
        g.text(font, body(dl), dx + (dw - font.width(body(dl))) / 2, y + 3, arm ? DANGER : TEXT, false);
        hits.add(new Hit(dx, y, dw, ROW, () -> {
            if (confirmDelete != selected) { confirmDelete = selected; keepConfirm = true; return; }
            String n = layers.remove(selected).label;
            confirmDelete = -1;
            save();
            say(Lang.t("msg.removed", n), MUTED);
        }, null));
        y += ROW + GAP;
        return y;
    }

    // ------------------------------------------------------------------- Folder view
    private int folderView(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w) {
        LayerConfig cfg = assets.editable();
        String name = selectedFolder;
        LayerConfig.Folder f = cfg.folder(name);
        List<LayerConfig.Layer> parts = cfg.members(name);
        textField(g, mx, my, x, y - 2, w, 12, "name:folder:" + name, name, false, TEXT, v -> renameFolder(name, v.trim()));
        g.text(font, body(Lang.t(parts.size() == 1 ? "folder.count_one" : "folder.count", parts.size())), x, y + 10, FAINT, false);
        y += 23;
        y = toggle(g, mx, my, x, y, w, "show_this_folder", f.enabled, () -> { f.enabled = !f.enabled; save(); });

        y = section(g, x, y + 4, w, "parts");
        for (LayerConfig.Layer l : parts) {
            boolean hov = in(mx, my, x, y, w - 18, ROW + 2);
            if (hov) g.fill(x, y, x + w, y + ROW + 2, HOVER);
            boolean on = l.enabled && f.enabled;
            g.fill(x + 2, y + 3, x + 5, y + ROW - 1, on ? (l.color | 0xFF000000) : FAINT);
            g.text(font, body(fit(l.label, w - 30)), x + 9, y + 4, on ? TEXT : MUTED, false);
            final int idx = cfg.layers.indexOf(l);
            hits.add(new Hit(x, y, w - 18, ROW + 2, () -> selectLayer(idx), null));
            visibilityBox(g, mx, my, x + w - 11, y + 4, l.enabled, () -> { l.enabled = !l.enabled; save(); });
            y += ROW + 3;
        }
        y = secondaryButton(g, mx, my, x, y + 2, w, Lang.t("button.show_all_parts"), () -> { for (LayerConfig.Layer l : parts) l.enabled = true; f.enabled = true; save(); });

        y = section(g, x, y + 4, w, "adjust_all_parts");
        double step = STEPS[stepIndex];
        LayerConfig.Layer first = parts.get(0);
        exact(v -> { float s2 = (float) Math.max(0.01, v / 100.0); for (LayerConfig.Layer l : parts) l.scale = new float[]{s2, s2, s2}; save(); });
        y = stepper(g, mx, my, x, y, w, "size", Math.round(first.scale[0] * 100) + "%", d -> {
            for (LayerConfig.Layer l : parts) {
                float s2 = (float) Math.max(0.01, round(l.scale[0] + d * step));
                l.scale = new float[]{s2, s2, s2};
            }
            save();
        });
        String[] move = {"left", "down", "back"};
        for (int a = 0; a < 3; a++) {
            final int ai = a;
            exact(v -> { float delta = (float) (v - first.offset[ai]); for (LayerConfig.Layer l : parts) l.offset[ai] = (float) round(l.offset[ai] + delta); save(); });
            y = stepper(g, mx, my, x, y, w, move[a], fmt(first.offset[a]), d -> {
                for (LayerConfig.Layer l : parts) l.offset[ai] = (float) round(l.offset[ai] + d * step);
                save();
            });
        }
        exact(v -> { f.motion = clampF((float) (v / 100.0), 0f, 3f); save(); });
        y = stepper(g, mx, my, x, y, w, "motion", Math.round(f.motion * 100) + "%", d -> { f.motion = clampF(f.motion + d * 0.1f, 0f, 3f); save(); });
        y = cycle(g, mx, my, x, y, w, "step_size", Lang.t("value.step." + STEP_NAMES[stepIndex]), d -> stepIndex = Math.floorMod(stepIndex + d, STEPS.length));
        if (styleClipboard != null) {
            y = secondaryButton(g, mx, my, x, y, w, Lang.t("button.paste_style_all"), () -> {
                for (LayerConfig.Layer l : parts) LayerConfig.copyStyle(styleClipboard, l);
                save();
                say(Lang.t("msg.style_pasted_all", parts.size()), MUTED);
            });
        }

        y = audience(g, mx, my, x, y + 4, w, f.show, null);

        y = section(g, x, y + 4, w, "share");
        y = secondaryButton(g, mx, my, x, y, w, Lang.t("button.export_zip"), () -> {
            try {
                java.nio.file.Path zp = assets.exportFolder(name);
                refreshFiles(true);
                say(Lang.t("msg.exported", "exports/" + zp.getFileName()), ACCENT);
            } catch (Exception ex) {
                say(Lang.t("msg.export_failed", ex.getMessage()), DANGER);
            }
        });
        tip(mx, my, x, y - ROW - GAP, w, ROW, "export_zip");

        y = section(g, x, y + 4, w, "folder");
        int half = (w - GAP) / 2;
        secondaryButton(g, mx, my, x, y, half, Lang.t("button.ungroup"), () -> {
            for (LayerConfig.Layer l : parts) l.folder = null;
            selectedFolder = null;
            rightScroll = 0;
            selected = cfg.layers.indexOf(parts.get(0));
            save();
            say(Lang.t("msg.ungrouped", name), MUTED);
        });
        tip(mx, my, x, y, half, ROW, "ungroup");
        int dx = x + half + GAP, dw = w - half - GAP;
        boolean dh = in(mx, my, dx, y, dw, ROW);
        g.fill(dx, y, dx + dw, y + ROW, confirmFolderDelete ? 0xFF5A2338 : dh ? FIELD_HOVER : FIELD);
        g.outline(dx, y, dw, ROW, confirmFolderDelete ? DANGER : FIELD_EDGE);
        String dl = fit(Lang.t(confirmFolderDelete ? "button.confirm" : "button.remove_folder"), dw - 4);
        g.text(font, body(dl), dx + (dw - font.width(body(dl))) / 2, y + 3, confirmFolderDelete ? DANGER : TEXT, false);
        hits.add(new Hit(dx, y, dw, ROW, () -> {
            if (!confirmFolderDelete) { confirmFolderDelete = true; keepConfirm = true; return; }
            cfg.layers.removeAll(parts);
            confirmFolderDelete = false;
            selectedFolder = null;
            selected = 0;
            rightScroll = 0;
            save();
            say(Lang.t("msg.folder_removed", name, parts.size()), MUTED);
        }, null));
        tip(mx, my, dx, y, dw, ROW, "remove_folder");
        return y + ROW + GAP;
    }

    // ------------------------------------------------------------------- Add
    private int addTab(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w) {
        y = section(g, x, y, w, "presets");
        int th = Thumbs.SIZE + 4;   // thumbnail column
        for (Presets.Info p : presetList) {
            List<String> lines = wrapLines(p.description(), w - 12 - th, 2);
            int ch = Math.max(19 + lines.size() * 10 + 3, th + 6);
            boolean hov = in(mx, my, x, y, w, ch);
            g.fill(x, y, x + w, y + ch, hov ? FIELD_HOVER : FIELD);
            g.fill(x, y, x + 2, y + ch, ACCENT);
            thumb(g, x + 5, y + (ch - Thumbs.SIZE) / 2, Thumbs.get("preset:" + p.id(), () -> assets.presets.previewGroups(p.id())));
            g.text(font, body(fit(p.name(), w - 46 - th)), x + 7 + th, y + 5, TEXT, false);
            for (int li = 0; li < lines.size(); li++) g.text(font, body(lines.get(li)), x + 7 + th, y + 18 + li * 10, MUTED, false);
            String chip = Lang.t("preset.add");
            int cwid = font.width(body(chip)) + 8;
            g.fill(x + w - cwid - 5, y + 4, x + w - 5, y + 14, ACCENT);
            g.text(font, body(chip), x + w - cwid - 1, y + 5, ACCENT_INK, false);
            hits.add(new Hit(x, y, w, ch, () -> addPreset(p), null));
            y += ch + GAP;
        }

        y = section(g, x, y + 5, w, "particles");
        int pw = (w - GAP) / 2;
        String[] starters = ParticleStarters.NAMES;
        for (int i = 0; i < starters.length; i++) {
            int bx = x + (i % 2) * (pw + GAP);
            String id = starters[i];
            secondaryButton(g, mx, my, bx, y, i % 2 == 0 ? pw : w - pw - GAP, Lang.t("starter." + id), () -> addParticles(id));
            tip(mx, my, bx, y, pw, ROW, "starter_" + id);
            if (i % 2 == 1 || i == starters.length - 1) y += ROW + GAP;
        }

        y = section(g, x, y + 5, w, "your_models");
        List<String> own = new ArrayList<>();
        for (String f : meshFiles) if (!f.startsWith("presets/")) own.add(f);
        if (own.isEmpty() && objFiles.isEmpty()) {
            y = wrapped(g, x, y, w, Lang.t("add.empty_hint"), MUTED);
        }
        for (String f : own) y = modelRow(g, mx, my, x, y, w, f, "mesh", () -> addMesh(f));
        for (String f : objFiles) {
            if (f.startsWith("presets/")) continue;
            y = modelRow(g, mx, my, x, y, w, f, "import", () -> openImport(f));
        }
        if (!setZips.isEmpty()) {
            y = section(g, x, y + 5, w, "shared_sets");
            for (String z : setZips) y = listRow(g, mx, my, x, y, w, z.startsWith("exports/") ? z.substring(8) : z, "set", () -> importSetZip(z));
        }
        y += 4;
        y = secondaryButton(g, mx, my, x, y, w, Lang.t("button.open_folder"), assets::openFolder);
        return y;
    }

    private void importSetZip(String zip) {
        try {
            List<LayerConfig.Layer> add = assets.importSet(zip);
            List<LayerConfig.Layer> layers = assets.editable().layers;
            layers.addAll(add);
            String folder = add.get(0).folder;
            save();
            refreshFiles(true);
            selectFolder(folder);
            say(Lang.t(add.size() == 1 ? "msg.added_one" : "msg.added", folder, add.size()), ACCENT);
        } catch (Exception e) {
            say(Lang.t("msg.set_failed", e.getMessage()), DANGER);
        }
    }

    private void renameFolder(String from, String to) {
        if (to.isEmpty() || to.equals(from)) return;
        LayerConfig cfg = assets.editable();
        if (cfg.folderNames().contains(to)) { say(Lang.t("msg.folder_exists", to), WARN); return; }
        LayerConfig.Folder state = cfg.folders.remove(from);
        for (LayerConfig.Layer l : cfg.layers) if (from.equals(l.folder)) l.folder = to;
        if (state != null) cfg.folders.put(to, state);
        selectedFolder = to;
        save();
    }

    /** A model file in the Add tab, with a small picture of it. */
    private int modelRow(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String file, String kind, Runnable click) {
        int rh = Thumbs.SIZE + 2;
        boolean hov = in(mx, my, x, y, w, rh);
        if (hov) g.fill(x, y, x + w, y + rh, HOVER);
        java.nio.file.Path path = assets.dir().resolve(file);
        thumb(g, x + 1, y + 1, Thumbs.get(Thumbs.key(path), () -> assets.previewGroups(file)));
        String kindText = Lang.t("add.kind." + kind);
        int kw = font.width(body(kindText)), tx = x + Thumbs.SIZE + 6;
        g.text(font, body(fit(file, x + w - kw - 8 - tx)), tx, y + (rh - 8) / 2, TEXT, false);
        g.text(font, body(kindText), x + w - kw - 3, y + (rh - 8) / 2, kind.equals("import") ? ACCENT : MUTED, false);
        hits.add(new Hit(x, y, w, rh, click, null));
        return y + rh + 1;
    }

    /** "Label   [text]" row: click the text to type, Enter saves. */
    private int textRow(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String id, String value, String placeholder, Consumer<String> commit) {
        int lw = labelWidth(w);
        boolean band = rowBand(g, mx, my, x, y, w);
        rowLabel(g, Lang.t("label." + id), x, y, lw - 4, band);
        tip(mx, my, x, y, lw - 2, ROW, id);
        capsule(g, x + lw, y + 1, w - lw, ROW - 2, FIELD);
        boolean empty = value == null || value.isBlank();
        textField(g, mx, my, x + lw + 4, y + 1, w - lw - 6, ROW - 2, "text:" + id, empty ? "" : value, false, TEXT, commit);
        if (empty && !("text:" + id).equals(editId)) g.text(font, body(fit(placeholder, w - lw - 8)), x + lw + 4, y + 3, FAINT, false);
        return y + ROW + GAP;
    }

    /** Draws a thumbnail (or an empty frame while it's being made), one fill per run of equal pixels. */
    private void thumb(GuiGraphicsExtractor g, int x, int y, int[] px) {
        int n = Thumbs.SIZE;
        g.fill(x, y, x + n, y + n, 0x40081038);
        if (px == null) return;
        for (int r = 0; r < n; r++) {
            int c = 0;
            while (c < n) {
                int col = px[r * n + c], end = c + 1;
                while (end < n && px[r * n + end] == col) end++;
                if ((col >>> 24) != 0) g.fill(x + c, y + r, x + end, y + r + 1, col);
                c = end;
            }
        }
    }

    private int listRow(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String name, String kind, Runnable click) {
        boolean hov = in(mx, my, x, y, w, ROW + 2);
        if (hov) g.fill(x, y, x + w, y + ROW + 2, HOVER);
        String kindText = Lang.t("add.kind." + kind);
        int kw = font.width(body(kindText));
        g.text(font, body(fit(name, w - kw - 12)), x + 3, y + 4, TEXT, false);
        g.text(font, body(kindText), x + w - kw - 3, y + 4, kind.equals("import") ? ACCENT : MUTED, false);
        hits.add(new Hit(x, y, w, ROW + 2, click, null));
        return y + ROW + 3;
    }

    private void addParticles(String starter) {
        LayerConfig.Layer l = ParticleStarters.make(starter, Lang.t("starter." + starter));
        List<LayerConfig.Layer> layers = assets.editable().layers;
        layers.add(l);
        selected = layers.size() - 1;
        selectedFolder = null;
        tab = Tab.EDIT;
        rightScroll = 0;
        save();
        say(Lang.t("msg.added_file", l.label), ACCENT);
    }

    private void addPreset(Presets.Info p) {
        try {
            List<LayerConfig.Layer> add = assets.presets.install(p.id());
            List<LayerConfig.Layer> layers = assets.editable().layers;
            layers.addAll(add);
            selected = layers.size() - add.size();
            String folder = add.get(0).folder;
            save();
            refreshFiles(true);
            tab = Tab.EDIT;
            rightScroll = 0;
            if (folder != null) selectFolder(folder); else selectedFolder = null;
            say(Lang.t(add.size() == 1 ? "msg.added_one" : "msg.added", p.name(), add.size()), ACCENT);
        } catch (Exception e) {
            say(Lang.t("msg.preset_failed", p.name(), e.getMessage()), DANGER);
        }
    }

    private void addMesh(String file) {
        LayerConfig.Layer l = LayerConfig.newLayer(file);
        String png = file.replaceAll("(?i)\\.json$", ".png");
        if (pngFiles.contains(png)) l.texture = png;
        List<LayerConfig.Layer> layers = assets.editable().layers;
        layers.add(l);
        selected = layers.size() - 1;
        selectedFolder = null;
        save();
        tab = Tab.EDIT;
        rightScroll = 0;
        say(Lang.t("msg.added_file", shortFile(file)), ACCENT);
    }

    // ------------------------------------------------------------------- Import view
    private void openImport(String f) {
        impFile = f;
        rightScroll = 0;
        impPng = null;
        impNotes.clear();
        try {
            String lower = f.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".gltf") || lower.endsWith(".glb")) {
                java.nio.file.Path file = assets.dir().resolve(f).normalize();
                GltfImport.Loaded ld = GltfImport.load(java.nio.file.Files.readAllBytes(file), file.getParent());
                impParsed = ld.parsed();
                impPng = ld.png();
                if (ld.textures() > 0 && ld.png() == null) impNotes.add(new ObjImport.Warning("texture_not_png"));
                else if (ld.textures() > 1) impNotes.add(new ObjImport.Warning("extra_textures", ld.textures() - 1));
            } else {
                impParsed = ObjImport.parse(assets.readFile(f));
            }
            impError = null;
        } catch (Exception e) {
            impParsed = null;
            impError = Lang.t("import.read_failed", f, e.getMessage());
        }
        reconvert();
    }

    private int importView(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w) {
        boolean bh = in(mx, my, x, y, 60, ROW);
        g.text(font, body(Lang.t("button.back")), x, y + 2, bh ? TEXT : MUTED, false);
        hits.add(new Hit(x, y, 60, ROW, () -> { impFile = null; impParsed = null; impResult = null; assets.clearPreview(); rightScroll = 0; }, null));
        y += ROW + 2;
        g.text(font, body(fit(impFile, w)), x, y, TEXT, false);
        y += 12;
        if (impError != null) {
            String ie = impError;
            y = wrapped(g, x, y, w, impError, DANGER);
            y = secondaryButton(g, mx, my, x, y + 2, w, Lang.t("button.copy_error"), () -> copyText(ie));
        } else if (impResult != null) {
            double sx = impResult.max()[0] - impResult.min()[0], sy = impResult.max()[1] - impResult.min()[1], sz = impResult.max()[2] - impResult.min()[2];
            g.text(font, body(fit(Lang.t("import.stats", String.format(Locale.ROOT, "%,d", impResult.quads()), fmt(sx), fmt(sy), fmt(sz)), w)), x, y, MUTED, false);
            y += 10;
            g.text(font, body(fit(Lang.t("import.parts", String.join(", ", impResult.groups())), w)), x, y, FAINT, false);
            y += 13;
            for (ObjImport.Warning warn : impResult.warnings()) y = wrapped(g, x, y, w, Lang.t("import.warn." + warn.key(), warn.args()), WARN);
            for (ObjImport.Warning warn : impNotes) y = wrapped(g, x, y, w, Lang.t("import.warn." + warn.key(), warn.args()), WARN);
            if (impResult.groups().size() > 1) {
                y = toggle(g, mx, my, x, y, w, "one_layer_per_part_in_a_folder", importAsFolder, () -> importAsFolder = !importAsFolder);
            }
            y = primaryButtonRow(g, mx, my, x, y, w, Lang.t(impResult.groups().size() > 1 && importAsFolder ? "button.add_as_folder" : "button.add_as_layer"), this::commitImport);
        }
        y = section(g, x, y + 4, w, "orientation");
        y = cycle(g, mx, my, x, y, w, "axes", Lang.t("import.axes." + imp.axes.name().toLowerCase(Locale.ROOT)), d -> { imp.axes = cyc(ObjImport.Axes.values(), imp.axes, d); reconvert(); });
        y = cycle(g, mx, my, x, y, w, "origin_at", Lang.t(imp.origin == ObjImport.Origin.FEET ? "import.origin.feet" : "import.origin.neck"), d -> { imp.origin = cyc(ObjImport.Origin.values(), imp.origin, d); reconvert(); });
        y = section(g, x, y + 4, w, "size");
        y = cycle(g, mx, my, x, y, w, "units", Lang.t("import.units." + imp.unitIndex), d -> { imp.unitIndex = Math.floorMod(imp.unitIndex + d, ObjImport.UNIT_VALUES.length); reconvert(); });
        double fitV = ObjImport.FIT_VALUES[imp.fitIndex];
        y = cycle(g, mx, my, x, y, w, "fit_to", fitV == 0 ? Lang.t("value.off") : Lang.t("value.blocks", fmt(fitV)), d -> { imp.fitIndex = Math.floorMod(imp.fitIndex + d, ObjImport.FIT_VALUES.length); reconvert(); });
        exact(v -> { imp.scale = Math.max(0.0001, v); reconvert(); });
        y = stepper(g, mx, my, x, y, w, "scale", fmt(imp.scale) + "×", d -> { imp.scale = Math.max(0.01, round(imp.scale * (d > 0 ? 1.1 : 1 / 1.1))); reconvert(); });
        String[] move = {"left", "down", "back"};
        for (int a = 0; a < 3; a++) {
            final int ai = a;
            double v = a == 0 ? imp.ox : a == 1 ? imp.oy : imp.oz;
            exact(nv -> { if (ai == 0) imp.ox = round(nv); else if (ai == 1) imp.oy = round(nv); else imp.oz = round(nv); reconvert(); });
            y = stepper(g, mx, my, x, y, w, move[a], fmt(v), d -> {
                double nv = round((ai == 0 ? imp.ox : ai == 1 ? imp.oy : imp.oz) + d * STEPS[stepIndex]);
                if (ai == 0) imp.ox = nv; else if (ai == 1) imp.oy = nv; else imp.oz = nv;
                reconvert();
            });
        }
        y = section(g, x, y + 4, w, "details");
        y = cycle(g, mx, my, x, y, w, "split_by", Lang.t("import.split." + imp.grouping.name().toLowerCase(Locale.ROOT)), d -> { imp.grouping = cyc(ObjImport.Grouping.values(), imp.grouping, d); reconvert(); });
        y = cycle(g, mx, my, x, y, w, "normals", Lang.t("import.normals." + imp.normals.name().toLowerCase(Locale.ROOT)), d -> { imp.normals = cyc(ObjImport.Normals.values(), imp.normals, d); reconvert(); });
        y = toggle(g, mx, my, x, y, w, "flip_texture_vertically", imp.flipV, () -> { imp.flipV = !imp.flipV; reconvert(); });
        return y;
    }

    private void reconvert() {
        impResult = null;
        if (impParsed == null) { assets.clearPreview(); return; }
        try {
            impResult = ObjImport.convert(impParsed, imp);
            if (impResult.verts() == 0) { impError = Lang.t("import.no_faces"); assets.clearPreview(); return; }
            Mesh mesh = Mesh.fromJson(Json.parse(impResult.json()));
            LayerConfig.Layer pl = LayerConfig.newLayer("(preview)");
            pl.color = 0xFF8FE8F2;
            pl.label = "import preview";
            assets.setPreview(mesh, pl);
            impError = null;
        } catch (Exception e) {
            impError = Lang.t("import.failed", e.getMessage());
            assets.clearPreview();
        }
    }

    private void commitImport() {
        if (impResult == null || impFile == null) return;
        try {
            String fileOnly = impFile.substring(impFile.lastIndexOf('/') + 1);
            String base = fileOnly.replaceAll("(?i)\\.(obj|gltf|glb)$", "");
            String name = assets.writeMesh(base, impResult.json());
            String png = Assets.sanitizeFile(base) + ".png";
            if (impPng != null && !pngFiles.contains(png)) {   // texture that came inside a glTF/GLB
                java.nio.file.Files.write(assets.dir().resolve(png), impPng);
                pngFiles = assets.files("png");
            }
            String tex = pngFiles.contains(png) ? png : pngFiles.contains(base + ".png") ? base + ".png" : null;
            List<LayerConfig.Layer> layers = assets.editable().layers;
            List<String> parts = impResult.groups();
            String folder = null;
            if (parts.size() > 1 && importAsFolder) {
                folder = assets.editable().uniqueFolderName(base);
                for (String part : parts) {
                    LayerConfig.Layer l = LayerConfig.newLayer(name);
                    l.label = part;
                    l.groups = new ArrayList<>(List.of(part));
                    l.texture = tex;
                    l.folder = folder;
                    layers.add(l);
                }
            } else {
                LayerConfig.Layer l = LayerConfig.newLayer(name);
                l.texture = tex;
                layers.add(l);
            }
            selected = layers.size() - 1;
            assets.clearPreview();
            impFile = null;
            save();
            if (folder != null) selectFolder(folder); else selectedFolder = null;
            refreshFiles(true);
            tab = Tab.EDIT;
            say(Lang.t("msg.imported", name), ACCENT);
        } catch (Exception e) {
            say(Lang.t("msg.import_save_failed", e.getMessage()), DANGER);
        }
    }

    // ------------------------------------------------------------------- Settings
    private int settingsTab(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w) {
        LayerConfig c = assets.editable();
        y = section(g, x, y, w, "visibility");
        y = toggle(g, mx, my, x, y, w, "show_layers", c.enabled, () -> { c.enabled = !c.enabled; save(); });
        String who = Lang.t("value.show_on." + c.showOn.name().toLowerCase(Locale.ROOT));
        y = cycle(g, mx, my, x, y, w, "show_on", who, d -> { c.showOn = cyc(LayerConfig.ShowOn.values(), c.showOn, d); save(); });
        if (c.showOn == LayerConfig.ShowOn.LISTED)
            y = textRow(g, mx, my, x, y, w, "players", String.join(", ", c.players), Lang.t("value.players_empty"), v -> {
                c.players.clear();
                c.players.addAll(splitPlayers(v));
                save();
            });
        y = textRow(g, mx, my, x, y, w, "me_uuids", String.join(", ", c.meUuids), Lang.t("value.none"), v -> {
            c.meUuids.clear();
            for (String u : v.split("[,\\s]+")) { String n = LayerConfig.normalizeUuid(u); if (n != null && !c.meUuids.contains(n)) c.meUuids.add(n); }
            save();
        });
        y = nearbyPlayers(g, mx, my, x, y, w, c);
        y = toggle(g, mx, my, x, y, w, "armor_stands", c.armorStands, () -> { c.armorStands = !c.armorStands; save(); });
        if (c.armorStands)
            y = textRow(g, mx, my, x, y, w, "stand_name", c.armorStandName, "", v -> { c.armorStandName = v.trim(); save(); });

        LayerConfig.Physics p = c.physics;
        y = section(g, x, y + 4, w, "motion");
        int feel = Motion.match(p);
        y = cycle(g, mx, my, x, y, w, "motion_feel", Lang.t(feel < 0 ? "value.feel.custom" : "value.feel." + Motion.NAMES[feel]), d -> {
            int cur = Motion.match(p);
            Motion.apply(p, Math.floorMod((cur < 0 ? Motion.NORMAL : cur) + d, Motion.NAMES.length));
            save();
        });
        y = toggle(g, mx, my, x, y, w, "fine_tune_motion", showMotionDetails, () -> showMotionDetails = !showMotionDetails);
        if (showMotionDetails) {
            exact(v -> { p.lag = clampF((float) v, 0, 0.2f); save(); });
            y = stepper(g, mx, my, x, y, w, "trail", fmt(p.lag), d -> { p.lag = clampF(p.lag + d * 0.005f, 0, 0.2f); save(); });
            exact(v -> { p.maxLag = clampF((float) v, 0, 1f); save(); });
            y = stepper(g, mx, my, x, y, w, "max_trail", fmt(p.maxLag), d -> { p.maxLag = clampF(p.maxLag + d * 0.02f, 0, 1f); save(); });
            exact(v -> { p.yawLag = clampF((float) v, 0, 0.5f); save(); });
            y = stepper(g, mx, my, x, y, w, "turn_lag", fmt(p.yawLag), d -> { p.yawLag = clampF(p.yawLag + d * 0.01f, 0, 0.5f); save(); });
            exact(v -> { p.maxYaw = clampF((float) v, 0, 60); save(); });
            y = stepper(g, mx, my, x, y, w, "max_turn", fmt(p.maxYaw) + "°", d -> { p.maxYaw = clampF(p.maxYaw + d, 0, 60); save(); });
            exact(v -> { p.stiffness = clampF((float) v, 1, 40); save(); });
            y = stepper(g, mx, my, x, y, w, "springiness", fmt(p.stiffness), d -> { p.stiffness = clampF(p.stiffness + d * 0.5f, 1, 40); save(); });
            exact(v -> { p.damping = clampF((float) v, 0.1f, 2f); save(); });
            y = stepper(g, mx, my, x, y, w, "damping", fmt(p.damping), d -> { p.damping = clampF(p.damping + d * 0.05f, 0.1f, 2f); save(); });
            exact(v -> { p.swayDeg = clampF((float) v, 0, 20); save(); });
            y = stepper(g, mx, my, x, y, w, "idle_sway", fmt(p.swayDeg) + "°", d -> { p.swayDeg = clampF(p.swayDeg + d * 0.25f, 0, 20); save(); });
            exact(v -> { p.swayHz = clampF((float) v, 0, 3); save(); });
            y = stepper(g, mx, my, x, y, w, "sway_speed", fmt(p.swayHz) + " Hz", d -> { p.swayHz = clampF(p.swayHz + d * 0.05f, 0, 3); save(); });
            exact(v -> { p.bob = clampF((float) v, 0, 0.2f); save(); });
            y = stepper(g, mx, my, x, y, w, "idle_bob", fmt(p.bob), d -> { p.bob = clampF(p.bob + d * 0.002f, 0, 0.2f); save(); });
            exact(v -> { p.bobHz = clampF((float) v, 0, 3); save(); });
            y = stepper(g, mx, my, x, y, w, "bob_speed", fmt(p.bobHz) + " Hz", d -> { p.bobHz = clampF(p.bobHz + d * 0.05f, 0, 3); save(); });
    }
        y = secondaryButton(g, mx, my, x, y, w, Lang.t("button.reset_motion"), () -> {
            LayerConfig.Physics def = new LayerConfig.Physics();
            p.stiffness = def.stiffness; p.damping = def.damping; p.lag = def.lag; p.maxLag = def.maxLag;
            p.yawLag = def.yawLag; p.maxYaw = def.maxYaw; p.swayDeg = def.swayDeg; p.swayHz = def.swayHz;
            p.bob = def.bob; p.bobHz = def.bobHz;
            save();
        });

        y = section(g, x, y + 4, w, "files");
        y = secondaryButton(g, mx, my, x, y, w, Lang.t("button.open_folder"), assets::openFolder);
        y = secondaryButton(g, mx, my, x, y, w, Lang.t("button.reload_disk"), () -> { assets.reload(); refreshFiles(true); say(Lang.t(assets.lastError() == null ? "msg.reloaded" : "msg.reloaded_problem"), assets.lastError() == null ? MUTED : DANGER); });
        y = wrapped(g, x, y + 2, w, Privacy.redact(assets.dir().toString()), FAINT);

        y = section(g, x, y + 4, w, "updates");
        y = toggle(g, mx, my, x, y, w, "automatic_updates", c.autoUpdate, () -> { c.autoUpdate = !c.autoUpdate; save(); Updater.recheck(c.autoUpdate, c.devUpdates); });
        if (c.autoUpdate)
            y = toggle(g, mx, my, x, y, w, "dev_updates", c.devUpdates, () -> { c.devUpdates = !c.devUpdates; save(); Updater.recheck(true, c.devUpdates); });
        Updater.State us = Updater.state();
        int uc = us == Updater.State.READY ? ACCENT : us == Updater.State.FAILED ? WARN : FAINT;
        y = wrapped(g, x, y, w, Lang.t("update." + us.name().toLowerCase(Locale.ROOT), Updater.detail()), uc);
        if (us != Updater.State.CHECKING && us != Updater.State.READY && us != Updater.State.UNAVAILABLE)
            y = secondaryButton(g, mx, my, x, y + 2, w, Lang.t("button.check_updates"), () -> Updater.checkAsync(true, c.devUpdates));
        if (assets.lastError() != null) {
            String err = assets.lastError();
            y = wrapped(g, x, y + 2, w, Lang.t("settings.problem", err), DANGER);
            y = secondaryButton(g, mx, my, x, y + 2, w, Lang.t("button.copy_error"), () -> copyText(err));
        }

        // privacy: folded away at the end until opened
        y = foldSection(g, mx, my, x, y + 4, w, "privacy", privacyOpen, () -> privacyOpen = !privacyOpen);
        if (privacyOpen) {
            String pm = Lang.t("value.privacy." + c.privacyMode.name().toLowerCase(Locale.ROOT));
            y = cycle(g, mx, my, x, y, w, "name_in_paths", pm, d -> { c.privacyMode = cyc(Privacy.Mode.values(), c.privacyMode, d); Privacy.mode = c.privacyMode; save(); });
            if (c.privacyMode == Privacy.Mode.CUSTOM) {
                int lw = labelWidth(w);
                boolean band = rowBand(g, mx, my, x, y, w);
                rowLabel(g, Lang.t("label.custom_name"), x, y, lw - 4, band);
                tip(mx, my, x, y, lw - 2, ROW, "custom_name");
                capsule(g, x + lw, y + 1, w - lw, ROW - 2, FIELD);
                textField(g, mx, my, x + lw + 4, y + 1, w - lw - 6, ROW - 2, "name:privacy", c.privacyName.isBlank() ? "player" : c.privacyName, false, TEXT, v -> {
                    c.privacyName = v.trim();
                    Privacy.customName = c.privacyName;
                    save();
                });
                y += ROW + GAP;
            }
        }

        return y;
    }

    private static boolean privacyOpen;   // Settings > Privacy starts folded each time the game starts

    // =================================================================== preview
    private void preview(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h) {
        hits.add(new Hit(x, y, w, h - 20, () -> dragging = true, d -> previewZoom = clampF(previewZoom * (d > 0 ? 1.1f : 1 / 1.1f), 0.4f, 4f)));
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.entity.player.Player player = Self.previewPlayer(assets.editable());   // the recorded player when watching a replay
        if (player == null) {
            String s = fit(Lang.t("preview.no_world"), w - 8);
            g.text(font, body(s), x + (w - font.width(body(s))) / 2, y + h / 2 - 4, MUTED, false);
            previewState = null;
        } else {
            try {
                EntityRenderer<?, ?> renderer = mc.getEntityRenderDispatcher().getRenderer(player);
                @SuppressWarnings({"unchecked", "rawtypes"})
                // the game's partial tick, so tick-based animation (walking, emotes) moves smoothly between ticks
                float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                EntityRenderState st = ((EntityRenderer) renderer).createRenderState(player, pt);
                st.shadowPieces.clear();
                st.outlineColor = 0;
                float bbh = 1.8f;
                int spin = assets.editable().captureSpin;
                if (Recorder.mode() == Recorder.Mode.RECORDING && spin > 0)   // one turn every 12 s (slow) or 6 s (fast)
                    previewYaw = ((spinFrom + Recorder.seconds() * (spin == 1 ? 30f : 60f)) % 360f + 360f) % 360f;
                if (st instanceof LivingEntityRenderState ls) {
                    if (Recorder.mode() == Recorder.Mode.RECORDING && !Recorder.driven()) {   // without the game timer hook: idle animation and sway follow the recording's clock
                        if (Float.isNaN(recordAge0)) recordAge0 = ls.ageInTicks;
                        ls.ageInTicks = recordAge0 + Recorder.seconds() * 20f;
                    } else recordAge0 = Float.NaN;
                    ls.bodyRot = 180f + previewYaw;
                    ls.yRot = 0f;
                    ls.xRot = 0f;
                    ls.boundingBoxWidth = ls.boundingBoxWidth / ls.scale;
                    ls.boundingBoxHeight = ls.boundingBoxHeight / ls.scale;
                    ls.scale = 1f;
                    bbh = ls.boundingBoxHeight;
                }
                previewState = st;
                float size = Math.max(20, Math.min(w, h - 20) / 3.6f) * previewZoom;
                Quaternionf cam = new Quaternionf().rotateX(previewPitch * 0.017453292f);
                Quaternionf rot = new Quaternionf().rotateZ((float) Math.PI).mul(cam);
                Vector3f offset = new Vector3f(0f, bbh / 2f + 0.0625f, 0f);
                Recorder.Submit sub = Recorder.submit;
                if (sub != null && Recorder.capturing()) sub.submit(g, st, size, offset, rot, cam, x, y, x + w, y + h - 18, Recorder.sizeFactor());
                else g.entity(st, size, offset, rot, cam, x, y, x + w, y + h - 18);
            } catch (Throwable t) {
                g.text(font, body(Lang.t("preview.unavailable")), x + 6, y + 6, DANGER, false);
            }
        }
        if (player != null && Recorder.submit != null) captureChips(g, mx, my, x, y, w);
        // camera chips
        String[] views = {Lang.t("preview.front"), Lang.t("preview.side"), Lang.t("preview.back")};
        float[] yaws = {0f, 90f, 180f};
        for (int i = 0; i < views.length; i++) views[i] = views[i].toUpperCase(Locale.ROOT);
        int cw = 0;
        for (String v : views) cw += strongWidth(v) + 14;
        cw += GAP * 2;
        if (cw > w - 6) {
            views = new String[]{Lang.t("preview.front_short"), Lang.t("preview.side_short"), Lang.t("preview.back_short")};
            for (int i = 0; i < views.length; i++) views[i] = views[i].toUpperCase(Locale.ROOT);
            cw = 0;
            for (String v : views) cw += strongWidth(v) + 14;
            cw += GAP * 2;
            if (cw > w - 6) return;
        }
        int cx = x + (w - cw) / 2, cy = y + h - 16;
        for (int i = 0; i < views.length; i++) {
            int vw = strongWidth(views[i]) + 14;
            boolean on = Math.abs(Math.floorMod((int) previewYaw - (int) yaws[i] + 180, 360) - 180) < 3;
            boolean hov = in(mx, my, cx, cy, vw, 13);
            slant(g, cx, cy, vw, 13, 3, on ? PAPER : hov ? FIELD_HOVER : 0xC8050B33);
            g.text(font, styled(views[i], true, true), cx + 5, cy + 3, on ? INK : hov ? PAPER : HAZE, false);
            final float ty = yaws[i];
            hits.add(new Hit(cx, cy, vw, 13, () -> { previewYaw = ty; previewPitch = -8f; }, null));
            cx += vw + GAP;
        }
    }

    /** Picture / Rec buttons in the preview's top-right corner; while recording, Stop with the time. */
    private void captureChips(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w) {
        int cy = y + 4, right = x + w - 4;
        captureAnchor = null;
        if (Recorder.mode() == Recorder.Mode.RECORDING) {
            float sec = Recorder.seconds();
            String t = Lang.t("preview.stop", String.format(Locale.ROOT, "%d:%02d", (int) sec / 60, (int) sec % 60)).toUpperCase(Locale.ROOT);
            int cw = strongWidth(t) + 20, cx = right - cw;
            boolean hov = in(mx, my, cx, cy, cw, 14);
            slant(g, cx, cy, cw, 14, 4, hov ? 0xFFFF4A66 : RED);
            disc(g, cx + 6.5f, cy + 7f, 2.6f, (System.currentTimeMillis() / 500) % 2 == 0 ? PAPER : 0x80FFFFFF);
            g.text(font, styled(t, true, true), cx + 12, cy + 3, PAPER, false);
            hits.add(new Hit(cx, cy, cw, 14, Recorder::stop, null));
            return;
        }
        if (Recorder.capturing()) return;   // a picture is being taken this frame
        if (Recorder.pending() > 0) {       // still saving a recording
            String sv = Lang.t("preview.saving", Recorder.framesWritten());
            g.text(font, body(sv), right - font.width(body(sv)), cy + 2, MUTED, false);
            return;
        }
        String[] labels = {Lang.t("preview.picture"), Lang.t("preview.record")};
        String[] tips = {"picture", "record"};
        Runnable[] acts = {this::takePicture, this::startRecording};
        int cx = right;
        for (int i = labels.length - 1; i >= 0; i--) {
            String lab = labels[i].replace("●", "").trim().toUpperCase(Locale.ROOT);
            boolean rec = i == 1;
            int menuW = rec ? 15 : 0;                       // Rec carries the capture settings: a ▼ on its right end
            int cw = strongWidth(lab) + (rec ? 20 : 14) + menuW;
            cx -= cw;
            boolean hov = in(mx, my, cx, cy, cw - menuW, 14), mh = rec && in(mx, my, cx + cw - menuW, cy, menuW, 14);
            slant(g, cx, cy, cw, 14, 4, rec ? RED : (hov ? SEA : 0xE6050B33));
            if (rec && hov) slant(g, cx, cy, cw - menuW, 14, 0, 0x30FFFFFF);
            if (rec) disc(g, cx + 6.5f, cy + 7f, 2.6f, PAPER);
            g.text(font, styled(lab, true, true), cx + (rec ? 12 : 5), cy + 3, PAPER, false);
            tip(mx, my, cx, cy, cw - menuW, 14, tips[i]);
            hits.add(new Hit(cx, cy, cw - menuW, 14, acts[i], null));
            if (rec) {
                int mx0 = cx + cw - menuW;
                g.fill(mx0, cy + 3, mx0 + 1, cy + 11, 0x99FFFFFF);
                if (mh || captureMenu) slant(g, mx0 + 1, cy, menuW - 1, 14, 4, 0x30FFFFFF);
                float tx = mx0 + 6.5f, ty = cy + 7f;
                if (captureMenu) triangle(g, tx - 3f, ty + 1.8f, tx + 3f, ty + 1.8f, tx, ty - 2.2f, PAPER);
                else triangle(g, tx - 3f, ty - 1.8f, tx + 3f, ty - 1.8f, tx, ty + 2.2f, PAPER);
                tip(mx, my, mx0, cy, menuW, 14, "capture_menu");
                hits.add(new Hit(mx0, cy, menuW, 14, () -> captureMenu = !captureMenu, null));
                captureAnchor = new int[]{cx + cw, cy + 14};
            }
            cx -= GAP;
        }
    }


    // ------------------------------------------------------------------- capture settings, under Rec
    private boolean captureMenu;
    private int[] captureAnchor;   // right edge and bottom of the Rec button this frame, or null when it isn't shown

    /** Size, frame rate and spin for pictures and recordings: a white card (the site's .card) dropping from Rec. */
    private void captureMenu(GuiGraphicsExtractor g, int mx, int my) {
        if (captureMenu && captureAnchor == null) captureMenu = false;   // recording, saving, or no preview
        if (!captureMenu) return;
        LayerConfig c = assets.editable();
        int w = 132, x = Math.max(4, captureAnchor[0] - w), y = captureAnchor[1] + 3;
        int h = 3 * 31 + 30;
        // anything outside closes it; nothing under it reacts
        hits.add(new Hit(0, 0, width, height, () -> captureMenu = false, d -> { }));
        // hard shadow, then the card with its bottom-right corner cut
        cutBox(g, x + 4, y + 4, w, h, 0x99050B33);
        cutBox(g, x, y, w, h, PAPER);
        hits.add(new Hit(x, y, w, h, () -> { }, d -> { }));
        int iy = y + 7, ix = x + 8, iw = w - 16;
        String[] sizes = {"1×", "2×", "4×"};
        int si = c.captureSize >= 4 ? 2 : c.captureSize >= 2 ? 1 : 0;
        iy = choiceRow(g, mx, my, ix, iy, iw, "capture_size", sizes, si, i -> { c.captureSize = new int[]{1, 2, 4}[i]; save(); });
        String[] fps = {"30", "60", "120"};
        int fi = c.captureFps >= 120 ? 2 : c.captureFps >= 60 ? 1 : 0;
        iy = choiceRow(g, mx, my, ix, iy, iw, "capture_fps", fps, fi, i -> { c.captureFps = new int[]{30, 60, 120}[i]; save(); });
        String[] spins = {Lang.t("value.capture_spin.0"), Lang.t("value.capture_spin.1"), Lang.t("value.capture_spin.2")};
        iy = choiceRow(g, mx, my, ix, iy, iw, "capture_spin", spins, Math.floorMod(c.captureSpin, 3), i -> { c.captureSpin = i; save(); });
        g.fill(ix, iy + 1, ix + iw, iy + 2, 0x334B5785);
        String open = Lang.t("button.open_captures").toUpperCase(Locale.ROOT) + " ›";
        boolean oh = in(mx, my, ix - 2, iy + 4, iw + 4, 13);
        g.text(font, styled(fitStrong(open, iw), true, true), ix, iy + 7, oh ? RED : SEA, false);
        hits.add(new Hit(ix - 2, iy + 4, iw + 4, 13, () -> {
            try { java.nio.file.Files.createDirectories(capturesDir()); } catch (Exception ignored) {}
            assets.openFolder(capturesDir());
        }, null));
    }

    /** One setting in the capture card: a small grey title, then its choices side by side; the chosen one is a dark slanted chip. */
    private int choiceRow(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String id, String[] opts, int chosen, IntConsumer pick) {
        g.text(font, styled(Lang.t("label." + id).toUpperCase(Locale.ROOT), true, true), x, y, 0xFF4B5785, false);
        tip(mx, my, x, y - 1, w, 10, id);
        int cy = y + 11, n = opts.length, gap = 3, cw = (w - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            int cx = x + i * (cw + gap);
            boolean on = i == chosen, hov = in(mx, my, cx, cy, cw, 13);
            slant(g, cx, cy, cw, 13, 3, on ? INK : hov ? 0xFFC9D8FF : 0xFFE8EDFF);
            String o = fitStrong(opts[i].toUpperCase(Locale.ROOT), cw - 6);
            g.text(font, styled(o, true, true), cx + (cw - 2 - strongWidth(o)) / 2, cy + 3, on ? PAPER : INK, false);
            final int k = i;
            hits.add(new Hit(cx, cy, cw, 13, () -> pick.accept(k), null));
        }
        return cy + 13 + 7;
    }

    /** A filled box with its bottom-right corner cut off. */
    private void cutBox(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill) {
        int c = 8;
        g.fill(x, y, x + w, y + h - c, fill);
        fillShape(g, y + h - c, y + h, yy -> new float[]{x, x + w - (yy - (y + h - c))}, fill);
    }

    private java.nio.file.Path capturesDir() { return assets.dir().resolve("captures"); }

    private void takePicture() {
        try {
            captureWasRecording = false;
            Recorder.picture(capturesDir(), assets.editable().captureSize, m -> captureDone = m);
        } catch (Exception e) {
            say(Lang.t("msg.capture_failed", String.valueOf(e.getMessage())), DANGER);
        }
    }

    private void startRecording() {
        try {
            captureWasRecording = true;
            spinFrom = previewYaw;
            LayerConfig c = assets.editable();
            Recorder.start(capturesDir(), c.captureSize, c.captureFps, System.nanoTime(), m -> captureDone = m);
        } catch (Exception e) {
            say(Lang.t("msg.capture_failed", String.valueOf(e.getMessage())), DANGER);
        }
    }

    // =================================================================== toast
    /** Small message at the bottom of the preview, above the camera buttons. */
    private void toast(GuiGraphicsExtractor g, int px, int pw, int bottom) {
        long now = System.currentTimeMillis();
        if (now >= toastUntil || toast.isEmpty()) return;
        String s = fit(toast, Math.min(width - 28, 280));
        int tw = font.width(body(s)) + 16;
        int tx = clamp(px + (pw - tw) / 2, 6, width - 6 - tw), ty = bottom - 38;
        g.fill(tx, ty, tx + tw, ty + 16, OVERLAY);
        g.outline(tx, ty, tw, 16, PANEL_EDGE);
        g.fill(tx + 1, ty + 1, tx + 3, ty + 15, toastColor);
        g.text(font, body(s), tx + 9, ty + 4, TEXT, false);
    }

    private void say(String s, int color) {
        toast = Privacy.redact(s);
        toastColor = color;
        toastUntil = System.currentTimeMillis() + 3500;
    }

    private void save() {
        String before = currentSnapshot;
        assets.save();
        String after = assets.snapshot();
        if (before != null && !after.equals(before)) {
            long now = System.currentTimeMillis();
            boolean merge = scrolling && lastSaveWasScroll && now - lastUndoPush < 800;
            if (!merge) {
                undoStack.push(before);
                while (undoStack.size() > 100) undoStack.removeLast();
            }
            lastUndoPush = now;
            lastSaveWasScroll = scrolling;
            redoStack.clear();
        }
        currentSnapshot = after;
        if (assets.lastError() != null) say(Lang.t("msg.saved_problem", assets.lastError()), DANGER);
    }

    private void undo() {
        commitEdit();
        if (undoStack.isEmpty()) { say(Lang.t("msg.nothing_undo"), MUTED); return; }
        redoStack.push(currentSnapshot);
        currentSnapshot = undoStack.pop();
        assets.restore(currentSnapshot);
        afterHistoryJump();
        say(Lang.t("msg.undone"), MUTED);
    }

    private void redo() {
        commitEdit();
        if (redoStack.isEmpty()) { say(Lang.t("msg.nothing_redo"), MUTED); return; }
        undoStack.push(currentSnapshot);
        currentSnapshot = redoStack.pop();
        assets.restore(currentSnapshot);
        afterHistoryJump();
        say(Lang.t("msg.redone"), MUTED);
    }

    private void afterHistoryJump() {
        lastUndoPush = 0;
        LayerConfig cfg = assets.editable();
        if (selectedFolder != null && cfg.members(selectedFolder).isEmpty()) selectedFolder = null;
        if (selected >= cfg.layers.size()) selected = Math.max(0, cfg.layers.size() - 1);
        refreshFiles(true);
    }

    private void refreshFiles(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - filesCheckedAt < 2000) return;
        filesCheckedAt = now;
        List<String> models = new ArrayList<>(assets.files("obj"));
        models.addAll(assets.files("gltf"));
        models.addAll(assets.files("glb"));
        objFiles = models;
        meshFiles = assets.files("json");
        pngFiles = assets.files("png");
        presetList = assets.presets.list();
        setZips = assets.setZips();
    }

    // =================================================================== input
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        dragging = false;
        if (editId != null) {
            if (editRect != null && in(mx, my, editRect[0], editRect[1], editRect[2], editRect[3])) return true;
            commitEdit();
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.click() != null && in(mx, my, h.x(), h.y(), h.w(), h.h())) {
                keepConfirm = false;
                h.click().run();
                if (!keepConfirm) { confirmDelete = -1; confirmFolderDelete = false; }
                return true;
            }
        }
        confirmDelete = -1;
        confirmFolderDelete = false;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            previewYaw = (previewYaw + (float) dx * 1.5f) % 360f;
            if (previewYaw < 0) previewYaw += 360f;
            previewPitch = clampF(previewPitch + (float) dy * 0.8f, -60f, 60f);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (editId != null) commitEdit();
        int d = sy > 0 ? 1 : sy < 0 ? -1 : 0;
        if (d == 0) return false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.scroll() != null && in(mx, my, h.x(), h.y(), h.w(), h.h())) {
                scrolling = true;
                try { h.scroll().accept(d); } finally { scrolling = false; }
                return true;
            }
        }
        return false;
    }

    // @port keys begin  (the 1.21.11 build swaps this block for GLFW key codes)
    // SDL scancodes (Minecraft 26.x reports physical keys as SDL scancodes)
    private static final int SC_A = 4, SC_Z = 29, SC_1 = 30, SC_0 = 39, SC_ENTER = 40, SC_ESC = 41, SC_BACKSPACE = 42,
        SC_SPACE = 44, SC_MINUS = 45, SC_COMMA = 54, SC_PERIOD = 55, SC_DELETE = 76, SC_KP_MINUS = 86, SC_KP_ENTER = 88,
        SC_KP_1 = 89, SC_KP_0 = 98, SC_KP_PERIOD = 99, SC_Y = 28, SC_V = 25,
        SC_LCTRL = 224, SC_LSHIFT = 225, SC_LGUI = 227, SC_RCTRL = 228, SC_RSHIFT = 229, SC_RGUI = 231;

    private static boolean down(int sc) {
        try { return InputConstants.isKeyDown(sc); } catch (Throwable t) { return false; }
    }

    /** The digit a key types (number row or keypad), or 0. */
    private static char digit(int k) {
        if (k >= SC_1 && k <= SC_0) return k == SC_0 ? '0' : (char) ('1' + (k - SC_1));
        if (k >= SC_KP_1 && k <= SC_KP_0) return k == SC_KP_0 ? '0' : (char) ('1' + (k - SC_KP_1));
        return 0;
    }
    // @port keys end

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if (editId != null) {
            boolean shift = down(SC_LSHIFT) || down(SC_RSHIFT);
            if (k == SC_V && (down(SC_LCTRL) || down(SC_RCTRL) || down(SC_LGUI) || down(SC_RGUI))) { paste(); return true; }
            if (k == SC_ENTER || k == SC_KP_ENTER) { commitEdit(); return true; }
            if (k == SC_ESC) { cancelEdit(); return true; }
            if (k == SC_BACKSPACE || k == SC_DELETE) {
                if (editReplace) editBuf.setLength(0); else if (editBuf.length() > 0) editBuf.setLength(editBuf.length() - 1);
                editReplace = false;
                return true;
            }
            char c = 0;
            if (digit(k) != 0) c = digit(k);
            else if (k == SC_PERIOD || k == SC_KP_PERIOD || k == SC_COMMA) c = editNumeric ? '.' : (k == SC_COMMA ? ',' : '.');
            else if (k == SC_MINUS || k == SC_KP_MINUS) c = shift && !editNumeric ? '_' : '-';
            else if (!editNumeric && k >= SC_A && k <= SC_Z) c = (char) ((shift ? 'A' : 'a') + (k - SC_A));
            else if (!editNumeric && k == SC_SPACE) c = ' ';
            if (c != 0) {
                if (editReplace) editBuf.setLength(0);
                editReplace = false;
                if (editBuf.length() < (editNumeric ? 40 : 200)) editBuf.append(c);
            }
            return true;   // swallow everything else while typing (so letters don't trigger keybinds)
        }
        boolean ctrl = down(SC_LCTRL) || down(SC_RCTRL) || down(SC_LGUI) || down(SC_RGUI);
        if (ctrl && k == SC_Z) { if (down(SC_LSHIFT) || down(SC_RSHIFT)) redo(); else undo(); return true; }
        if (ctrl && k == SC_Y) { redo(); return true; }
        if (k == SC_ESC) { close(); return true; }
        return super.keyPressed(event);
    }

    /** Ctrl+V while typing: adds the clipboard's text (first line, printable characters only). */
    private void paste() {
        String clip;
        try { clip = Minecraft.getInstance().keyboardHandler.getClipboard(); } catch (Throwable t) { return; }
        if (clip == null) return;
        StringBuilder add = new StringBuilder();
        for (char ch : clip.toCharArray()) {
            if (ch == '\n' || ch == '\r') break;
            if (ch >= ' ' && ch != 127 && (!editNumeric || "0123456789.-".indexOf(ch) >= 0)) add.append(ch);
        }
        if (add.length() == 0) return;
        if (editReplace) editBuf.setLength(0);
        editReplace = false;
        int room = (editNumeric ? 40 : 200) - editBuf.length();
        editBuf.append(add, 0, Math.max(0, Math.min(room, add.length())));
    }

    private void beginEdit(String id, String initial, boolean numeric, Consumer<String> commit) {
        commitEdit();
        editId = id;
        editBuf.setLength(0);
        editBuf.append(initial);
        editReplace = true;
        editNumeric = numeric;
        editCommit = commit;
    }

    private void commitEdit() {
        if (editId == null) return;
        String v = editBuf.toString();
        Consumer<String> c = editCommit;
        editId = null;
        editCommit = null;
        if (c != null) c.accept(v);
    }

    private void cancelEdit() {
        editId = null;
        editCommit = null;
    }

    /** Text that turns into an input box when clicked (Enter saves, Esc cancels). */
    private void textField(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h, String id, String value, boolean numeric, int color, Consumer<String> commit) {
        if (id.equals(editId)) {
            g.fill(x - 2, y, x + w, y + h, FIELD);
            g.outline(x - 2, y, w + 2, h, ACCENT);
            String shown = editBuf.toString();
            boolean caret = (System.currentTimeMillis() / 500) % 2 == 0;
            String fitted = fitTail(shown, w - 8);
            if (editReplace && !shown.isEmpty()) g.fill(x, y + 2, x + font.width(body(fitted)) + 1, y + h - 2, ACCENT_DIM);
            g.text(font, body(fitted), x, y + (h - 8) / 2, TEXT, false);
            if (caret) { int cx = x + font.width(body(fitted)) + 1; g.fill(cx, y + 2, cx + 1, y + h - 2, TEXT); }
            editRect = new int[]{x - 2, y, w + 2, h};
            return;
        }
        boolean hov = in(mx, my, x - 2, y, w + 2, h);
        String shown = fit(value, w - (hov ? 30 : 0));
        g.text(font, body(shown), x, y + (h - 8) / 2, color, false);
        if (hov) {
            g.fill(x, y + h - 1, x + font.width(body(shown)), y + h, FAINT);
            g.text(font, body(Lang.t("field.edit_hint")), x + w - font.width(body(Lang.t("field.edit_hint"))), y + (h - 8) / 2, FAINT, false);
        }
        hits.add(new Hit(x - 2, y, w + 2, h, () -> beginEdit(id, value, numeric, commit), null));
    }

    private String fitTail(String s, int w) {
        if (font.width(body(s)) <= w) return s;
        int i = 0;
        while (i < s.length() && font.width(body("…" + s.substring(i))) > w) i++;
        return "…" + s.substring(i);
    }

    /** Registers an exact-value setter for the next stepper (its value can then be clicked and typed). */
    private void exact(DoubleConsumer setter) { nextExact = setter; }

    private static Double parseNumber(String s) {
        String t = s.replace(',', '.').replaceAll("[^0-9.\\-]", "");
        if (t.isEmpty() || t.equals("-") || t.equals(".")) return null;
        try { return Double.parseDouble(t); } catch (NumberFormatException e) { return null; }
    }

    // =================================================================== widgets
    // ------------------------------------------------------------------- the site's shapes
    // Drawn in real screen pixels (not GUI pixels), so slants, triangles and round ends stay smooth
    // at every GUI scale.

    /** The website's display font: Archivo Condensed Black Italic (SIL Open Font License; assets/rainium/font). */
    private static final FontDescription DISPLAY = new FontDescription.Resource(Identifier.fromNamespaceAndPath("rainium", "display"));

    /** The website's text font: Inter (SIL Open Font License; assets/rainium/font). Both fonts fall back to each other before
     *  Minecraft's, so resource packs can't mix pixel letters or symbols into the editor. */
    private static final FontDescription BODY = new FontDescription.Resource(Identifier.fromNamespaceAndPath("rainium", "body"));

    /** Text in the editor's text font. */
    private static MutableComponent body(String s) {
        return Component.literal(s == null ? "" : s).withStyle(st -> st.withFont(BODY));
    }

    private static Component body(Component c) { return c; }

    /** Text styles: bold + italic means the site's display font (tabs, headings, buttons); otherwise the text font. */
    private static Component styled(String s, boolean bold, boolean italic) {
        if (bold && italic) return Component.literal(s).withStyle(st -> st.withFont(DISPLAY));
        MutableComponent c = body(s);
        if (bold) return c.withStyle(ChatFormatting.BOLD);
        return italic ? c.withStyle(ChatFormatting.ITALIC) : c;
    }

    private int strongWidth(String s) { return font.width(styled(s, true, true)); }

    /** Like fit(), for bold text. */
    private String fitStrong(String s, int w) {
        if (s == null || w <= 0) return "";
        if (strongWidth(s) <= w) return s;
        int n = s.length();
        while (n > 0 && strongWidth(s.substring(0, n) + "…") > w) n--;
        return s.substring(0, n) + "…";
    }

    private static float guiScale() {
        try { return Math.max(1f, (float) Minecraft.getInstance().getWindow().getGuiScale()); } catch (Throwable t) { return 1f; }
    }

    /** Left and right edge of a shape at height y (GUI units), or null where the row is empty. */
    private interface Span { float[] at(float y); }

    /** Fills a shape one screen-pixel row at a time. */
    private void fillShape(GuiGraphicsExtractor g, float top, float bottom, Span span, int color) {
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

    /** A box whose right edge is cut at an angle (the site's buttons and tags); cut = how far the bottom-right corner moves in. */
    private void slant(GuiGraphicsExtractor g, int x, int y, int w, int h, int cut, int color) {
        fillShape(g, y, y + h, yy -> new float[]{x, x + w - cut * (yy - y) / h}, color);
    }

    /** A pill with round ends. */
    private void capsule(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        float r = h / 2f, cy = y + r;
        fillShape(g, y, y + h, yy -> {
            float dy = yy - cy, d = r * r - dy * dy;
            if (d < 0) return null;
            float dx = (float) Math.sqrt(d);
            return new float[]{x + r - dx, x + w - r + dx};
        }, color);
    }

    private void disc(GuiGraphicsExtractor g, float cx, float cy, float r, int color) {
        fillShape(g, cy - r, cy + r, yy -> {
            float d = r * r - (yy - cy) * (yy - cy);
            if (d < 0) return null;
            float dx = (float) Math.sqrt(d);
            return new float[]{cx - dx, cx + dx};
        }, color);
    }

    /** A ring: a disc with its middle left empty (two spans per row are drawn as two shapes). */
    private void ring(GuiGraphicsExtractor g, float cx, float cy, float r, float thick, int color) {
        float ri = r - thick;
        for (int side = -1; side <= 1; side += 2) {
            final int sd = side;
            fillShape(g, cy - r, cy + r, yy -> {
                float dy = yy - cy, d = r * r - dy * dy;
                if (d < 0) return null;
                float o = (float) Math.sqrt(d), di = ri * ri - dy * dy, in = di > 0 ? (float) Math.sqrt(di) : 0f;
                return sd < 0 ? new float[]{cx - o, cx - in} : new float[]{cx + in, cx + o};
            }, color);
        }
    }

    private void triangle(GuiGraphicsExtractor g, float ax, float ay, float bx, float by, float cx, float cy, int color) {
        fillShape(g, Math.min(ay, Math.min(by, cy)), Math.max(ay, Math.max(by, cy)), yy -> triSpan(ax, ay, bx, by, cx, cy, yy), color);
    }

    private static float[] triSpan(float ax, float ay, float bx, float by, float cx, float cy, float y) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        float[][] e = {{ax, ay, bx, by}, {bx, by, cx, cy}, {cx, cy, ax, ay}};
        for (float[] s : e) {
            if (s[1] == s[3] || y < Math.min(s[1], s[3]) || y > Math.max(s[1], s[3])) continue;
            float x = s[0] + (y - s[1]) * (s[2] - s[0]) / (s[3] - s[1]);
            lo = Math.min(lo, x); hi = Math.max(hi, x);
        }
        return lo < hi ? new float[]{lo, hi} : null;
    }

    // ------------------------------------------------------------------- the triangle selection
    private final java.util.Map<String, Object> selWhat = new java.util.HashMap<>();
    private final java.util.Map<String, Long> selSince = new java.util.HashMap<>();

    /** Where a stabbing point is after t seconds (from = start, to = end, 0..1), easing out past the end and back, as on the site. */
    private static float stab(float t, float dur, float from, float over, float to) {
        if (t <= 0) return from;
        float p = Math.min(1f, t / dur);
        if (p < 0.7f) { float q = 1f - p / 0.7f; return from + (over - from) * (1f - q * q * q); }
        return over + (to - over) * (p - 0.7f) / 0.3f;
    }

    /**
     * The site's selection: bold italic text pierced by a white triangle pointing right, with a red twin
     * behind it; the letters turn red inside the white one. Both stab in when "what" changes for this key,
     * then the white one drifts gently.
     */
    private void selection(GuiGraphicsExtractor g, String key, Object what, String text, int x, int y) {
        long now = System.currentTimeMillis();
        if (!java.util.Objects.equals(selWhat.get(key), what)) { selWhat.put(key, what); selSince.put(key, now); }
        float t = (now - selSince.get(key)) / 1000f;
        Component c = styled(text, true, true);
        int tw = font.width(c);
        // the text's box, padded like the site's .pick
        float bx = x - 2, by = y - 2.5f, bw = tw + 5, bh = 12;
        // red twin, a touch higher and longer, a moment later
        float rTip = stab(t - 0.03f, 0.21f, 0.0f, 1.08f, 1.05f);
        triangle(g, bx - 0.06f * bw, by - 0.36f * bh, bx + 0.03f * bw, by + 0.98f * bh, bx + rTip * bw, by + 0.24f * bh, 0xA8F2163A);
        // white: stabs in, then drifts
        float d = (float) Math.sin(Math.max(0f, t - 0.17f) * Math.PI / 3.4f);
        float ax = bx + (-0.12f - 0.015f * d) * bw, ay = by + (-0.16f - 0.02f * d) * bh;
        float bbx = bx + (-0.05f + 0.01f * d) * bw, bby = by + (1.16f - 0.02f * d) * bh;
        float cxp = bx + (stab(t, 0.17f, -0.08f, 1.0f, 0.96f) + 0.01f * d) * bw, cyp = by + (0.5f - 0.025f * d) * bh;
        triangle(g, ax, ay, bbx, bby, cxp, cyp, PAPER);
        g.text(font, c, x, y, INK, false);
        // the red copy, cut to the white triangle one GUI row at a time
        for (int r = 0; r < 9; r++) {
            float[] sp = triSpan(ax, ay, bbx, bby, cxp, cyp, y + r + 0.5f);
            if (sp == null) continue;
            int l = Math.max(x, (int) Math.floor(sp[0])), rr = Math.min(x + tw + 1, (int) Math.floor(sp[1]));
            if (rr <= l) continue;
            g.enableScissor(l, y + r, rr, y + r + 1);
            g.text(font, c, x, y, RED, false);
            g.disableScissor();
        }
    }

    // ------------------------------------------------------------------- panels and headings
    /** The site's dialogue box: deep navy, a white border, the bottom-right corner cut off. */
    private void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) { box(g, x, y, w, h, PANEL); }

    /** The same box in see-through blue over the blurred game, with a faint sheen at the top. */
    private void glassPanel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        box(g, x, y, w, h, GLASS);
        g.fillGradient(x + 1, y + 1, x + w - 1, y + Math.min(h, 60), 0x1EFFFFFF, 0x00FFFFFF);
    }

    private void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill) {
        int c = 7;
        g.fill(x, y, x + w, y + h - c, fill);
        fillShape(g, y + h - c, y + h, yy -> new float[]{x, x + w - (yy - (y + h - c))}, fill);
        g.fill(x, y, x + w, y + 1, PANEL_EDGE);
        g.fill(x, y + 1, x + 1, y + h, PANEL_EDGE);
        g.fill(x + 1, y + h - 1, x + w - c, y + h, PANEL_EDGE);
        g.fill(x + w - 1, y + 1, x + w, y + h - c, PANEL_EDGE);
        fillShape(g, y + h - c, y + h, yy -> { float e = x + w - (yy - (y + h - c)); return new float[]{e - 1.2f, e}; }, PANEL_EDGE);
    }

    /** A white slanted tag with bold italic dark text (the site's name tags). Returns its width. */
    private int nameTag(GuiGraphicsExtractor g, int x, int y, String s) {
        int w = strongWidth(s) + 13;
        slant(g, x, y, w, 11, 3, PAPER);
        g.text(font, styled(s, true, true), x + 4, y + 2, INK, false);
        return w;
    }

    /** A section heading (id names "section.&lt;id&gt;" in the lang file): a name tag and a fading line. */
    private int section(GuiGraphicsExtractor g, int x, int y, int w, String id) {
        String title = Lang.t("section." + id).toUpperCase(Locale.ROOT);
        int tw = nameTag(g, x, y + 2, fitStrong(title, w - 30));
        int lx = x + tw + 4, n = 6;
        for (int i = 0; i < n; i++) g.fill(lx + (w - tw - 4) * i / n, y + 7, lx + (w - tw - 4) * (i + 1) / n, y + 8, ((0x80 - i * 0x14) << 24) | 0xFFFFFF);
        return y + 17;
    }

    /** A section heading that folds its rows away: a click on it opens or closes them. */
    private int foldSection(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String id, boolean open, Runnable flip) {
        boolean hov = in(mx, my, x - 2, y, w + 4, 15);
        String title = Lang.t("section." + id).toUpperCase(Locale.ROOT);
        int tw = nameTag(g, x, y + 2, fitStrong(title, w - 46));
        // a small triangle: pointing right when folded, down when open
        float cx = x + tw + 7, cy = y + 7.5f;
        int col = hov ? PAPER : CYAN;
        if (open) triangle(g, cx - 3.5f, cy - 2f, cx + 3.5f, cy - 2f, cx, cy + 2.5f, col);
        else triangle(g, cx - 2f, cy - 3.5f, cx - 2f, cy + 3.5f, cx + 2.5f, cy, col);
        int lx = x + tw + 14, n = 6;
        for (int i = 0; i < n; i++) g.fill(lx + (w - tw - 14) * i / n, y + 7, lx + (w - tw - 14) * (i + 1) / n, y + 8, ((0x80 - i * 0x14) << 24) | 0xFFFFFF);
        hits.add(new Hit(x - 2, y, w + 4, 15, flip, null));
        return y + 17;
    }

    /** The row under the mouse, as in the game's settings: a white band with a red line under it. */
    private boolean rowBand(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w) {
        if (!in(mx, my, x - 6, y - 1, w + 9, ROW + 2) || editId != null) return false;
        g.fill(x - 6, y - 1, x + w + 3, y + ROW + 1, 0xF2FFFFFF);
        g.fill(x - 6, y + ROW, x + w + 3, y + ROW + 1, RED);
        return true;
    }

    private void rowLabel(GuiGraphicsExtractor g, String text, int x, int y, int w, boolean band) {
        g.text(font, styled(fit(text, w), false, true), x, y + 3, band ? INK : MENU, false);
    }

    private void primaryButton(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, Runnable click) {
        boolean hov = in(mx, my, x, y, w, ROW + 2);
        slant(g, x, y, w, ROW + 2, 4, hov ? PAPER : ACCENT);
        String s = fitStrong(label.toUpperCase(Locale.ROOT), w - 8);
        g.text(font, styled(s, true, true), x + (w - 3 - strongWidth(s)) / 2, y + 4, INK, false);
        hits.add(new Hit(x, y, w, ROW + 2, click, null));
    }

    private int primaryButtonRow(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, Runnable click) {
        primaryButton(g, mx, my, x, y, w, label, click);
        return y + ROW + 2 + GAP + 2;
    }

    private int secondaryButton(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, Runnable click) {
        boolean hov = in(mx, my, x, y, w, ROW);
        slant(g, x, y, w, ROW, 4, hov ? BRIGHT : SEA);
        String s = fitStrong(label, w - 8);
        g.text(font, styled(s, true, true), x + (w - 3 - strongWidth(s)) / 2, y + 3, PAPER, false);
        hits.add(new Hit(x, y, w, ROW, click, null));
        return y + ROW + GAP;
    }

    private void iconButton(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, boolean enabled, Runnable click) {
        boolean hov = enabled && in(mx, my, x, y, w, ROW);
        slant(g, x, y, w, ROW, 4, !enabled ? 0xB0050B33 : hov ? BRIGHT : SEA);
        String s = fitStrong(label, w - 6);
        g.text(font, styled(s, true, true), x + (w - 3 - strongWidth(s)) / 2, y + 3, enabled ? PAPER : FAINT, false);
        if (enabled) hits.add(new Hit(x, y, w, ROW, click, null));
    }

    /** ON / OFF as the game's settings show it: a dark pill with the chosen side in white. */
    private void drawSwitch(GuiGraphicsExtractor g, int x, int y, boolean on) {
        int w = 48, h = 12, half = w / 2;
        capsule(g, x, y, w, h, FIELD);
        capsule(g, on ? x + 1 : x + half - 1, y + 1, half, h - 2, PAPER);
        String a = Lang.t("value.on").toUpperCase(Locale.ROOT), b = Lang.t("value.off").toUpperCase(Locale.ROOT);
        g.text(font, body(a), x + (half - font.width(body(a))) / 2 + 1, y + 2, on ? INK : FAINT, false);
        g.text(font, body(b), x + half + (half - font.width(body(b))) / 2 - 1, y + 2, on ? FAINT : INK, false);
    }

    private int labelWidth(int w) { return Math.min(70, w * 2 / 5); }

    /** "Label        ‹ value ›" — click either half or scroll to change. */
    private int cycle(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String id, String value, IntConsumer step) {
        int lw = labelWidth(w);
        boolean band = rowBand(g, mx, my, x, y, w);
        rowLabel(g, Lang.t("label." + id), x, y, lw - 4, band);
        tip(mx, my, x, y, lw - 2, ROW, id);
        int bx = x + lw, bw = w - lw;
        boolean hl = in(mx, my, bx, y, bw / 2, ROW), hr = in(mx, my, bx + bw / 2, y, bw - bw / 2, ROW);
        capsule(g, bx, y + 1, bw, ROW - 2, hl || hr ? FIELD_HOVER : FIELD);
        g.text(font, body("‹"), bx + 5, y + 3, hl ? PAPER : CYAN, false);
        g.text(font, body("›"), bx + bw - 9, y + 3, hr ? PAPER : CYAN, false);
        String v = fit(value, bw - 24);
        g.text(font, body(v), bx + (bw - font.width(body(v))) / 2, y + 3, TEXT, false);
        hits.add(new Hit(bx, y, bw / 2, ROW, () -> step.accept(-1), step));
        hits.add(new Hit(bx + bw / 2, y, bw - bw / 2, ROW, () -> step.accept(1), step));
        return y + ROW + GAP;
    }

    /** "Label        − value +" — buttons or scroll wheel. */
    private int stepper(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String id, String value, IntConsumer step) {
        DoubleConsumer setter = nextExact;
        nextExact = null;
        int lw = labelWidth(w);
        boolean band = rowBand(g, mx, my, x, y, w);
        rowLabel(g, Lang.t("label." + id), x, y, lw - 4, band);
        tip(mx, my, x, y, lw - 2, ROW, id);
        int bx = x + lw, bw = w - lw;
        String fieldId = "num:" + id;
        capsule(g, bx, y + 1, bw, ROW - 2, FIELD);
        boolean hm = in(mx, my, bx, y, 14, ROW), hp = in(mx, my, bx + bw - 14, y, 14, ROW);
        if (hm) disc(g, bx + 6.5f, y + ROW / 2f, 4.5f, FIELD_HOVER);
        if (hp) disc(g, bx + bw - 6.5f, y + ROW / 2f, 4.5f, FIELD_HOVER);
        g.text(font, body("−"), bx + 4, y + 3, hm ? PAPER : CYAN, false);
        g.text(font, body("+"), bx + bw - 9, y + 3, hp ? PAPER : CYAN, false);
        hits.add(new Hit(bx, y, 14, ROW, () -> step.accept(-1), null));
        hits.add(new Hit(bx + bw - 14, y, 14, ROW, () -> step.accept(1), null));
        if (setter != null && fieldId.equals(editId)) {
            textField(g, mx, my, bx + 16, y + 1, bw - 32, ROW - 2, fieldId, value, true, TEXT, null);
            return y + ROW + GAP;
        }
        boolean hv = setter != null && in(mx, my, bx + 14, y, bw - 28, ROW);
        String v = fit(value, bw - 30);
        int vw = font.width(body(v)), vx = bx + (bw - vw) / 2;
        if (hv) g.fill(vx, y + ROW - 3, vx + vw, y + ROW - 2, CYAN);   // click to type a value
        g.text(font, body(v), vx, y + 3, TEXT, false);
        Runnable startEdit = setter == null ? null : () -> {
            Double cur = parseNumber(value);
            beginEdit(fieldId, cur == null ? "" : fmt(cur), true, txt -> {
                Double n = parseNumber(txt);
                if (n != null) setter.accept(n);
            });
        };
        hits.add(new Hit(bx + 14, y, bw - 28, ROW, startEdit, step));
        return y + ROW + GAP;
    }

    private int toggle(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String id, boolean on, Runnable flip) {
        boolean band = rowBand(g, mx, my, x, y, w);
        rowLabel(g, Lang.t("label." + id), x, y, w - 54, band);
        tip(mx, my, x, y, w - 50, ROW, id);
        drawSwitch(g, x + w - 48, y + 1, on);
        hits.add(new Hit(x, y, w, ROW, flip, null));
        return y + ROW + GAP;
    }

    /**
     * "Shown on" settings for a layer (folder = the layer's folder, or null) or a folder (folder = null).
     * "Default" uses the folder's choice, then Settings > Visibility; the default in force is shown in brackets.
     */
    private int audience(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, LayerConfig.Audience a, LayerConfig.Folder folder) {
        LayerConfig c = assets.editable();
        y = section(g, x, y, w, "shown_on");
        LayerConfig.ShowOn inheritedMode = folder != null && folder.show.players != null ? folder.show.players : c.showOn;
        List<String> inheritedNames = folder != null && folder.show.names != null ? folder.show.names : c.players;
        List<String> inheritedStands = folder != null && folder.show.stands != null ? folder.show.stands
            : c.armorStands && !c.armorStandName.isBlank() ? List.of(c.armorStandName) : List.of();

        // players: Default, then each choice
        LayerConfig.ShowOn[] modes = LayerConfig.ShowOn.values();
        String pv = a.players == null
            ? Lang.t("value.default_is", Lang.t("value.show_on." + inheritedMode.name().toLowerCase(Locale.ROOT)))
            : Lang.t("value.show_on." + a.players.name().toLowerCase(Locale.ROOT));
        y = cycle(g, mx, my, x, y, w, "on_players", pv, d -> {
            int i = a.players == null ? -1 : a.players.ordinal();
            int n = Math.floorMod(i + 1 + d, modes.length + 1) - 1;   // -1 = Default
            a.players = n < 0 ? null : modes[n];
            if (a.players == LayerConfig.ShowOn.LISTED && a.names == null) a.names = new ArrayList<>(inheritedNames);
            if (a.players != LayerConfig.ShowOn.LISTED) a.names = null;
            save();
        });
        if (a.players == LayerConfig.ShowOn.LISTED) {
            y = textRow(g, mx, my, x, y, w, "players", String.join(", ", a.names), Lang.t("value.players_empty"), v -> {
                a.names = splitPlayers(v);
                save();
            });
        }

        // armor stands: Default / None / Named
        String sv = a.stands == null
            ? Lang.t("value.default_is", inheritedStands.isEmpty() ? Lang.t("value.stands.none") : String.join(", ", inheritedStands))
            : a.stands.isEmpty() ? Lang.t("value.stands.none") : Lang.t("value.stands.named");
        y = cycle(g, mx, my, x, y, w, "on_stands", sv, d -> {
            int i = a.stands == null ? 0 : a.stands.isEmpty() ? 1 : 2;
            int n = Math.floorMod(i + d, 3);
            a.stands = n == 0 ? null : n == 1 ? new ArrayList<>() : new ArrayList<>(inheritedStands.isEmpty() ? List.of("Rainium") : inheritedStands);
            save();
        });
        if (a.stands != null && !a.stands.isEmpty()) {
            y = textRow(g, mx, my, x, y, w, "stand_names", String.join(", ", a.stands), Lang.t("value.players_empty"), v -> {
                a.stands = splitNames(v);
                save();
            });
        }
        return y;
    }

    /** Player names: separated by commas or spaces (names can't contain spaces). */
    /** Players loaded around you (in a replay: the recorded players), each with a button to count it as "me". */
    private int nearbyPlayers(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, LayerConfig c) {
        List<net.minecraft.world.entity.player.Player> ps;
        try { ps = Self.nearbyPlayers(6); } catch (Throwable t) { return y; }
        if (ps.isEmpty()) return y;
        g.text(font, body(fit(Lang.t("label.players_here"), w)), x, y + 2, FAINT, false);
        tip(mx, my, x, y, w, 11, "players_here");
        y += 12;
        for (net.minecraft.world.entity.player.Player p : ps) {
            String uuid = p.getUUID().toString();   // the entity UUID, as Flashback shows it
            boolean me = Self.listed(p, c);
            String chip = Lang.t(me ? "button.is_me" : "button.add_me");
            int cw = font.width(body(chip)) + 10, cx = x + w - cw;
            String label = fit(p.getName().getString() + "  " + uuid.substring(0, 8) + "…", cx - x - 6);
            g.text(font, body(label), x + 4, y + 2, TEXT, false);
            boolean hov = in(mx, my, cx, y, cw, 12);
            g.fill(cx, y, cx + cw, y + 12, me ? ACCENT_DIM : hov ? FIELD_HOVER : FIELD);
            g.text(font, body(chip), cx + 5, y + 2, me ? TEXT : MUTED, false);
            hits.add(new Hit(cx, y, cw, 12, () -> {
                if (me) { c.meUuids.remove(uuid); try { c.meUuids.remove(p.getGameProfile().id().toString()); } catch (Throwable ignored) { } }
                else c.meUuids.add(uuid);
                save();
            }, null));
            y += 13;
        }
        return y + GAP;
    }

    private static List<String> splitPlayers(String v) { return split(v, "[,\\s]+"); }

    /** Armor stand names: separated by commas (name tags can contain spaces). */
    private static List<String> splitNames(String v) { return split(v, ","); }

    private static List<String> split(String v, String sep) {
        List<String> out = new ArrayList<>();
        for (String n : v.split(sep)) if (!n.isBlank()) out.add(n.trim());
        return out;
    }

    /** Particle texture: Minecraft's particle pictures first, then PNGs from the rainium folder. */
    private int particleTexture(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, LayerConfig.Layer l) {
        String vn = ParticleSim.vanillaName(l.texture);
        String shown = vn != null ? Lang.t("particle." + vn) : l.texture == null ? Lang.t("value.none") : shortFile(l.texture);
        return cycle(g, mx, my, x, y, w, "particle_texture", shown, d -> {
            List<String> opts = new ArrayList<>();
            for (String n : ParticleSim.VANILLA) opts.add(ParticleSim.vanillaId(n));
            opts.addAll(pngFiles);
            if (l.texture != null && !opts.contains(l.texture)) opts.add(l.texture);
            int i = opts.indexOf(l.texture);
            l.texture = opts.get(Math.floorMod((i < 0 ? -1 : i) + d, opts.size()));
            save();
        });
    }

    /** How a particle emitter spawns and moves its particles. */
    private int particleSettings(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, LayerConfig.Particles p) {
        y = section(g, x, y, w, "particles");
        y = cycle(g, mx, my, x, y, w, "p_when", Lang.t("value.p_when." + p.when.name().toLowerCase(Locale.ROOT)), d -> { p.when = cyc(LayerConfig.Particles.When.values(), p.when, d); save(); });
        exact(v -> { p.rate = clampF((float) v, 0, 200); save(); });
        y = stepper(g, mx, my, x, y, w, "p_rate", fmt(p.rate) + "/s", d -> { p.rate = clampF(p.rate + d * (p.rate < 10 ? 1 : 5), 0, 200); save(); });
        exact(v -> { p.life = clampF((float) v, 0.05f, 20); save(); });
        y = stepper(g, mx, my, x, y, w, "p_life", fmt(p.life) + " s", d -> { p.life = clampF(p.life + d * 0.25f, 0.05f, 20); save(); });
        y = toggle(g, mx, my, x, y, w, "p_follow", p.follow, () -> { p.follow = !p.follow; save(); });

        y = section(g, x, y + 4, w, "p_spawn");
        y = cycle(g, mx, my, x, y, w, "p_shape", Lang.t("value.p_shape." + p.shape.name().toLowerCase(Locale.ROOT)), d -> { p.shape = cyc(LayerConfig.Particles.Shape.values(), p.shape, d); save(); });
        if (p.shape != LayerConfig.Particles.Shape.POINT) {
            exact(v -> { p.area = clampF((float) v, 0, 4); save(); });
            y = stepper(g, mx, my, x, y, w, "p_area", fmt(p.area), d -> { p.area = clampF(p.area + d * 0.05f, 0, 4); save(); });
        }
        exact(v -> { p.size = clampF((float) v, 0.005f, 2); save(); });
        y = stepper(g, mx, my, x, y, w, "p_size", fmt(p.size), d -> { p.size = clampF(p.size + d * 0.01f, 0.005f, 2); save(); });
        exact(v -> { p.endSize = clampF((float) v, 0, 2); save(); });
        y = stepper(g, mx, my, x, y, w, "p_end_size", fmt(p.endSize), d -> { p.endSize = clampF(p.endSize + d * 0.01f, 0, 2); save(); });
        y = toggle(g, mx, my, x, y, w, "p_fade", p.fade, () -> { p.fade = !p.fade; save(); });

        y = section(g, x, y + 4, w, "p_motion");
        y = cycle(g, mx, my, x, y, w, "p_direction", Lang.t("value.p_direction." + p.direction.name().toLowerCase(Locale.ROOT)), d -> { p.direction = cyc(LayerConfig.Particles.Direction.values(), p.direction, d); save(); });
        exact(v -> { p.speed = clampF((float) v, 0, 10); save(); });
        y = stepper(g, mx, my, x, y, w, "p_speed", fmt(p.speed), d -> { p.speed = clampF(p.speed + d * 0.05f, 0, 10); save(); });
        exact(v -> { p.spread = clampF((float) v, 0, 180); save(); });
        y = stepper(g, mx, my, x, y, w, "p_spread", fmt(p.spread) + "°", d -> { p.spread = clampF(p.spread + d * 5, 0, 180); save(); });
        exact(v -> { p.gravity = clampF((float) v, -20, 20); save(); });
        y = stepper(g, mx, my, x, y, w, "p_gravity", fmt(p.gravity), d -> { p.gravity = clampF(p.gravity + d * 0.1f, -20, 20); save(); });
        exact(v -> { p.drag = clampF((float) v, 0, 10); save(); });
        y = stepper(g, mx, my, x, y, w, "p_drag", fmt(p.drag), d -> { p.drag = clampF(p.drag + d * 0.1f, 0, 10); save(); });
        exact(v -> { p.spin = clampF((float) v, -720, 720); save(); });
        y = stepper(g, mx, my, x, y, w, "p_spin", fmt(p.spin) + "°/s", d -> { p.spin = clampF(p.spin + d * 15, -720, 720); save(); });
        y = cycle(g, mx, my, x, y, w, "p_angle", Lang.t(p.upright ? "value.p_angle.upright" : "value.p_angle.random"), d -> { p.upright = !p.upright; save(); });
        return y;
    }

    /** Hue shift: a stepper (type or scroll for exact values) and a strip to click a shift directly. */
    private int hue(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, LayerConfig.Layer l) {
        exact(v -> { l.hue = Hue.normalize((float) round(v)); save(); });
        y = stepper(g, mx, my, x, y, w, "hue", hueText(l.hue), d -> { l.hue = Hue.normalize(l.hue + d * HUE_STEPS[stepIndex]); save(); });
        // the strip shows what a red pixel becomes at each shift from -180° (left) to +180° (right)
        int lw = labelWidth(w), bx = x + lw, bw = w - lw, n = 24, sy = y - GAP, sh = 4;
        for (int i = 0; i < n; i++) {
            int x0 = bx + bw * i / n, x1 = bx + bw * (i + 1) / n;
            float shift = -180f + 360f * (i + 0.5f) / n;
            g.fill(x0, sy, x1, sy + sh, Hue.rotateArgb(0xFFE84A4A, shift));
            final float target = Math.round(shift / 15f) * 15f;
            hits.add(new Hit(x0, sy - 1, x1 - x0, sh + 2, () -> { l.hue = Hue.normalize(target); save(); }, null));
        }
        int mxk = bx + Math.round(bw * (Hue.normalize(l.hue) + 180f) / 360f);
        g.fill(mxk - 1, sy - 1, mxk + 1, sy + sh + 1, TEXT);
        return sy + sh + GAP + 1;
    }

    private float lastTintHue = 0f;   // the tint colour to bring back when a tint is turned up from Off

    /** Tint: how strongly the layer is coloured towards a colour, and a strip to pick the colour. */
    private int tint(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, LayerConfig.Layer l) {
        float[] t = Hue.tintOf(l.color);
        if (t[1] > 0) lastTintHue = t[0];
        final float hue = t[1] > 0 ? t[0] : lastTintHue;
        int pct = Math.round(t[1] * 100f);
        exact(v -> { setTint(l, hue, clamp((int) Math.round(v), 0, 100) / 100f); save(); });
        y = stepper(g, mx, my, x, y, w, "tint", pct == 0 ? Lang.t("value.off") : pct + "%", d -> {
            setTint(l, hue, clamp(Math.round(Hue.tintOf(l.color)[1] * 100f) + d * 10, 0, 100) / 100f);
            save();
        });
        // the strip shows the colours to tint towards; a click picks one (at 60% if the tint was off)
        int lw = labelWidth(w), bx = x + lw, bw = w - lw, n = 24, sy = y - GAP, sh = 4;
        for (int i = 0; i < n; i++) {
            int x0 = bx + bw * i / n, x1 = bx + bw * (i + 1) / n;
            final float h = 360f * (i + 0.5f) / n;
            g.fill(x0, sy, x1, sy + sh, 0xFF000000 | Hue.tintRgb(h, 1f));
            hits.add(new Hit(x0, sy - 1, x1 - x0, sh + 2, () -> {
                float s0 = Hue.tintOf(l.color)[1];
                lastTintHue = Math.round(h / 15f) * 15f;
                setTint(l, lastTintHue, s0 > 0 ? s0 : 0.6f);
                save();
            }, null));
        }
        if (pct > 0) {
            int mxk = bx + Math.round(bw * hue / 360f);
            g.fill(mxk - 1, sy - 1, mxk + 1, sy + sh + 1, TEXT);
        }
        return sy + sh + GAP + 1;
    }

    private static void setTint(LayerConfig.Layer l, float hue, float strength) {
        l.color = (l.color & 0xFF000000) | Hue.tintRgb(hue, strength);
    }

    private static String hueText(float h) {
        int d = Math.round(Hue.normalize(h));
        return (d > 0 ? "+" : "") + d + "°";
    }

    private List<String> wrapLines(String text, int w, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (font.width(body(trial)) > w && line.length() > 0) { out.add(line.toString()); line = new StringBuilder(word); }
            else line = new StringBuilder(trial);
        }
        if (line.length() > 0) out.add(line.toString());
        if (out.size() > maxLines) {
            List<String> cut = new ArrayList<>(out.subList(0, maxLines));
            cut.set(maxLines - 1, fit(cut.get(maxLines - 1) + " " + String.join(" ", out.subList(maxLines, out.size())), w));
            return cut;
        }
        for (int i = 0; i < out.size(); i++) out.set(i, fit(out.get(i), w));
        return out;
    }

    private int wrapped(GuiGraphicsExtractor g, int x, int y, int w, String text, int color) {
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (font.width(body(trial)) > w && line.length() > 0) {
                g.text(font, body(fit(line.toString(), w)), x, y + 2, color, false);
                y += 10;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(trial);
            }
        }
        if (line.length() > 0) { g.text(font, body(fit(line.toString(), w)), x, y + 2, color, false); y += 10; }
        return y + 3;
    }

    // =================================================================== helpers
    private static String styleName(LayerConfig.Render r) {
        return switch (r) {
            case CUTOUT_NO_CULL -> Lang.t("value.style.solid");
            case CUTOUT -> Lang.t("value.style.solid_one_sided");
            case TRANSLUCENT -> Lang.t("value.style.see_through");
            case TRANSLUCENT_CULL -> Lang.t("value.style.see_through_one_sided");
            case EMISSIVE -> Lang.t("value.style.glow");
            case TRANSLUCENT_EMISSIVE -> Lang.t("value.style.soft_glow");
        };
    }

    private static String anchorName(LayerConfig.Anchor a) {
        return switch (a) {
            case BODY -> Lang.t("value.anchor.body");
            case HEAD -> Lang.t("value.anchor.head");
            case ROOT -> Lang.t("value.anchor.body_no_lean");
            case LEFT_ARM -> Lang.t("value.anchor.left_arm");
            case RIGHT_ARM -> Lang.t("value.anchor.right_arm");
            case LEFT_LEG -> Lang.t("value.anchor.left_leg");
            case RIGHT_LEG -> Lang.t("value.anchor.right_leg");
        };
    }

    private static String shortFile(String path) {
        if (path == null) return "";
        String f = path.substring(path.lastIndexOf('/') + 1);
        return f.replaceAll("(?i)\\.(json|png)$", "");
    }

    private String fit(String s, int w) {
        if (s == null) return "";
        if (w <= 0) return "";
        if (font.width(body(s)) <= w) return s;
        String e = "…";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (font.width(body(s.substring(0, mid) + e)) <= w) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo) + e;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clampF(float v, float lo, float hi) { return (float) round(Math.max(lo, Math.min(hi, v))); }
    private static double round(double v) { return Math.round(v * 10000.0) / 10000.0; }

    private static String fmt(double v) {
        StringBuilder b = new StringBuilder();
        JsonWriter.num(b, Math.round(v * 1000.0) / 1000.0);
        return b.toString();
    }

    private static <E extends Enum<E>> E cyc(E[] values, E cur, int d) {
        return values[Math.floorMod(cur.ordinal() + d, values.length)];
    }
}
