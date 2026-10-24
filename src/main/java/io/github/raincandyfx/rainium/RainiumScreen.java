package io.github.raincandyfx.rainium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.network.chat.Component;
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

    // ---- palette (ARGB), taken from the icon: night navy, cloud lavender, rain cyan ----------
    private static final int PANEL = 0xEC08103A, PANEL_EDGE = 0xFF1F2A5E;
    private static final int FIELD = 0xFF101A48, FIELD_HOVER = 0xFF1B2760, FIELD_EDGE = 0xFF2A376E;
    private static final int TEXT = 0xFFF1E8F5, MUTED = 0xFFAE9FC6, FAINT = 0xFF6E6A96;
    private static final int ACCENT = 0xFF1AD1E3, ACCENT_HOVER = 0xFF6BE3EE, ACCENT_DIM = 0xFF163F72, ACCENT_INK = 0xFF041136;
    private static final int WARN = 0xFFF0B252, DANGER = 0xFFFF8AA4, HOVER = 0x1CE8DBED;
    private static final int OVERLAY = 0xF2081038;   // tooltip, toast and preview chips

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
    private float spinFrom;
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
        // no blur: the world stays visible around the panels
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
            LocalPlayer me = Minecraft.getInstance().player;
            if (me != null) Privacy.ingameName = me.getName().getString();
        } catch (Throwable ignored) { }
        editRect = null;
        refreshFiles(false);
        if (!(tab == Tab.ADD && impFile != null)) assets.clearPreview();

        int W = width, H = height;
        int lw = clamp(W * 2 / 7, W < 380 ? 100 : 112, 156);
        int rw = clamp(W * 2 / 5, 158, 214);
        if (W - lw - rw - 24 < 80) rw = Math.max(136, W - lw - 24 - 80);
        int top = 26, bottom = H - 7;

        header(g, mx, my, W);
        layersPanel(g, mx, my, 6, top, lw, bottom - top);
        sidePanel(g, mx, my, W - 6 - rw, top, rw, bottom - top);
        int pvx = 6 + lw + 6, pvw = W - lw - rw - 24;
        preview(g, mx, my, pvx, top, pvw, bottom - top);
        toast(g, pvx, pvw, bottom);
        errorBanner(g, mx, my, pvx, top, pvw);
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
        g.text(font, fit(Lang.t("error.banner"), w - 50), x + 6, y + 4, DANGER, false);
        for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), x + 6, y + 15 + i * 10, TEXT, false);
        String rl = Lang.t("button.reload");
        int rw = font.width(Component.literal(rl)) + 8, rx = x + w - rw - 4;
        boolean hov = in(mx, my, rx, y + 3, rw, 11);
        g.fill(rx, y + 3, rx + rw, y + 14, hov ? FIELD_HOVER : FIELD);
        g.text(font, rl, rx + 4, y + 4, TEXT, false);
        hits.add(new Hit(rx, y + 3, rw, 11, () -> { assets.reload(); refreshFiles(true); currentSnapshot = assets.snapshot(); }, null));
        String cl = Lang.t("button.copy");
        int cw = font.width(Component.literal(cl)) + 8, cx = rx - cw - 3;
        boolean ch = in(mx, my, cx, y + 3, cw, 11);
        g.fill(cx, y + 3, cx + cw, y + 14, ch ? FIELD_HOVER : FIELD);
        g.text(font, cl, cx + 4, y + 4, TEXT, false);
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
        for (String ln : lines) tw = Math.max(tw, font.width(Component.literal(ln)));
        int bw = tw + 10, bh = lines.size() * 10 + 8;
        int bx = Math.min(mx + 10, width - bw - 4), by = my + 14;
        if (by + bh > height - 4) by = my - bh - 6;
        bx = Math.max(4, bx);
        g.fill(bx, by, bx + bw, by + bh, OVERLAY);
        g.outline(bx, by, bw, bh, FIELD_EDGE);
        for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), bx + 5, by + 5 + i * 10, TEXT, false);
    }

    // =================================================================== header
    private void header(GuiGraphicsExtractor g, int mx, int my, int W) {
        String title = Lang.t("name");
        g.text(font, title, 8, 9, TEXT, false);

        LayerConfig c = assets.editable();
        int x = W - 6;
        String done = Lang.t("button.done");
        int dw = font.width(Component.literal(done)) + 18;
        primaryButton(g, mx, my, x - dw, 5, dw, done, this::close);
        x -= dw + 8;
        String redo = Lang.t("button.redo"), undo = Lang.t("button.undo");
        int rw2 = font.width(Component.literal(redo)) + 10, uw = font.width(Component.literal(undo)) + 10;
        iconButton(g, mx, my, x - rw2, 6, rw2, redo, !redoStack.isEmpty(), this::redo);
        iconButton(g, mx, my, x - rw2 - 3 - uw, 6, uw, undo, !undoStack.isEmpty(), this::undo);
        x -= rw2 + uw + 3 + 8;
        String lab = Lang.t(c.enabled ? "header.showing" : "header.hidden");
        int sw = font.width(Component.literal(lab)) + 30;
        boolean hov = in(mx, my, x - sw, 5, sw, 16);
        if (hov) g.fill(x - sw, 5, x, 21, HOVER);
        g.text(font, lab, x - sw + 4, 9, c.enabled ? TEXT : MUTED, false);
        drawSwitch(g, x - 22, 8, c.enabled);
        hits.add(new Hit(x - sw, 5, sw, 16, () -> { c.enabled = !c.enabled; save(); say(Lang.t(c.enabled ? "msg.layers_shown" : "msg.layers_hidden"), MUTED); }, null));
    }

    // =================================================================== left: layers
    private void layersPanel(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h) {
        panel(g, x, y, w, h);
        LayerConfig cfg = assets.editable();
        List<LayerConfig.Layer> layers = cfg.layers;
        if (selected >= layers.size()) selected = Math.max(0, layers.size() - 1);
        if (selectedFolder != null && cfg.members(selectedFolder).isEmpty()) selectedFolder = null;
        sectionLabel(g, x + PAD, y + 6, Lang.t("section.layers"));
        String count = String.valueOf(layers.size());
        g.text(font, count, x + w - PAD - font.width(Component.literal(count)), y + 6, FAINT, false);

        int footer = ROW * 2 + GAP + 10;
        int listTop = y + 19, listBottom = y + h - footer;
        if (layers.isEmpty()) {
            g.text(font, Lang.t("layers.empty"), x + PAD, listTop + 4, MUTED, false);
            wrapped(g, x + PAD, listTop + 16, w - PAD * 2, Lang.t("layers.empty_hint"), FAINT);
        }

        int before = hits.size();
        g.enableScissor(x + 1, listTop, x + w - 1, listBottom);
        int ry = listTop - leftScroll, startY = ry;
        int rx = x + 3, rwid = w - 6;
        for (LayerConfig.Entry e : cfg.entries()) {
            if (e.isFolder()) {
                ry = folderRow(g, mx, my, rx, ry, rwid, e);
                LayerConfig.Folder f = cfg.folder(e.folder());
                if (!f.collapsed) {
                    int top = ry;
                    for (LayerConfig.Layer l : e.members()) ry = partRow(g, mx, my, rx + 9, ry, rwid - 9, l, layers.indexOf(l), f.enabled);
                    g.fill(rx + 5, top, rx + 6, ry - 2, PANEL_EDGE);   // guide line for the folder's parts
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
            g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0x66FFFFFF);
        }
        final int maxScroll = Math.max(0, contentH - viewH);
        hits.add(new Hit(x, listTop, w, viewH, null, d -> leftScroll = clamp(leftScroll - d * 12, 0, maxScroll)));

        int by = y + h - footer + 4;
        primaryButton(g, mx, my, x + PAD, by, w - PAD * 2, Lang.t("button.add_layer"), () -> { tab = Tab.ADD; impFile = null; rightScroll = 0; refreshFiles(true); });
        by += ROW + GAP;
        boolean has = !layers.isEmpty();
        int bx = x + PAD, half = (w - PAD * 2 - GAP) / 2;
        iconButton(g, mx, my, bx, by, half, Lang.t("button.up"), has, () -> move(-1));
        iconButton(g, mx, my, bx + half + GAP, by, w - PAD * 2 - half - GAP, Lang.t("button.down"), has, () -> move(1));
    }

    private int folderRow(GuiGraphicsExtractor g, int mx, int my, int rx, int ry, int rwid, LayerConfig.Entry e) {
        LayerConfig.Folder f = assets.editable().folder(e.folder());
        int rh = 18;
        boolean sel = e.folder().equals(selectedFolder), hov = in(mx, my, rx, ry, rwid, rh - 2);
        if (sel) { g.fill(rx, ry, rx + rwid, ry + rh - 2, ACCENT_DIM); g.fill(rx, ry, rx + 2, ry + rh - 2, ACCENT); }
        else if (hov) g.fill(rx, ry, rx + rwid, ry + rh - 2, HOVER);
        // collapse arrow
        boolean ah = in(mx, my, rx + 2, ry, 12, rh - 2);
        g.text(font, f.collapsed ? "▸" : "▾", rx + 4, ry + 4, ah ? TEXT : MUTED, false);
        hits.add(new Hit(rx + 2, ry, 12, rh - 2, () -> { f.collapsed = !f.collapsed; save(); }, null));
        g.text(font, fit(e.folder(), rwid - 34), rx + 15, ry + 4, f.enabled ? TEXT : MUTED, false);
        visibilityBox(g, mx, my, rx + rwid - 14, ry + 4, f.enabled, () -> {
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
        if (sel) { g.fill(rx, ry, rx + rwid, ry + rh - 2, ACCENT_DIM); g.fill(rx, ry, rx + 2, ry + rh - 2, ACCENT); }
        else if (hov) g.fill(rx, ry, rx + rwid, ry + rh - 2, HOVER);
        g.fill(rx + 6, ry + 3, rx + 10, ry + 13, on ? (l.color | 0xFF000000) : FAINT);
        g.text(font, fit(l.label, rwid - 34), rx + 15, ry + 4, on ? TEXT : MUTED, false);
        visibilityBox(g, mx, my, rx + rwid - 14, ry + 4, l.enabled, () -> { l.enabled = !l.enabled; save(); });
        hits.add(new Hit(rx, ry, rwid - 18, rh - 2, () -> selectLayer(idx), null));
        return ry + rh;
    }

    /** Compact one-line row for a part inside a folder. */
    private int partRow(GuiGraphicsExtractor g, int mx, int my, int rx, int ry, int rwid, LayerConfig.Layer l, int idx, boolean parentOn) {
        int rh = 16;
        boolean on = l.enabled && parentOn;
        boolean sel = selectedFolder == null && idx == selected, hov = in(mx, my, rx, ry, rwid, rh - 2);
        if (sel) { g.fill(rx, ry, rx + rwid, ry + rh - 2, ACCENT_DIM); g.fill(rx, ry, rx + 2, ry + rh - 2, ACCENT); }
        else if (hov) g.fill(rx, ry, rx + rwid, ry + rh - 2, HOVER);
        g.fill(rx + 6, ry + 3, rx + 9, ry + 11, on ? (l.color | 0xFF000000) : FAINT);
        g.text(font, fit(l.label, rwid - 34), rx + 14, ry + 3, on ? TEXT : MUTED, false);
        visibilityBox(g, mx, my, rx + rwid - 14, ry + 3, l.enabled, () -> { l.enabled = !l.enabled; save(); });
        hits.add(new Hit(rx, ry, rwid - 18, rh - 2, () -> selectLayer(idx), null));
        return ry + rh;
    }

    private void visibilityBox(GuiGraphicsExtractor g, int mx, int my, int ex, int ey, boolean on, Runnable flip) {
        boolean eh = in(mx, my, ex - 3, ey - 3, 14, 14);
        g.outline(ex, ey, 8, 8, eh ? TEXT : (on ? ACCENT : FAINT));
        if (on) g.fill(ex + 2, ey + 2, ex + 6, ey + 6, ACCENT);
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
        panel(g, x, y, w, h);
        // segmented control
        int sx = x + PAD, sy = y + 5, sw = w - PAD * 2, segW = sw / 3;
        g.fill(sx, sy, sx + sw, sy + ROW + 2, FIELD);
        String[] names = {Lang.t("tab.edit"), Lang.t("tab.add"), Lang.t("tab.settings")};
        for (Tab t : Tab.values()) {
            int tx = sx + t.ordinal() * segW, tw = t.ordinal() == 2 ? sw - segW * 2 : segW;
            boolean on = tab == t, hov = in(mx, my, tx, sy, tw, ROW + 2);
            if (on) g.fill(tx + 1, sy + 1, tx + tw - 1, sy + ROW + 1, ACCENT_DIM);
            else if (hov) g.fill(tx + 1, sy + 1, tx + tw - 1, sy + ROW + 1, HOVER);
            String n = names[t.ordinal()];
            g.text(font, n, tx + (tw - font.width(Component.literal(n))) / 2, sy + 4, on ? TEXT : MUTED, false);
            hits.add(new Hit(tx, sy, tw, ROW + 2, () -> { tab = t; rightScroll = 0; if (t == Tab.ADD) refreshFiles(true); }, null));
        }

        int cx = x + PAD, cw = w - PAD * 2 - 3, cTop = sy + ROW + 8, cBottom = y + h - 4;
        int viewH = cBottom - cTop;
        rightScroll = clamp(rightScroll, 0, Math.max(0, rightContent - viewH));
        int before = hits.size();
        g.enableScissor(x + 1, cTop, x + w - 1, cBottom);
        int cy = cTop - rightScroll, start = cy;
        cy = switch (tab) {
            case EDIT -> editTab(g, mx, my, cx, cy, cw);
            case ADD -> impFile != null ? importView(g, mx, my, cx, cy, cw) : addTab(g, mx, my, cx, cy, cw);
            case SETTINGS -> settingsTab(g, mx, my, cx, cy, cw);
        };
        g.disableScissor();
        rightContent = cy - start + 6;
        for (int i = hits.size() - 1; i >= before; i--) {
            Hit hh = hits.get(i);
            if (hh.y() + hh.h() <= cTop || hh.y() >= cBottom) hits.remove(i);
        }
        if (rightContent > viewH) {
            int barH = Math.max(14, viewH * viewH / rightContent);
            int barY = cTop + (viewH - barH) * rightScroll / Math.max(1, rightContent - viewH);
            g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0x66FFFFFF);
        }
        hits.add(new Hit(x, cTop, w, viewH, null, d -> rightScroll = clamp(rightScroll - d * 14, 0, Math.max(0, rightContent - viewH))));
    }

    // ------------------------------------------------------------------- Edit
    private int editTab(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w) {
        if (selectedFolder != null) return folderView(g, mx, my, x, y, w);
        List<LayerConfig.Layer> layers = assets.editable().layers;
        if (layers.isEmpty()) {
            g.text(font, Lang.t("edit.empty"), x, y + 2, TEXT, false);
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
        g.text(font, dl, dx + (dw - font.width(Component.literal(dl))) / 2, y + 3, arm ? DANGER : TEXT, false);
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
        g.text(font, Lang.t(parts.size() == 1 ? "folder.count_one" : "folder.count", parts.size()), x, y + 10, FAINT, false);
        y += 23;
        y = toggle(g, mx, my, x, y, w, "show_this_folder", f.enabled, () -> { f.enabled = !f.enabled; save(); });

        y = section(g, x, y + 4, w, "parts");
        for (LayerConfig.Layer l : parts) {
            boolean hov = in(mx, my, x, y, w - 18, ROW + 2);
            if (hov) g.fill(x, y, x + w, y + ROW + 2, HOVER);
            boolean on = l.enabled && f.enabled;
            g.fill(x + 2, y + 3, x + 5, y + ROW - 1, on ? (l.color | 0xFF000000) : FAINT);
            g.text(font, fit(l.label, w - 30), x + 9, y + 4, on ? TEXT : MUTED, false);
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
        g.text(font, dl, dx + (dw - font.width(Component.literal(dl))) / 2, y + 3, confirmFolderDelete ? DANGER : TEXT, false);
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
            g.text(font, fit(p.name(), w - 46 - th), x + 7 + th, y + 5, TEXT, false);
            for (int li = 0; li < lines.size(); li++) g.text(font, lines.get(li), x + 7 + th, y + 18 + li * 10, MUTED, false);
            String chip = Lang.t("preset.add");
            int cwid = font.width(Component.literal(chip)) + 8;
            g.fill(x + w - cwid - 5, y + 4, x + w - 5, y + 14, ACCENT);
            g.text(font, chip, x + w - cwid - 1, y + 5, ACCENT_INK, false);
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
        int kw = font.width(Component.literal(kindText)), tx = x + Thumbs.SIZE + 6;
        g.text(font, fit(file, x + w - kw - 8 - tx), tx, y + (rh - 8) / 2, TEXT, false);
        g.text(font, kindText, x + w - kw - 3, y + (rh - 8) / 2, kind.equals("import") ? ACCENT : MUTED, false);
        hits.add(new Hit(x, y, w, rh, click, null));
        return y + rh + 1;
    }

    /** "Label   [text]" row: click the text to type, Enter saves. */
    private int textRow(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String id, String value, String placeholder, Consumer<String> commit) {
        int lw = labelWidth(w);
        g.text(font, fit(Lang.t("label." + id), lw - 2), x, y + 3, MUTED, false);
        tip(mx, my, x, y, lw - 2, ROW, id);
        g.fill(x + lw, y, x + w, y + ROW, FIELD);
        boolean empty = value == null || value.isBlank();
        textField(g, mx, my, x + lw + 4, y + 1, w - lw - 6, ROW - 2, "text:" + id, empty ? "" : value, false, TEXT, commit);
        if (empty && !("text:" + id).equals(editId)) g.text(font, fit(placeholder, w - lw - 8), x + lw + 4, y + 3, FAINT, false);
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
        int kw = font.width(Component.literal(kindText));
        g.text(font, fit(name, w - kw - 12), x + 3, y + 4, TEXT, false);
        g.text(font, kindText, x + w - kw - 3, y + 4, kind.equals("import") ? ACCENT : MUTED, false);
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
        g.text(font, Lang.t("button.back"), x, y + 2, bh ? TEXT : MUTED, false);
        hits.add(new Hit(x, y, 60, ROW, () -> { impFile = null; impParsed = null; impResult = null; assets.clearPreview(); rightScroll = 0; }, null));
        y += ROW + 2;
        g.text(font, fit(impFile, w), x, y, TEXT, false);
        y += 12;
        if (impError != null) {
            String ie = impError;
            y = wrapped(g, x, y, w, impError, DANGER);
            y = secondaryButton(g, mx, my, x, y + 2, w, Lang.t("button.copy_error"), () -> copyText(ie));
        } else if (impResult != null) {
            double sx = impResult.max()[0] - impResult.min()[0], sy = impResult.max()[1] - impResult.min()[1], sz = impResult.max()[2] - impResult.min()[2];
            g.text(font, fit(Lang.t("import.stats", String.format(Locale.ROOT, "%,d", impResult.quads()), fmt(sx), fmt(sy), fmt(sz)), w), x, y, MUTED, false);
            y += 10;
            g.text(font, fit(Lang.t("import.parts", String.join(", ", impResult.groups())), w), x, y, FAINT, false);
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

        y = section(g, x, y + 4, w, "capture");
        y = cycle(g, mx, my, x, y, w, "capture_size", Lang.t("value.capture_size." + c.captureSize), d -> {
            int[] o = {1, 2, 4};
            int i = c.captureSize >= 4 ? 2 : c.captureSize >= 2 ? 1 : 0;
            c.captureSize = o[Math.floorMod(i + d, 3)];
            save();
        });
        y = cycle(g, mx, my, x, y, w, "capture_fps", Lang.t("value.capture_fps", c.captureFps), d -> { c.captureFps = c.captureFps == 60 ? 30 : 60; save(); });
        y = cycle(g, mx, my, x, y, w, "capture_spin", Lang.t("value.capture_spin." + c.captureSpin), d -> { c.captureSpin = Math.floorMod(c.captureSpin + d, 3); save(); });
        y = secondaryButton(g, mx, my, x, y, w, Lang.t("button.open_captures"), () -> {
            try { java.nio.file.Files.createDirectories(capturesDir()); } catch (Exception ignored) {}
            assets.openFolder(capturesDir());
        });

        y = section(g, x, y + 4, w, "privacy");
        String pm = Lang.t("value.privacy." + c.privacyMode.name().toLowerCase(Locale.ROOT));
        y = cycle(g, mx, my, x, y, w, "name_in_paths", pm, d -> { c.privacyMode = cyc(Privacy.Mode.values(), c.privacyMode, d); Privacy.mode = c.privacyMode; save(); });
        if (c.privacyMode == Privacy.Mode.CUSTOM) {
            int lw = labelWidth(w);
            g.text(font, Lang.t("label.custom_name"), x, y + 3, MUTED, false);
            tip(mx, my, x, y, lw - 2, ROW, "custom_name");
            g.fill(x + lw, y, x + w, y + ROW, FIELD);
            textField(g, mx, my, x + lw + 4, y + 1, w - lw - 6, ROW - 2, "name:privacy", c.privacyName.isBlank() ? "player" : c.privacyName, false, TEXT, v -> {
                c.privacyName = v.trim();
                Privacy.customName = c.privacyName;
                save();
            });
            y += ROW + GAP;
        }

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
        return y;
    }

    // =================================================================== preview
    private void preview(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h) {
        g.fillGradient(x, y, x + w, y + h, 0x2A000000, 0x6A000000);
        hits.add(new Hit(x, y, w, h - 20, () -> dragging = true, d -> previewZoom = clampF(previewZoom * (d > 0 ? 1.1f : 1 / 1.1f), 0.4f, 4f)));
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            String s = fit(Lang.t("preview.no_world"), w - 8);
            g.text(font, s, x + (w - font.width(Component.literal(s))) / 2, y + h / 2 - 4, MUTED, false);
            previewState = null;
        } else {
            try {
                EntityRenderer<?, ?> renderer = mc.getEntityRenderDispatcher().getRenderer(player);
                @SuppressWarnings({"unchecked", "rawtypes"})
                EntityRenderState st = ((EntityRenderer) renderer).createRenderState(player, 1f);
                st.shadowPieces.clear();
                st.outlineColor = 0;
                float bbh = 1.8f;
                int spin = assets.editable().captureSpin;
                if (Recorder.mode() == Recorder.Mode.RECORDING && spin > 0)   // one turn every 12 s (slow) or 6 s (fast)
                    previewYaw = ((spinFrom + Recorder.seconds(System.nanoTime()) * (spin == 1 ? 30f : 60f)) % 360f + 360f) % 360f;
                if (st instanceof LivingEntityRenderState ls) {
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
                g.text(font, Lang.t("preview.unavailable"), x + 6, y + 6, DANGER, false);
            }
        }
        if (player != null && Recorder.submit != null) captureChips(g, mx, my, x, y, w);
        // camera chips
        String[] views = {Lang.t("preview.front"), Lang.t("preview.side"), Lang.t("preview.back")};
        float[] yaws = {0f, 90f, 180f};
        int cw = 0;
        for (String v : views) cw += font.width(Component.literal(v)) + 10;
        cw += GAP * 2;
        if (cw > w - 6) {
            views = new String[]{Lang.t("preview.front_short"), Lang.t("preview.side_short"), Lang.t("preview.back_short")};
            cw = 0;
            for (String v : views) cw += font.width(Component.literal(v)) + 10;
            cw += GAP * 2;
            if (cw > w - 6) return;
        }
        int cx = x + (w - cw) / 2, cy = y + h - 16;
        for (int i = 0; i < views.length; i++) {
            int vw = font.width(Component.literal(views[i])) + 10;
            boolean on = Math.abs(Math.floorMod((int) previewYaw - (int) yaws[i] + 180, 360) - 180) < 3;
            boolean hov = in(mx, my, cx, cy, vw, 12);
            g.fill(cx, cy, cx + vw, cy + 12, on ? ACCENT_DIM : hov ? FIELD_HOVER : 0x99081038);
            g.text(font, views[i], cx + 5, cy + 2, on ? TEXT : MUTED, false);
            final float ty = yaws[i];
            hits.add(new Hit(cx, cy, vw, 12, () -> { previewYaw = ty; previewPitch = -8f; }, null));
            cx += vw + GAP;
        }
    }

    /** Picture / Rec buttons in the preview's top-right corner; while recording, Stop with the time. */
    private void captureChips(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w) {
        int cy = y + 4, right = x + w - 4;
        if (Recorder.mode() == Recorder.Mode.RECORDING) {
            float sec = Recorder.seconds(System.nanoTime());
            String t = Lang.t("preview.stop", String.format(Locale.ROOT, "%d:%02d", (int) sec / 60, (int) sec % 60));
            int cw = font.width(Component.literal(t)) + 10, cx = right - cw;
            boolean hov = in(mx, my, cx, cy, cw, 12);
            g.fill(cx, cy, cx + cw, cy + 12, hov ? 0xFFB0304E : 0xE0901E3C);
            g.text(font, t, cx + 5, cy + 2, TEXT, false);
            hits.add(new Hit(cx, cy, cw, 12, Recorder::stop, null));
            if (Recorder.framesDropped() > 0) {
                String d = fit(Lang.t("preview.dropping"), w - cw - 16);
                g.text(font, d, cx - 6 - font.width(Component.literal(d)), cy + 2, WARN, false);
            }
            return;
        }
        if (Recorder.capturing()) return;   // a picture is being taken this frame
        if (Recorder.pending() > 0) {       // still saving a recording
            String sv = Lang.t("preview.saving", Recorder.framesWritten());
            g.text(font, sv, right - font.width(Component.literal(sv)), cy + 2, MUTED, false);
            return;
        }
        String[] labels = {Lang.t("preview.picture"), Lang.t("preview.record")};
        String[] tips = {"picture", "record"};
        Runnable[] acts = {this::takePicture, this::startRecording};
        int cx = right;
        for (int i = labels.length - 1; i >= 0; i--) {
            int cw = font.width(Component.literal(labels[i])) + 10;
            cx -= cw;
            boolean hov = in(mx, my, cx, cy, cw, 12);
            g.fill(cx, cy, cx + cw, cy + 12, hov ? FIELD_HOVER : 0x99081038);
            g.text(font, labels[i], cx + 5, cy + 2, i == 1 ? DANGER : TEXT, false);
            tip(mx, my, cx, cy, cw, 12, tips[i]);
            hits.add(new Hit(cx, cy, cw, 12, acts[i], null));
            cx -= GAP;
        }
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
        int tw = font.width(Component.literal(s)) + 16;
        int tx = clamp(px + (pw - tw) / 2, 6, width - 6 - tw), ty = bottom - 38;
        g.fill(tx, ty, tx + tw, ty + 16, OVERLAY);
        g.fill(tx, ty, tx + 2, ty + 16, toastColor);
        g.text(font, s, tx + 9, ty + 4, TEXT, false);
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

    // SDL scancodes (Minecraft 26.x reports physical keys as SDL scancodes)
    private static final int SC_A = 4, SC_Z = 29, SC_1 = 30, SC_0 = 39, SC_ENTER = 40, SC_ESC = 41, SC_BACKSPACE = 42,
        SC_SPACE = 44, SC_MINUS = 45, SC_COMMA = 54, SC_PERIOD = 55, SC_DELETE = 76, SC_KP_MINUS = 86, SC_KP_ENTER = 88,
        SC_KP_1 = 89, SC_KP_0 = 98, SC_KP_PERIOD = 99, SC_Y = 28,
        SC_LCTRL = 224, SC_LSHIFT = 225, SC_LGUI = 227, SC_RCTRL = 228, SC_RSHIFT = 229, SC_RGUI = 231;

    private static boolean down(int sc) {
        try { return InputConstants.isKeyDown(sc); } catch (Throwable t) { return false; }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if (editId != null) {
            boolean shift = down(SC_LSHIFT) || down(SC_RSHIFT);
            if (k == SC_ENTER || k == SC_KP_ENTER) { commitEdit(); return true; }
            if (k == SC_ESC) { cancelEdit(); return true; }
            if (k == SC_BACKSPACE || k == SC_DELETE) {
                if (editReplace) editBuf.setLength(0); else if (editBuf.length() > 0) editBuf.setLength(editBuf.length() - 1);
                editReplace = false;
                return true;
            }
            char c = 0;
            if (k >= SC_1 && k <= SC_0) c = k == SC_0 ? '0' : (char) ('1' + (k - SC_1));
            else if (k >= SC_KP_1 && k <= SC_KP_0) c = k == SC_KP_0 ? '0' : (char) ('1' + (k - SC_KP_1));
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
            if (editReplace && !shown.isEmpty()) g.fill(x, y + 2, x + font.width(Component.literal(fitted)) + 1, y + h - 2, ACCENT_DIM);
            g.text(font, fitted, x, y + (h - 8) / 2, TEXT, false);
            if (caret) { int cx = x + font.width(Component.literal(fitted)) + 1; g.fill(cx, y + 2, cx + 1, y + h - 2, TEXT); }
            editRect = new int[]{x - 2, y, w + 2, h};
            return;
        }
        boolean hov = in(mx, my, x - 2, y, w + 2, h);
        String shown = fit(value, w - (hov ? 30 : 0));
        g.text(font, shown, x, y + (h - 8) / 2, color, false);
        if (hov) {
            g.fill(x, y + h - 1, x + font.width(Component.literal(shown)), y + h, FAINT);
            g.text(font, Lang.t("field.edit_hint"), x + w - font.width(Component.literal(Lang.t("field.edit_hint"))), y + (h - 8) / 2, FAINT, false);
        }
        hits.add(new Hit(x - 2, y, w + 2, h, () -> beginEdit(id, value, numeric, commit), null));
    }

    private String fitTail(String s, int w) {
        if (font.width(Component.literal(s)) <= w) return s;
        int i = 0;
        while (i < s.length() && font.width(Component.literal("…" + s.substring(i))) > w) i++;
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
    private void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        g.outline(x, y, w, h, PANEL_EDGE);
    }

    private void sectionLabel(GuiGraphicsExtractor g, int x, int y, String s) {
        g.text(font, s, x, y, FAINT, false);
    }

    /** A section heading; id names "section.&lt;id&gt;" in the lang file. */
    private int section(GuiGraphicsExtractor g, int x, int y, int w, String id) {
        String title = Lang.t("section." + id);
        sectionLabel(g, x, y + 2, title);
        int tw = font.width(Component.literal(title));
        g.fill(x + tw + 5, y + 6, x + w, y + 7, PANEL_EDGE);
        return y + 14;
    }

    private void primaryButton(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, Runnable click) {
        boolean hov = in(mx, my, x, y, w, ROW + 2);
        g.fill(x, y, x + w, y + ROW + 2, hov ? ACCENT_HOVER : ACCENT);
        String s = fit(label, w - 4);
        g.text(font, s, x + (w - font.width(Component.literal(s))) / 2, y + 4, ACCENT_INK, false);
        hits.add(new Hit(x, y, w, ROW + 2, click, null));
    }

    private int primaryButtonRow(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, Runnable click) {
        primaryButton(g, mx, my, x, y, w, label, click);
        return y + ROW + 2 + GAP + 2;
    }

    private int secondaryButton(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, Runnable click) {
        boolean hov = in(mx, my, x, y, w, ROW);
        g.fill(x, y, x + w, y + ROW, hov ? FIELD_HOVER : FIELD);
        g.outline(x, y, w, ROW, FIELD_EDGE);
        String s = fit(label, w - 4);
        g.text(font, s, x + (w - font.width(Component.literal(s))) / 2, y + 3, TEXT, false);
        hits.add(new Hit(x, y, w, ROW, click, null));
        return y + ROW + GAP;
    }

    private void iconButton(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, boolean enabled, Runnable click) {
        boolean hov = enabled && in(mx, my, x, y, w, ROW);
        g.fill(x, y, x + w, y + ROW, hov ? FIELD_HOVER : FIELD);
        String s = fit(label, w - 2);
        g.text(font, s, x + (w - font.width(Component.literal(s))) / 2, y + 3, enabled ? TEXT : FAINT, false);
        if (enabled) hits.add(new Hit(x, y, w, ROW, click, null));
    }

    private void drawSwitch(GuiGraphicsExtractor g, int x, int y, boolean on) {
        g.fill(x, y, x + 18, y + 10, on ? ACCENT_DIM : FIELD_EDGE);
        g.fill(on ? x + 9 : x + 1, y + 1, on ? x + 17 : x + 9, y + 9, on ? ACCENT : MUTED);
    }

    private int labelWidth(int w) { return Math.min(70, w * 2 / 5); }

    /** "Label        ‹ value ›" — click either half or scroll to change. */
    private int cycle(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String id, String value, IntConsumer step) {
        int lw = labelWidth(w);
        g.text(font, fit(Lang.t("label." + id), lw - 2), x, y + 3, MUTED, false);
        tip(mx, my, x, y, lw - 2, ROW, id);
        int bx = x + lw, bw = w - lw;
        boolean hl = in(mx, my, bx, y, bw / 2, ROW), hr = in(mx, my, bx + bw / 2, y, bw - bw / 2, ROW);
        g.fill(bx, y, bx + bw, y + ROW, hl || hr ? FIELD_HOVER : FIELD);
        g.text(font, "‹", bx + 4, y + 3, hl ? TEXT : FAINT, false);
        g.text(font, "›", bx + bw - 8, y + 3, hr ? TEXT : FAINT, false);
        String v = fit(value, bw - 22);
        g.text(font, v, bx + (bw - font.width(Component.literal(v))) / 2, y + 3, TEXT, false);
        hits.add(new Hit(bx, y, bw / 2, ROW, () -> step.accept(-1), step));
        hits.add(new Hit(bx + bw / 2, y, bw - bw / 2, ROW, () -> step.accept(1), step));
        return y + ROW + GAP;
    }

    /** "Label        − value +" — buttons or scroll wheel. */
    private int stepper(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String id, String value, IntConsumer step) {
        DoubleConsumer setter = nextExact;
        nextExact = null;
        int lw = labelWidth(w);
        g.text(font, fit(Lang.t("label." + id), lw - 2), x, y + 3, MUTED, false);
        tip(mx, my, x, y, lw - 2, ROW, id);
        int bx = x + lw, bw = w - lw;
        String fieldId = "num:" + id;
        g.fill(bx, y, bx + bw, y + ROW, FIELD);
        boolean hm = in(mx, my, bx, y, 14, ROW), hp = in(mx, my, bx + bw - 14, y, 14, ROW);
        if (hm) g.fill(bx, y, bx + 14, y + ROW, FIELD_HOVER);
        if (hp) g.fill(bx + bw - 14, y, bx + bw, y + ROW, FIELD_HOVER);
        g.text(font, "−", bx + 4, y + 3, hm ? TEXT : MUTED, false);
        g.text(font, "+", bx + bw - 10, y + 3, hp ? TEXT : MUTED, false);
        hits.add(new Hit(bx, y, 14, ROW, () -> step.accept(-1), null));
        hits.add(new Hit(bx + bw - 14, y, 14, ROW, () -> step.accept(1), null));
        if (setter != null && fieldId.equals(editId)) {
            textField(g, mx, my, bx + 16, y + 1, bw - 32, ROW - 2, fieldId, value, true, TEXT, null);
            return y + ROW + GAP;
        }
        boolean hv = setter != null && in(mx, my, bx + 14, y, bw - 28, ROW);
        if (hv) g.fill(bx + 14, y, bx + bw - 14, y + ROW, FIELD_HOVER);
        String v = fit(value, bw - 30);
        g.text(font, v, bx + (bw - font.width(Component.literal(v))) / 2, y + 3, TEXT, false);
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
        boolean hov = in(mx, my, x, y, w, ROW);
        if (hov) g.fill(x - 2, y, x + w, y + ROW, HOVER);
        g.text(font, fit(Lang.t("label." + id), w - 26), x, y + 3, on ? TEXT : MUTED, false);
        tip(mx, my, x, y, w - 22, ROW, id);
        drawSwitch(g, x + w - 19, y + 2, on);
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

    private static String hueText(float h) {
        int d = Math.round(Hue.normalize(h));
        return (d > 0 ? "+" : "") + d + "°";
    }

    private List<String> wrapLines(String text, int w, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (font.width(Component.literal(trial)) > w && line.length() > 0) { out.add(line.toString()); line = new StringBuilder(word); }
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
            if (font.width(Component.literal(trial)) > w && line.length() > 0) {
                g.text(font, fit(line.toString(), w), x, y + 2, color, false);
                y += 10;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(trial);
            }
        }
        if (line.length() > 0) { g.text(font, fit(line.toString(), w), x, y + 2, color, false); y += 10; }
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
        if (font.width(Component.literal(s)) <= w) return s;
        String e = "…";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (font.width(Component.literal(s.substring(0, mid) + e)) <= w) lo = mid; else hi = mid - 1;
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
