package io.github.raincandyfx.rainium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.network.chat.Component;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The reactions editor: a whole screen with the graph of one layer (or folder) on a canvas you can
 * pan and zoom, the selected node's settings on the right, and a small live preview of you.
 * Drag from a node's output (right) to another's input (left) to wire them; drop a wire on empty
 * canvas to add a node there already wired. Every change is saved straight away, like the editor.
 */
public final class ReactScreen extends Screen {
    // ---- palette: the editor's
    private static final int INK = 0xFF050B33, SEA = 0xFF1436C9, BRIGHT = 0xFF1F7CFF, CYAN = 0xFF48E6FF, MENU = 0xFF8FF0FF,
        HAZE = 0xFFC9D8FF, PAPER = 0xFFFFFFFF, RED = 0xFFF2163A;
    private static final int FIELD = 0xF2050B33, FIELD_HOVER = 0xFF1A2D86, FIELD_EDGE = 0xFF3C4C90;
    private static final int TEXT = 0xFFF4F7FF, MUTED = HAZE, FAINT = 0xFF8590C4, WARN = 0xFFFFC266, DANGER = 0xFFFF6A86;
    private static final int C_EVENT = 0xFFFFC266, C_STATE = 0xFF48E6FF, C_LOGIC = 0xFFB9A2FF, C_ACTION = 0xFFFF7A9A;
    private static final int ROW = 14, GAP = 3, PAD = 7, BAR = 24;

    // ---- node shape (canvas units)
    static final int NW = 104, HEAD = 13, PORT_ROW = 11, SUMMARY = 10;

    private final RainiumScreen parent;
    private final Assets assets = RainiumClient.ASSETS;
    private final String folder;          // the folder edited, or null for a layer
    private LayerConfig.Layer layerRef;   // the layer edited (looked up again after undo)
    private final String layerLabel, layerFolder;
    private final int layerIndex;

    // view: screen = canvas origin + (x + panX) * zoom
    float panX = 24, panY = 18, zoom = 1f;
    private int cvX, cvY, cvW, cvH;
    int selected = -1;

    private enum Drag { NONE, PAN, NODE, WIRE, HANDLE }
    private Drag drag = Drag.NONE;
    private int dragNode, wireNode, wirePort, handle;
    private boolean wireBackward, dragMoved;
    private String handleKey;
    private float grabX, grabY;
    private double lastMx, lastMy;

    // add-a-node menu
    boolean menuOpen;
    private int menuX, menuY, menuScroll, menuContent;
    private float menuCX, menuCY;
    private React.Cat menuCat = React.Cat.EVENT;
    private int linkNode = -1, linkPort;
    private boolean linkBackward;
    private final StringBuilder filter = new StringBuilder();

    private boolean fileOpen, importOpen, confirmClear, showPreview = true;
    private int inspScroll, inspContent;

    // typing a value
    private String editId;
    private final StringBuilder editBuf = new StringBuilder();
    private boolean editNumeric, editReplace;
    private Consumer<String> editCommit;
    private int[] editRect;

    private String toast = "";
    private int toastColor = TEXT;
    private long toastUntil;
    private String tip;

    private record Hit(int x, int y, int w, int h, Runnable click, java.util.function.IntConsumer scroll) {}
    private final List<Hit> hits = new ArrayList<>();

    ReactScreen(RainiumScreen parent, LayerConfig.Layer layer, String folder) {
        super(Component.translatable("rainium.react.title"));
        this.parent = parent;
        this.folder = folder;
        this.layerRef = layer;
        this.layerLabel = layer == null ? null : layer.label;
        this.layerFolder = layer == null ? null : layer.folder;
        this.layerIndex = layer == null ? -1 : assets.editable().layers.indexOf(layer);
        React.Graph g = graph();
        if (g != null && !g.isEmpty()) fitLater = true;
    }

    private boolean fitLater;

    // ------------------------------------------------------------------ what's being edited
    private LayerConfig cfg() { return assets.editable(); }

    /** The layer being edited (after an undo the layer objects are new: find it again by name). */
    LayerConfig.Layer layer() {
        if (folder != null) return null;
        List<LayerConfig.Layer> ls = cfg().layers;
        if (layerRef != null && ls.contains(layerRef)) return layerRef;
        if (layerIndex >= 0 && layerIndex < ls.size() && java.util.Objects.equals(ls.get(layerIndex).label, layerLabel)) return layerRef = ls.get(layerIndex);
        for (LayerConfig.Layer l : ls) if (java.util.Objects.equals(l.label, layerLabel) && java.util.Objects.equals(l.folder, layerFolder)) return layerRef = l;
        return null;
    }

    React.Graph graph() {
        if (folder != null) { LayerConfig.Folder f = cfg().folders.get(folder); return f == null ? null : f.reactions; }
        LayerConfig.Layer l = layer();
        return l == null ? null : l.reactions;
    }

    /** The graph, made (empty) if there isn't one yet. */
    React.Graph ensure() {
        React.Graph g = graph();
        if (g != null) return g;
        g = new React.Graph();
        if (folder != null) cfg().folder(folder).reactions = g;
        else if (layer() != null) layer().reactions = g;
        return g;
    }

    private String objKey() {
        if (folder != null) return React.folderKey(folder);
        LayerConfig.Layer l = layer();
        return l == null ? "?" : React.layerKey(l);
    }

    private String targetName() {
        if (folder != null) return folder;
        LayerConfig.Layer l = layer();
        return l == null ? "?" : l.label;
    }

    private boolean targetIsParticles() { LayerConfig.Layer l = layer(); return l != null && l.isParticles(); }

    /** For tests: save as the screen does. */
    void save0() { save(); }

    private void save() {
        React.Graph g = graph();
        if (g != null && g.isEmpty() && g.enabled) {   // an empty graph isn't kept
            if (folder != null) { LayerConfig.Folder f = cfg().folders.get(folder); if (f != null) f.reactions = null; }
            else if (layer() != null) layer().reactions = null;
        }
        parent.save();
    }

    // ------------------------------------------------------------------ frame
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, width, height, 0xFF0A1A7A, 0xFF050B33);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        super.extractRenderState(g, mx, my, pt);
        hits.clear();
        tip = null;
        editRect = null;
        int W = width, H = height;
        int insW = Math.max(150, Math.min(220, W * 34 / 100));
        cvX = 0; cvY = BAR; cvW = W - insW; cvH = H - BAR;
        React.Graph gr = graph();
        if (fitLater && cvW > 0) { fitLater = false; fit(); }

        canvas(g, mx, my, gr);
        int pvH = showPreview && H - BAR > 200 ? Math.min(118, (H - BAR) * 2 / 7) : 0;
        inspector(g, mx, my, W - insW, BAR, insW, H - BAR - pvH);
        if (pvH > 0) preview(g, mx, my, W - insW, H - pvH, insW, pvH);
        else if (H - BAR > 200) {   // hidden: a small button brings it back
            String s = Lang.t("react.preview_show");
            int bw = font.width(RainiumScreen.body(s)) + 10, bx = W - bw - 6, by = H - 16;
            boolean hov = in(mx, my, bx, by, bw, 12);
            g.fill(bx, by, bx + bw, by + 12, hov ? FIELD_HOVER : FIELD);
            g.text(font, RainiumScreen.body(s), bx + 5, by + 2, hov ? PAPER : MUTED, false);
            hits.add(new Hit(bx, by, bw, 12, () -> showPreview = true, null));
        }
        topBar(g, mx, my, W);
        toast(g);
        if (fileOpen) fileMenu(g, mx, my, W);
        if (menuOpen) addMenu(g, mx, my);
        if (tip != null && !menuOpen) tooltip(g, mx, my, tip);
    }

    // ------------------------------------------------------------------ top bar
    private void topBar(GuiGraphicsExtractor g, int mx, int my, int W) {
        g.fill(0, 0, W, BAR, 0xF0050B33);
        g.fill(0, BAR - 1, W, BAR, 0x50FFFFFF);
        int x = 6;
        x += chip(g, mx, my, x, 5, Lang.t("react.back"), false, this::back) + 6;
        String title = Lang.t("react.title").toUpperCase(Locale.ROOT);
        int tw = strong(title) + 12;
        slant(g, x, 5, tw, 14, 4, PAPER);
        g.text(font, RainiumScreen.styled(title, true, true), x + 4, 8, INK, false);
        x += tw + 6;
        int right = W - 6;
        // right side, from the right
        React.Graph gr = graph();
        boolean on = gr == null || gr.enabled;
        int sw = 48;
        right -= sw;
        drawSwitch(g, right, 6, on);
        hits.add(new Hit(right, 5, sw, 14, () -> { React.Graph e = ensure(); e.enabled = !e.enabled; save(); say(Lang.t(e.enabled ? "react.msg.on" : "react.msg.off"), MUTED); }, null));
        if (in(mx, my, right, 5, sw, 14)) tip = Lang.t("react.tip.switch");
        right -= 6;
        String fitS = Lang.t("react.fit").toUpperCase(Locale.ROOT);
        right -= strong(fitS) + 14;
        chip(g, mx, my, right, 5, fitS, false, this::fit);
        right -= 4;
        String fileS = Lang.t("react.file").toUpperCase(Locale.ROOT);
        right -= strong(fileS) + 14;
        chip(g, mx, my, right, 5, fileS, fileOpen, () -> { fileOpen = !fileOpen; importOpen = false; confirmClear = false; });
        fileX = right;
        right -= 4;
        String addS = "+ " + Lang.t("react.add").toUpperCase(Locale.ROOT);
        int aw = strong(addS) + 14;
        right -= aw;
        boolean ah = in(mx, my, right, 5, aw, 14);
        slant(g, right, 5, aw, 14, 4, ah ? PAPER : CYAN);
        g.text(font, RainiumScreen.styled(addS, true, true), right + 5, 8, INK, false);
        final int ax = right;
        hits.add(new Hit(right, 5, aw, 14, () -> openMenu(ax, BAR + 2, centreX(), centreY(), -1, 0, false), null));
        // the name, between the title and the buttons
        String name = RainiumScreen.styled(targetName(), false, true).getString();
        String nm = fit((folder != null ? Lang.t("react.folder_prefix") + " " : "") + name, right - x - 8);
        g.text(font, RainiumScreen.body(nm), x, 8, MENU, false);
    }

    private int fileX;

    private int chip(GuiGraphicsExtractor g, int mx, int my, int x, int y, String label, boolean on, Runnable click) {
        String s = label.toUpperCase(Locale.ROOT);
        int w = strong(s) + 14;
        boolean hov = in(mx, my, x, y, w, 14);
        slant(g, x, y, w, 14, 4, on ? PAPER : hov ? BRIGHT : SEA);
        g.text(font, RainiumScreen.styled(s, true, true), x + 5, y + 3, on ? INK : PAPER, false);
        hits.add(new Hit(x, y, w, 14, click, null));
        return w;
    }

    private void back() {
        commitEdit();
        Minecraft.getInstance().gui.setScreen(parent);
    }

    // ------------------------------------------------------------------ canvas
    private float centreX() { return (cvW / 2f) / zoom - panX - NW / 2f; }
    private float centreY() { return (cvH / 2f) / zoom - panY - 20; }
    private float toCX(double sx) { return (float) ((sx - cvX) / zoom - panX); }
    private float toCY(double sy) { return (float) ((sy - cvY) / zoom - panY); }

    static int nodeH(React.Node n) {
        React.Type t = n.t();
        int rows = t == null ? 1 : Math.max(1, t.in().length);
        return HEAD + rows * PORT_ROW + SUMMARY + 3;
    }
    static float inX(React.Node n) { return n.x; }
    static float inY(React.Node n, int port) { return n.y + HEAD + 6 + port * PORT_ROW; }
    static float outX(React.Node n) { return n.x + NW; }
    static float outY(React.Node n) { return n.y + HEAD + 6; }

    private static int catColor(React.Cat c) {
        return switch (c) { case EVENT -> C_EVENT; case STATE -> C_STATE; case LOGIC -> C_LOGIC; case ACTION -> C_ACTION; };
    }

    private static int portColor(React.Port p) {
        return p == React.Port.EVENT ? C_EVENT : p == React.Port.LEVEL ? C_STATE : PAPER;
    }

    void fit() {
        React.Graph gr = graph();
        if (gr == null || gr.isEmpty() || cvW <= 0) { panX = 24; panY = 18; zoom = 1f; return; }
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (React.Node n : gr.nodes) { x0 = Math.min(x0, n.x); y0 = Math.min(y0, n.y); x1 = Math.max(x1, n.x + NW); y1 = Math.max(y1, n.y + nodeH(n)); }
        float w = x1 - x0 + 40, h = y1 - y0 + 40;
        zoom = snapZoom(Math.min(1.5f, Math.min(cvW / w, cvH / h)));
        panX = (cvW / zoom - (x1 - x0)) / 2 - x0;
        panY = (cvH / zoom - (y1 - y0)) / 2 - y0;
    }

    private static final float[] ZOOMS = {0.5f, 0.625f, 0.75f, 0.875f, 1f, 1.25f, 1.5f, 2f};
    private static float snapZoom(float z) {
        float best = ZOOMS[0];
        for (float s : ZOOMS) if (s <= z + 1e-3f) best = s;
        return best;
    }

    private void zoomAt(double sx, double sy, int dir) {
        int i = 0;
        for (int k = 0; k < ZOOMS.length; k++) if (Math.abs(ZOOMS[k] - zoom) < 1e-3f) i = k;
        int j = Math.max(0, Math.min(ZOOMS.length - 1, i + dir));
        if (j == i) return;
        float cx = toCX(sx), cy = toCY(sy);
        zoom = ZOOMS[j];
        panX = (float) ((sx - cvX) / zoom - cx);
        panY = (float) ((sy - cvY) / zoom - cy);
    }

    private void canvas(GuiGraphicsExtractor g, int mx, int my, React.Graph gr) {
        g.enableScissor(cvX, cvY, cvX + cvW, cvY + cvH);
        // grid
        float step = 20 * zoom;
        if (step >= 6) {
            float ox = ((panX * zoom) % step + step) % step, oy = ((panY * zoom) % step + step) % step;
            for (float x = cvX + ox; x < cvX + cvW; x += step) g.fill((int) x, cvY, (int) x + 1, cvY + cvH, 0x14FFFFFF);
            for (float y = cvY + oy; y < cvY + cvH; y += step) g.fill(cvX, (int) y, cvX + cvW, (int) y + 1, 0x14FFFFFF);
        }
        React.Runtime live = React.running(ReactSensors.selfKey(), objKey());
        double now = System.nanoTime() / 1e9;
        g.pose().pushMatrix();
        g.pose().translate(cvX + panX * zoom, cvY + panY * zoom);
        g.pose().scale(zoom, zoom);
        drawingCanvas = true;
        float cmx = toCX(mx), cmy = toCY(my);
        if (gr != null) {
            for (React.Wire w : gr.wires) {
                React.Node a = gr.node(w.from()), b = gr.node(w.to());
                if (a == null || b == null || a.t() == null) continue;
                if (drag == Drag.WIRE && wireBackward && b.id == wireNode && w.port() == wirePort) continue;   // being moved
                int col = portColor(a.t().out());
                double[] lv = live == null ? null : live.live.get(a.id);
                boolean hot = lv != null && (lv[0] > 0.5 || (lv[1] >= 0 && now - lv[1] < 0.35));
                wire(g, outX(a), outY(a), inX(b), inY(b, w.port()), hot ? PAPER : (col & 0x00FFFFFF) | 0xB0000000, hot ? 2.2f : 1.4f);
            }
            if (drag == Drag.WIRE) {
                React.Node a = gr.node(wireNode);
                if (a != null) {
                    int col = wireBackward ? PAPER : portColor(a.t().out());
                    if (wireBackward) wire(g, cmx, cmy, inX(a), inY(a, wirePort), col, 1.6f);
                    else wire(g, outX(a), outY(a), cmx, cmy, col, 1.6f);
                }
            }
            for (React.Node n : gr.nodes) node(g, cmx, cmy, n, live, now, gr);
        }
        drawingCanvas = false;
        g.pose().popMatrix();
        if (gr == null || gr.isEmpty()) emptyHint(g);
        // zoom readout
        String zs = Math.round(zoom * 100) + "%";
        g.text(font, RainiumScreen.body(zs), cvX + cvW - font.width(RainiumScreen.body(zs)) - 6, cvY + cvH - 12, FAINT, false);
        g.disableScissor();
        // the canvas takes clicks and the wheel last (everything else is on top)
        hits.add(0, new Hit(cvX, cvY, cvW, cvH, null, d -> zoomAt(lastMx, lastMy, d)));
    }

    private void emptyHint(GuiGraphicsExtractor g) {
        int w = Math.min(cvW - 40, 230), x = cvX + (cvW - w) / 2, y = cvY + Math.max(20, cvH / 3 - 20);
        String a = Lang.t("react.empty");
        g.text(font, RainiumScreen.styled(a, false, true), x + (w - font.width(RainiumScreen.styled(a, false, true))) / 2, y, MENU, false);
        int yy = y + 14;
        for (String line : wrap(Lang.t("react.empty_hint"), w)) {
            g.text(font, RainiumScreen.body(line), x + (w - font.width(RainiumScreen.body(line))) / 2, yy, FAINT, false);
            yy += 10;
        }
    }

    private void node(GuiGraphicsExtractor g, float cmx, float cmy, React.Node n, React.Runtime live, double now, React.Graph gr) {
        React.Type t = n.t();
        if (t == null) return;
        int x = Math.round(n.x), y = Math.round(n.y), h = nodeH(n);
        int col = catColor(t.cat());
        boolean sel = n.id == selected;
        double[] lv = live == null ? null : live.live.get(n.id);
        double since = lv == null || lv[1] < 0 ? 99 : now - lv[1];
        float level = lv == null ? 0 : (float) lv[0];
        boolean hov = cmx >= x && cmx < x + NW && cmy >= y && cmy < y + h;
        // body
        g.fill(x + 2, y + 2, x + NW + 2, y + h + 2, 0x60000000);   // shadow
        g.fill(x, y, x + NW, y + h, sel ? 0xF81A2D86 : 0xF00A1450);
        int edge = sel ? PAPER : hov ? 0xFF8FA0E0 : FIELD_EDGE;
        g.outline(x, y, NW, h, edge);
        // header: lights up when it fires
        int head = since < 0.35 ? PAPER : col;
        g.fill(x, y, x + NW, y + HEAD, head);
        String title = fitStrong(Lang.t("react.node." + n.type).toUpperCase(Locale.ROOT), NW - (t.cat() == React.Cat.EVENT || t.cat() == React.Cat.STATE ? 22 : 8));
        g.text(font, RainiumScreen.styled(title, true, true), x + 4, y + 2, INK, false);
        // test buttons in the header
        if (t.cat() == React.Cat.EVENT && !n.type.equals("interval")) {
            int bx = x + NW - 13;
            boolean bh = cmx >= bx && cmx < bx + 11 && cmy >= y + 1 && cmy < y + 12;
            g.fill(bx, y + 1, bx + 11, y + 12, bh ? INK : 0x40050B33);
            triangle(g, bx + 3.5f, y + 3.5f, bx + 3.5f, y + 9.5f, bx + 8.5f, y + 6.5f, bh ? col : INK);
            if (bh) tip = Lang.t("react.tip.test_event");
        } else if (t.cat() == React.Cat.STATE) {
            int bx = x + NW - 13;
            boolean on = React.testOn.contains(objKey() + "#" + n.id);
            boolean bh = cmx >= bx && cmx < bx + 11 && cmy >= y + 1 && cmy < y + 12;
            g.fill(bx, y + 1, bx + 11, y + 12, on ? INK : bh ? 0x80050B33 : 0x40050B33);
            g.fill(bx + 3, y + 4, bx + 8, y + 9, on ? col : bh ? PAPER : INK);
            if (bh) tip = Lang.t(on ? "react.tip.test_state_off" : "react.tip.test_state");
        }
        // inputs
        for (int i = 0; i < t.in().length; i++) {
            float py = inY(n, i);
            React.Port want = t.in()[i];
            React.Port got = gr.incoming(n, i);
            port(g, x, py, want == React.Port.ANY && got != null ? got : want, gr.input(n.id, i) != null);
            String pl = Lang.optional("react.port." + n.type + "." + i);
            if (pl != null) g.text(font, RainiumScreen.body(pl), x + 6, Math.round(py) - 4, FAINT, false);
        }
        // output
        if (t.out() != null) port(g, x + NW, outY(n), t.out(), gr.wires.stream().anyMatch(w -> w.from() == n.id));
        // summary
        String sum = fit(summary(n, gr), NW - 8);
        g.text(font, RainiumScreen.body(sum), x + 4, y + h - SUMMARY - 3, sum.startsWith("!") ? WARN : MUTED, false);
        if (t.self()) {
            String you = Lang.t("react.you_only_short");
            int yw = font.width(RainiumScreen.body(you));
            g.text(font, RainiumScreen.body(you), x + NW - yw - 4, y + HEAD + 2, FAINT, false);
        }
        // live level
        if (level > 0.001f) g.fill(x + 1, y + h - 2, x + 1 + Math.round((NW - 2) * Math.min(1f, level)), y + h - 1, col);
    }

    private void port(GuiGraphicsExtractor g, float x, float y, React.Port p, boolean wired) {
        int c = portColor(p);
        if (p == React.Port.EVENT) {   // a diamond
            triangle(g, x - 4, y, x + 4, y, x, y - 4, c);
            triangle(g, x - 4, y, x + 4, y, x, y + 4, c);
            if (!wired) { triangle(g, x - 2, y, x + 2, y, x, y - 2, INK); triangle(g, x - 2, y, x + 2, y, x, y + 2, INK); }
        } else {                       // a square (a level), or white (either)
            g.fill(Math.round(x) - 3, Math.round(y) - 3, Math.round(x) + 4, Math.round(y) + 4, c);
            if (!wired) g.fill(Math.round(x) - 1, Math.round(y) - 1, Math.round(x) + 2, Math.round(y) + 2, INK);
        }
    }

    /** One line about what a node is set to, under its inputs. "!" first = something to fix. */
    private String summary(React.Node n, React.Graph gr) {
        React.Type t = n.t();
        if (t.isAction()) {
            React.Port in = gr.incoming(n, 0);
            if (in == null) return "! " + Lang.t("react.sum.unwired");
            String main = switch (n.type) {
                case "tint" -> n.str("color");
                case "opacity" -> pct(n.num("value"));
                case "glow" -> pct(n.num("strength"));
                case "rainbow" -> num(n.num("speed")) + " Hz";
                case "scale" -> num(n.num("factor")) + "×";
                case "move" -> num(n.num("x")) + ", " + num(n.num("y")) + ", " + num(n.num("z"));
                case "rotate" -> num(n.num("x")) + "°, " + num(n.num("y")) + "°, " + num(n.num("z")) + "°";
                case "spin" -> num(n.num("speed")) + "°/s";
                case "shake" -> num(n.num("strength"));
                case "bob" -> num(n.num("amount"));
                case "particles" -> "+" + (int) n.num("burst") + " · " + num(n.num("rate")) + "×";
                default -> "";
            };
            String time = in == React.Port.EVENT
                ? Lang.t("react.sum.for", num(n.num("hold")))
                : Lang.t("react.sum.while");
            return main.isEmpty() ? time : main + " · " + time;
        }
        StringBuilder b = new StringBuilder();
        for (React.Param q : t.params()) {
            if (!applies(n, q, gr)) continue;
            if (b.length() > 0) b.append(" · ");
            b.append(valueText(n, q));
        }
        if (t.cat() == React.Cat.LOGIC) {
            for (int i = 0; i < t.in().length; i++) if (gr.input(n.id, i) == null && !n.type.equals("gate") && !n.type.equals("either") && !n.type.equals("and") && !n.type.equals("or") && !n.type.equals("switch")) return "! " + Lang.t("react.sum.needs_input");
        }
        return b.toString();
    }

    private static String pct(double v) { return Math.round(v * 100) + "%"; }

    static String num(double v) {
        if (Math.abs(v - Math.round(v)) < 1e-6) return String.valueOf(Math.round(v));
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    String valueText(React.Node n, React.Param q) {
        return switch (q.kind()) {
            case NUM, INT -> num(n.num(q.key())) + (q.unit().isEmpty() ? "" : q.unit().equals("%") || q.unit().equals("°") || q.unit().equals("×") ? q.unit() : " " + unitText(q.unit()));
            case CHOICE -> Lang.t("react.choice." + q.key() + "." + n.str(q.key()));
            case CURVE -> curveName(n.str(q.key()));
            case COLOR -> n.str(q.key());
            case TEXT -> n.str(q.key()).isBlank() ? Lang.t("react.any") : ReactSensors.shortId(n.str(q.key()));
            case BOOL -> Lang.t(Boolean.TRUE.equals(n.get(q.key())) ? "value.on" : "value.off");
        };
    }

    private static String unitText(String u) { String t = Lang.optional("react.unit." + u.replace('/', '_')); return t == null ? u : t; }

    static String curveName(String c) { return Lang.t("react.curve." + (c.startsWith("bezier:") ? "custom" : c)); }

    /** Whether a setting matters right now (hold only after an event, etc.). */
    static boolean applies(React.Node n, React.Param q, React.Graph gr) {
        String w = q.when();
        if (w == null) return true;
        if (w.equals("event")) return gr.incoming(n, 0) != React.Port.LEVEL;
        if (w.equals("level")) return gr.incoming(n, 0) == React.Port.LEVEL;
        int ne = w.indexOf("!="), eq = w.indexOf('=');
        if (ne > 0) return !n.str(w.substring(0, ne)).equals(w.substring(ne + 2));
        if (eq > 0) return n.str(w.substring(0, eq)).equals(w.substring(eq + 1));
        return true;
    }

    /** A wire: a smooth curve from an output to an input, drawn in small steps at screen-pixel size. */
    private void wire(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, int color, float thick) {
        float s = guiScale() * zoom;
        float dx = Math.max(24, Math.abs(x2 - x1) * 0.5f);
        float ax = x1 + dx, bx = x2 - dx;
        double len = Math.hypot(x2 - x1, y2 - y1) + dx;
        int steps = (int) Math.max(8, Math.min(800, len * s / 1.5));
        g.pose().pushMatrix();
        g.pose().scale(1f / s, 1f / s);
        int t = Math.max(1, Math.round(thick * guiScale()));
        int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
        for (int i = 0; i <= steps; i++) {
            float u = i / (float) steps, v = 1 - u;
            float px = v * v * v * x1 + 3 * v * v * u * ax + 3 * v * u * u * bx + u * u * u * x2;
            float py = v * v * v * y1 + 3 * v * v * u * y1 + 3 * v * u * u * y2 + u * u * u * y2;
            int qx = Math.round(px * s), qy = Math.round(py * s);
            if (qx == lastX && qy == lastY) continue;
            lastX = qx; lastY = qy;
            g.fill(qx - t / 2, qy - t / 2, qx - t / 2 + t, qy - t / 2 + t, color);
        }
        g.pose().popMatrix();
    }

    // ------------------------------------------------------------------ the little preview of you
    private void preview(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xF0081046);
        g.fill(x, y, x + 1, y + h, 0x50FFFFFF);
        g.fill(x, y, x + w, y + 1, 0x50FFFFFF);
        g.text(font, RainiumScreen.styled(Lang.t("react.preview").toUpperCase(Locale.ROOT), true, true), x + 4, y + 3, MENU, false);
        int cx = x + w - 11;
        boolean ch = in(mx, my, cx, y + 1, 10, 10);
        g.text(font, RainiumScreen.body("×"), cx + 2, y + 2, ch ? PAPER : FAINT, false);
        hits.add(new Hit(x, y, w, h, () -> { }, null));   // clicks on the preview don't reach anything under it
        hits.add(new Hit(cx, y + 1, 10, 10, () -> showPreview = false, null));
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.entity.player.Player player = Self.previewPlayer(cfg());
        if (player == null) {
            for (String line : wrap(Lang.t("preview.no_world"), w - 8)) { g.text(font, RainiumScreen.body(line), x + 4, y + 20, FAINT, false); y += 10; }
            return;
        }
        try {
            EntityRenderer<?, ?> renderer = mc.getEntityRenderDispatcher().getRenderer(player);
            float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            @SuppressWarnings({"unchecked", "rawtypes"})
            EntityRenderState st = ((EntityRenderer) renderer).createRenderState(player, pt);
            st.shadowPieces.clear();
            st.outlineColor = 0;
            float bbh = 1.8f;
            if (st instanceof LivingEntityRenderState ls) {
                ls.bodyRot = 180f + 160f;
                ls.yRot = 0f;
                ls.xRot = 0f;
                ls.boundingBoxWidth = ls.boundingBoxWidth / ls.scale;
                ls.boundingBoxHeight = ls.boundingBoxHeight / ls.scale;
                ls.scale = 1f;
                bbh = ls.boundingBoxHeight;
            }
            RainiumScreen.previewState = st;   // so every layer shows, as in the editor's preview
            float size = Math.min(w, h - 12) / 2.6f;
            Quaternionf cam = new Quaternionf().rotateX(-8f * 0.017453292f);
            Quaternionf rot = new Quaternionf().rotateZ((float) Math.PI).mul(cam);
            g.entity(st, size, new Vector3f(0f, bbh / 2f + 0.0625f, 0f), rot, cam, x, y + 12, x + w, y + h);
        } catch (Throwable t) {
            g.text(font, RainiumScreen.body(Lang.t("preview.unavailable")), x + 4, y + 20, DANGER, false);
        }
    }

    // ------------------------------------------------------------------ the right-hand panel
    private void inspector(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xE8050B33);
        g.fill(x, y, x + 1, y + h, 0x50FFFFFF);
        int cx = x + PAD, cw = w - PAD * 2 - 3, top = y + 6, bottom = y + h - 4;
        int viewH = bottom - top;
        inspScroll = Math.max(0, Math.min(inspScroll, Math.max(0, inspContent - viewH)));
        int before = hits.size();
        g.enableScissor(x + 1, top, x + w, bottom);
        int cy = top - inspScroll, start = cy;
        React.Graph gr = graph();
        React.Node n = gr == null ? null : gr.node(selected);
        if (n == null) selected = -1;
        cy = n == null ? overview(g, mx, my, cx, cy, cw, gr) : nodePanel(g, mx, my, cx, cy, cw, gr, n);
        g.disableScissor();
        inspContent = cy - start + 6;
        for (int i = hits.size() - 1; i >= before; i--) { Hit hh = hits.get(i); if (hh.y() + hh.h() <= top || hh.y() >= bottom) hits.remove(i); }
        if (inspContent > viewH) {
            int barH = Math.max(14, viewH * viewH / inspContent);
            int barY = top + (viewH - barH) * inspScroll / Math.max(1, inspContent - viewH);
            g.fill(x + w - 3, barY, x + w - 2, barY + barH, 0xD048E6FF);
        }
        hits.add(before, new Hit(x, y, w, h, () -> { }, d -> inspScroll = Math.max(0, Math.min(inspScroll - d * 14, Math.max(0, inspContent - viewH)))));
    }

    /** With nothing selected: what this is, how to use it, and ready-made examples. */
    private int overview(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, React.Graph gr) {
        y = heading(g, x, y, w, Lang.t("react.section.reactions"));
        y = wrapped(g, x, y, w, Lang.t(folder != null ? "react.intro_folder" : "react.intro", targetName()), MUTED) + 4;
        int nodes = gr == null ? 0 : gr.nodes.size();
        g.text(font, RainiumScreen.body(Lang.t("react.count", nodes, gr == null ? 0 : gr.wires.size())), x, y, FAINT, false);
        y += 13;
        y = heading(g, x, y + 2, w, Lang.t("react.section.examples"));
        for (Template tp : TEMPLATES) {
            if (tp.particlesOnly && !targetIsParticles()) continue;
            String label = Lang.t("react.example." + tp.id);
            List<String> lines = wrap(label, w - 10);
            int bh = 4 + lines.size() * 10;
            boolean hov = in(mx, my, x, y, w, bh);
            g.fill(x, y, x + w, y + bh, hov ? FIELD_HOVER : FIELD);
            g.fill(x, y, x + 2, y + bh, catColor(React.Cat.ACTION));
            int ly = y + 3;
            for (String line : lines) { g.text(font, RainiumScreen.body(line), x + 6, ly, TEXT, false); ly += 10; }
            hits.add(new Hit(x, y, w, bh, () -> addTemplate(tp), null));
            y += bh + 2;
        }
        y = heading(g, x, y + 4, w, Lang.t("react.section.how"));
        for (int i = 1; i <= 6; i++) {
            String s = Lang.optional("react.how." + i);
            if (s == null) break;
            y = wrapped(g, x, y, w, "• " + s, MUTED) + 2;
        }
        return y;
    }

    private int nodePanel(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, React.Graph gr, React.Node n) {
        React.Type t = n.t();
        int col = catColor(t.cat());
        String cat = Lang.t("react.cat." + t.cat().name().toLowerCase(Locale.ROOT)).toUpperCase(Locale.ROOT);
        int tw = strong(cat) + 12;
        slant(g, x, y, tw, 11, 3, col);
        g.text(font, RainiumScreen.styled(cat, true, true), x + 4, y + 2, INK, false);
        // close: back to the overview
        int bx = x + w - 10;
        boolean bh = in(mx, my, bx, y, 10, 11);
        g.text(font, RainiumScreen.body("×"), bx + 2, y + 1, bh ? PAPER : FAINT, false);
        hits.add(new Hit(bx, y, 10, 11, () -> selected = -1, null));
        y += 14;
        String name = Lang.t("react.node." + n.type);
        g.text(font, RainiumScreen.styled(fitStrong(name.toUpperCase(Locale.ROOT), w), true, true), x, y, PAPER, false);
        y += 13;
        y = wrapped(g, x, y, w, Lang.t("react.node." + n.type + ".desc"), MUTED) + 3;
        if (t.self()) y = wrapped(g, x, y, w, Lang.t("react.you_only"), WARN) + 3;
        if (t.isAction() && n.type.equals("particles") && !targetIsParticles()) y = wrapped(g, x, y, w, Lang.t("react.particles_only"), WARN) + 3;

        // try it
        if (t.cat() == React.Cat.EVENT && !n.type.equals("interval")) {
            y = button(g, mx, my, x, y, w, Lang.t("react.test_event"), () -> testEvent(n));
        } else if (t.cat() == React.Cat.STATE) {
            String k = objKey() + "#" + n.id;
            boolean on = React.testOn.contains(k);
            y = toggleRow(g, mx, my, x, y, w, Lang.t("react.test_state"), on, () -> { if (!React.testOn.remove(k)) React.testOn.add(k); });
        }

        if (t.isAction()) {
            React.Port in = gr.incoming(n, 0);
            String how = in == null ? Lang.t("react.input.none") : in == React.Port.EVENT ? Lang.t("react.input.event") : Lang.t("react.input.level");
            y = wrapped(g, x, y + 2, w, how, in == null ? WARN : TEXT) + 4;
            y = envelope(g, x, y, w, n, in);
        }

        y = heading(g, x, y + 2, w, Lang.t("react.section.settings"));
        boolean any = false;
        for (React.Param q : t.params()) {
            if (!applies(n, q, gr)) continue;
            any = true;
            y = paramRow(g, mx, my, x, y, w, n, q);
        }
        if (!any) y = wrapped(g, x, y, w, Lang.t("react.no_settings"), FAINT) + 3;

        y += 4;
        int half = (w - GAP) / 2;
        button(g, mx, my, x, y, half, Lang.t("react.duplicate"), () -> duplicate(n));
        y = dangerButton(g, mx, my, x + half + GAP, y, w - half - GAP, Lang.t("react.delete"), () -> delete(n));
        return y;
    }

    private void testEvent(React.Node n) {
        Object[] v = React.testValues(n);
        ReactSensors.test(n.type, (Float) v[0], (Integer) v[1], (String) v[2]);
        say(Lang.t("react.msg.tested", Lang.t("react.node." + n.type)), MUTED);
    }

    /** The shape of an action over time: fade in, hold (after an event) and fade out, with the chosen curves. */
    private int envelope(GuiGraphicsExtractor g, int x, int y, int w, React.Node n, React.Port in) {
        int h = 40;
        g.fill(x, y, x + w, y + h, FIELD);
        g.outline(x, y, w, h, FIELD_EDGE);
        double fin = n.num("in"), hold = in == React.Port.LEVEL ? Math.max(0.6, (fin + n.num("out")) * 0.6) : n.num("hold"), fout = n.num("out");
        double total = Math.max(0.1, fin + hold + fout);
        int gx = x + 4, gw = w - 8, gy = y + 6, gh = h - 14;
        float s = guiScale();
        int lastX = -1, lastY = -1;
        int col = catColor(React.Cat.ACTION);
        for (int i = 0; i <= gw * 2; i++) {
            double tt = total * i / (gw * 2.0), v;
            if (tt < fin) v = React.curve(n.str("in_curve"), tt / Math.max(1e-6, fin));
            else if (tt <= fin + hold) v = 1;
            else v = 1 - React.curve(n.str("out_curve"), (tt - fin - hold) / Math.max(1e-6, fout));
            int px = gx + i / 2, py = gy + gh - (int) Math.round(Math.max(-0.3, Math.min(1.3, v)) * gh);
            if (px == lastX && py == lastY) continue;
            g.fill(px, py, px + 1, py + 1, col);
            if (lastX >= 0 && Math.abs(py - lastY) > 1) g.fill(px, Math.min(py, lastY), px + 1, Math.max(py, lastY), col);
            lastX = px; lastY = py;
        }
        int ex = gx + (int) (gw * fin / total), hx = gx + (int) (gw * (fin + hold) / total);
        g.fill(ex, gy, ex + 1, gy + gh, 0x40FFFFFF);
        g.fill(hx, gy, hx + 1, gy + gh, 0x40FFFFFF);
        String lbl = in == React.Port.LEVEL ? Lang.t("react.env.level", num(fin), num(fout)) : Lang.t("react.env.event", num(fin), num(hold), num(fout));
        g.text(font, RainiumScreen.body(fit(lbl, w - 6)), x + 3, y + h - 9, FAINT, false);
        return y + h + GAP + 2;
    }

    // ---- setting rows
    private int paramRow(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, React.Node n, React.Param q) {
        String label = Lang.t("react.param." + q.key());
        String own = Lang.optional("react.param." + n.type + "." + q.key());
        if (own != null) label = own;
        int lw = Math.min(64, w * 2 / 5), vx = x + lw + 2, vw = w - lw - 2;
        boolean band = in(mx, my, x - 4, y - 1, w + 6, ROW + 2) && editId == null;
        if (band) { g.fill(x - 4, y - 1, x + w + 2, y + ROW + 1, 0xF2FFFFFF); g.fill(x - 4, y + ROW, x + w + 2, y + ROW + 1, RED); }
        g.text(font, RainiumScreen.styled(fit(label, lw), false, true), x, y + 3, band ? INK : MENU, false);
        String id = n.id + ":" + q.key();
        switch (q.kind()) {
            case NUM, INT -> {
                int bw = 12;
                stepBtn(g, mx, my, vx, y, bw, "−", () -> stepParam(n, q, -1));
                stepBtn(g, mx, my, vx + vw - bw, y, bw, "+", () -> stepParam(n, q, 1));
                field(g, mx, my, vx + bw + 1, y, vw - bw * 2 - 2, id, valueText(n, q), num(n.num(q.key())), true, v -> {
                    Double d = parse(v);
                    if (d != null) { n.set(q.key(), d); save(); }
                });
                hits.add(new Hit(x, y, w, ROW, null, d -> stepParam(n, q, d)));
            }
            case CHOICE, CURVE -> {
                List<String> opts = q.choices();
                String cur = n.str(q.key());
                int ci = q.kind() == React.Kind.CURVE && cur.startsWith("bezier:") ? opts.indexOf("custom") : opts.indexOf(cur);
                boolean hov = in(mx, my, vx, y, vw, ROW);
                g.fill(vx, y, vx + vw, y + ROW, hov ? FIELD_HOVER : FIELD);
                g.outline(vx, y, vw, ROW, FIELD_EDGE);
                String v = fit(valueText(n, q), vw - 20);
                g.text(font, RainiumScreen.body("‹"), vx + 3, y + 3, hov ? PAPER : FAINT, false);
                g.text(font, RainiumScreen.body("›"), vx + vw - 7, y + 3, hov ? PAPER : FAINT, false);
                g.text(font, RainiumScreen.body(v), vx + (vw - font.width(RainiumScreen.body(v))) / 2, y + 3, TEXT, false);
                java.util.function.IntConsumer step = d -> {
                    int k = Math.floorMod((ci < 0 ? 0 : ci) + d, opts.size());
                    String nv = opts.get(k);
                    if (q.kind() == React.Kind.CURVE && nv.equals("custom")) nv = cur.startsWith("bezier:") ? cur : React.CUSTOM_DEFAULT;
                    n.set(q.key(), nv);
                    save();
                };
                hits.add(new Hit(vx, y, vw / 2, ROW, () -> step.accept(-1), step));
                hits.add(new Hit(vx + vw / 2, y, vw - vw / 2, ROW, () -> step.accept(1), step));
                hits.add(new Hit(x, y, lw, ROW, null, step));
                if (q.kind() == React.Kind.CURVE && cur.startsWith("bezier:")) {
                    y += ROW + GAP;
                    return curveEditor(g, mx, my, x, y, w, n, q.key());
                }
            }
            case COLOR -> {
                int cur = n.rgb(q.key());
                g.fill(vx, y + 1, vx + 12, y + ROW - 1, 0xFF000000 | cur);
                g.outline(vx, y + 1, 12, ROW - 2, PAPER);
                field(g, mx, my, vx + 14, y, vw - 14, id, "#" + n.str(q.key()).replace("#", ""), n.str(q.key()).replace("#", ""), false, v -> {
                    String hex = v.trim().replace("#", "");
                    if (hex.matches("[0-9a-fA-F]{6}")) { n.set(q.key(), "#" + hex.toUpperCase(Locale.ROOT)); save(); }
                    else say(Lang.t("react.msg.bad_colour"), DANGER);
                });
                // a row of ready colours under it
                y += ROW + 2;
                int[] sw0 = {0xFF3050, 0xFF8A30, 0xFFE040, 0x50FF70, 0x40E6FF, 0x3070FF, 0xB050FF, 0xFF60D0, 0xFFFFFF, 0x202020};
                int sw = Math.max(6, Math.min(14, (w - (sw0.length - 1)) / sw0.length)), sx0 = x;
                for (int c : sw0) {
                    boolean hov = in(mx, my, sx0, y, sw, 9);
                    g.fill(sx0, y, sx0 + sw, y + 9, 0xFF000000 | c);
                    if (c == cur || hov) g.outline(sx0 - 1, y - 1, sw + 2, 11, PAPER);
                    hits.add(new Hit(sx0, y, sw, 9, () -> { n.set(q.key(), String.format("#%06X", c)); save(); }, null));
                    sx0 += sw + 1;
                }
                return y + 9 + GAP + 2;
            }
            case TEXT -> {
                String pick = q.choices() == null ? null : q.choices().get(0);
                int pw = pick == null ? 0 : 30;
                field(g, mx, my, vx, y, vw - pw - (pw > 0 ? 2 : 0), id, valueText(n, q), ReactSensors.shortId(n.str(q.key())), false, v -> { n.set(q.key(), ReactSensors.id(v)); save(); });
                if (pick != null) {
                    int px = vx + vw - pw;
                    boolean hov = in(mx, my, px, y, pw, ROW);
                    slant(g, px, y, pw, ROW, 3, hov ? BRIGHT : SEA);
                    String pl = Lang.t("react.pick." + pick);
                    g.text(font, RainiumScreen.styled(fitStrong(pl, pw - 4), true, true), px + 3, y + 3, PAPER, false);
                    hits.add(new Hit(px, y, pw, ROW, () -> pickCurrent(n, q, pick), null));
                    if (hov) tip = Lang.t("react.tip.pick." + pick);
                }
            }
            case BOOL -> {
                boolean on = Boolean.TRUE.equals(n.get(q.key()));
                drawSwitch(g, vx + vw - 48, y + 1, on);
                hits.add(new Hit(vx, y, vw, ROW, () -> { n.set(q.key(), !on); save(); }, null));
            }
        }
        return y + ROW + GAP;
    }

    private void pickCurrent(React.Node n, React.Param q, String pick) {
        String v = switch (pick) {
            case "held", "using" -> ReactSensors.heldItem();
            case "dimension" -> ReactSensors.myDimension();
            case "biome" -> ReactSensors.myBiome();
            case "effect" -> {
                List<String> mine = ReactSensors.myEffects();
                if (mine.isEmpty()) yield null;
                int i = mine.indexOf(ReactSensors.shortId(n.str(q.key())));
                yield mine.get((i + 1) % mine.size());
            }
            default -> null;
        };
        if (v == null) { say(Lang.t("react.msg.pick_none." + pick), WARN); return; }
        n.set(q.key(), ReactSensors.id(v));
        save();
    }

    private void stepParam(React.Node n, React.Param q, int d) {
        double v = n.num(q.key()) + d * q.step();
        n.set(q.key(), v);
        save();
    }

    /** A custom curve: drag the two handles. */
    private int curveEditor(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, React.Node n, String key) {
        int size = Math.min(w - 20, 90), bx = x + (w - size) / 2, by = y + 8;
        g.fill(bx - 1, by - 8, bx + size + 1, by + size + 8, FIELD);
        g.outline(bx - 1, by - 8, size + 2, size + 16, FIELD_EDGE);
        g.fill(bx, by + size, bx + size, by + size + 1, 0x40FFFFFF);
        g.fill(bx, by, bx + size, by + 1, 0x40FFFFFF);
        double[] b = React.bezier(n.str(key));
        int lastX = -1, lastY = -1;
        for (int i = 0; i <= size * 2; i++) {
            double t = i / (size * 2.0);
            int px = bx + (int) Math.round(t * size), py = by + size - (int) Math.round(React.curve(n.str(key), t) * size);
            if (px == lastX && py == lastY) continue;
            g.fill(px, py, px + 1, py + 1, C_ACTION);
            if (lastX >= 0 && Math.abs(py - lastY) > 1) g.fill(px, Math.min(py, lastY), px + 1, Math.max(py, lastY), C_ACTION);
            lastX = px; lastY = py;
        }
        int[][] hs = {{bx + (int) Math.round(b[0] * size), by + size - (int) Math.round(b[1] * size)}, {bx + (int) Math.round(b[2] * size), by + size - (int) Math.round(b[3] * size)}};
        int[][] ends = {{bx, by + size}, {bx + size, by}};
        for (int i = 0; i < 2; i++) {
            line(g, ends[i][0], ends[i][1], hs[i][0], hs[i][1], 0x80FFFFFF);
            boolean hov = Math.abs(mx - hs[i][0]) <= 4 && Math.abs(my - hs[i][1]) <= 4;
            g.fill(hs[i][0] - 2, hs[i][1] - 2, hs[i][0] + 3, hs[i][1] + 3, hov || (drag == Drag.HANDLE && handle == i) ? PAPER : CYAN);
            final int hi = i;
            hits.add(new Hit(hs[i][0] - 4, hs[i][1] - 4, 9, 9, () -> { drag = Drag.HANDLE; handle = hi; handleKey = key; dragNode = n.id; curveBox = new int[]{bx, by, size}; }, null));
        }
        if (drag == Drag.HANDLE && dragNode == n.id && key.equals(handleKey)) curveBox = new int[]{bx, by, size};
        return wrapped(g, x, by + size + 10, w, Lang.t("react.curve.drag"), FAINT) + 3;
    }

    private int[] curveBox;

    private void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int col) {
        int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= n; i++) {
            int px = x0 + (n == 0 ? 0 : (x1 - x0) * i / n), py = y0 + (n == 0 ? 0 : (y1 - y0) * i / n);
            g.fill(px, py, px + 1, py + 1, col);
        }
    }

    // ---- small widgets
    private int heading(GuiGraphicsExtractor g, int x, int y, int w, String title) {
        String t = fitStrong(title.toUpperCase(Locale.ROOT), w - 30);
        int tw = strong(t) + 13;
        slant(g, x, y + 2, tw, 11, 3, PAPER);
        g.text(font, RainiumScreen.styled(t, true, true), x + 4, y + 4, INK, false);
        int lx = x + tw + 4, n = 6;
        for (int i = 0; i < n; i++) g.fill(lx + (w - tw - 4) * i / n, y + 7, lx + (w - tw - 4) * (i + 1) / n, y + 8, ((0x80 - i * 0x14) << 24) | 0xFFFFFF);
        return y + 17;
    }

    private int button(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, Runnable click) {
        boolean hov = in(mx, my, x, y, w, ROW);
        slant(g, x, y, w, ROW, 4, hov ? BRIGHT : SEA);
        String s = fitStrong(label, w - 8);
        g.text(font, RainiumScreen.styled(s, true, true), x + (w - 3 - strong(s)) / 2, y + 3, PAPER, false);
        hits.add(new Hit(x, y, w, ROW, click, null));
        return y + ROW + GAP;
    }

    private int dangerButton(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, Runnable click) {
        boolean hov = in(mx, my, x, y, w, ROW);
        g.fill(x, y, x + w, y + ROW, hov ? 0xFF5A2338 : FIELD);
        g.outline(x, y, w, ROW, hov ? DANGER : FIELD_EDGE);
        String s = fit(label, w - 4);
        g.text(font, RainiumScreen.body(s), x + (w - font.width(RainiumScreen.body(s))) / 2, y + 3, hov ? DANGER : TEXT, false);
        hits.add(new Hit(x, y, w, ROW, click, null));
        return y + ROW + GAP;
    }

    private void stepBtn(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String s, Runnable click) {
        boolean hov = in(mx, my, x, y, w, ROW);
        g.fill(x, y, x + w, y + ROW, hov ? BRIGHT : SEA);
        g.text(font, RainiumScreen.body(s), x + (w - font.width(RainiumScreen.body(s))) / 2, y + 3, PAPER, false);
        hits.add(new Hit(x, y, w, ROW, click, null));
    }

    private int toggleRow(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, boolean on, Runnable flip) {
        boolean band = in(mx, my, x - 4, y - 1, w + 6, ROW + 2);
        if (band) { g.fill(x - 4, y - 1, x + w + 2, y + ROW + 1, 0xF2FFFFFF); g.fill(x - 4, y + ROW, x + w + 2, y + ROW + 1, RED); }
        g.text(font, RainiumScreen.styled(fit(label, w - 54), false, true), x, y + 3, band ? INK : MENU, false);
        drawSwitch(g, x + w - 48, y + 1, on);
        hits.add(new Hit(x, y, w, ROW, flip, null));
        return y + ROW + GAP;
    }

    private void drawSwitch(GuiGraphicsExtractor g, int x, int y, boolean on) {
        int w = 48, h = 12, half = w / 2;
        capsule(g, x, y, w, h, FIELD);
        capsule(g, on ? x + 1 : x + half - 1, y + 1, half, h - 2, PAPER);
        String a = Lang.t("value.on").toUpperCase(Locale.ROOT), b = Lang.t("value.off").toUpperCase(Locale.ROOT);
        g.text(font, RainiumScreen.body(a), x + (half - font.width(RainiumScreen.body(a))) / 2 + 1, y + 2, on ? INK : FAINT, false);
        g.text(font, RainiumScreen.body(b), x + half + (half - font.width(RainiumScreen.body(b))) / 2 - 1, y + 2, on ? FAINT : INK, false);
    }

    /** A value you can click to type into. */
    private void field(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String id, String shown, String initial, boolean numeric, Consumer<String> commit) {
        boolean editing = id.equals(editId);
        boolean hov = in(mx, my, x, y, w, ROW);
        g.fill(x, y, x + w, y + ROW, editing ? INK : hov ? FIELD_HOVER : FIELD);
        g.outline(x, y, w, ROW, editing ? CYAN : FIELD_EDGE);
        String s = editing ? tail(editBuf.toString(), w - 8) + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "") : fit(shown, w - 4);
        int tx = editing ? x + 3 : x + (w - font.width(RainiumScreen.body(s))) / 2;
        g.text(font, RainiumScreen.body(s), tx, y + 3, editing && editReplace ? CYAN : TEXT, false);
        if (editing) editRect = new int[]{x, y, w, ROW};
        hits.add(new Hit(x, y, w, ROW, () -> beginEdit(id, initial, numeric, commit), null));
    }

    // ------------------------------------------------------------------ the add-a-node menu
    private static final int MENU_W = 240;
    private String menuGroup;   // a group of the current tab, or null for all of them

    private void openMenu(int sx, int sy, float cx, float cy, int link, int port, boolean backward) {
        menuOpen = true;
        fileOpen = false;
        menuX = sx; menuY = sy; menuCX = cx; menuCY = cy;
        linkNode = link; linkPort = port; linkBackward = backward;
        menuScroll = 0;
        menuGroup = null;
        filter.setLength(0);
        if (link >= 0) {   // start on a tab that has something that fits
            for (React.Cat c : React.Cat.values()) if (!menuTypes(c, null).isEmpty()) { menuCat = c; break; }
        }
    }

    /** Whether a node type can take the wire being dropped (if any). */
    private boolean fitsLink(React.Type t) {
        React.Graph gr = graph();
        React.Node link = gr == null || linkNode < 0 ? null : gr.node(linkNode);
        if (link == null || link.t() == null) return true;
        if (!linkBackward) return inputFor(t, link.t().out()) >= 0;   // the new node takes the wire in its first fitting input
        React.Port want = link.t().in()[linkPort];                     // the new node feeds the input the wire came from
        return t.out() != null && (want == React.Port.ANY || want == t.out());
    }

    /** Node types of a tab (and group, if not null) that fit the wire being dropped. */
    private List<React.Type> menuTypes(React.Cat c, String group) {
        List<React.Type> out = new ArrayList<>();
        for (React.Type t : React.TYPES.values()) {
            if (t.cat() != c || (group != null && !group.equals(t.group()))) continue;
            if (t.id().equals("particles") && !targetIsParticles()) continue;
            if (fitsLink(t)) out.add(t);
        }
        return out;
    }

    /** Every node type whose name, description or group has all the typed words (any tab). */
    private List<React.Type> searchTypes() {
        String[] words = filter.toString().toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<React.Type> out = new ArrayList<>();
        for (React.Type t : React.TYPES.values()) {
            if (t.id().equals("particles") && !targetIsParticles()) continue;
            if (!fitsLink(t)) continue;
            String hay = (Lang.t("react.node." + t.id()) + " " + Lang.t("react.node." + t.id() + ".desc") + " " + groupName(t.group()) + " "
                + Lang.t("react.cat." + t.cat().name().toLowerCase(Locale.ROOT)) + " " + t.id().replace('_', ' ')).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) if (!w.isEmpty() && !hay.contains(w)) { all = false; break; }
            if (all) out.add(t);
        }
        return out;
    }

    private static String groupName(String g) { return Lang.t("react.group." + g); }

    private static int inputFor(React.Type t, React.Port from) {
        if (from == null) return -1;
        for (int i = 0; i < t.in().length; i++) if (t.in()[i] == React.Port.ANY || t.in()[i] == from) return i;
        return -1;
    }

    private void addMenu(GuiGraphicsExtractor g, int mx, int my) {
        int w = Math.min(MENU_W, width - 8), h = Math.min(260, height - 30);
        int x = Math.max(4, Math.min(menuX, width - w - 4)), y = Math.max(BAR + 2, Math.min(menuY, height - h - 4));
        menuRect = new int[]{x, y, w, h};
        int first = hits.size();
        g.fill(x + 3, y + 3, x + w + 3, y + h + 3, 0x80000000);
        g.fill(x, y, x + w, y + h, 0xF8050B33);
        g.outline(x, y, w, h, PAPER);
        boolean searching = filter.length() > 0;

        // the search bar: typing anywhere in the menu goes into it
        int sx = x + 4, sy = y + 4, sw = w - 8;
        g.fill(sx, sy, sx + sw, sy + 14, INK);
        g.outline(sx, sy, sw, 14, searching ? CYAN : FIELD_EDGE);
        magnifier(g, sx + 6, sy + 7, searching ? CYAN : FAINT);
        String q = searching ? tail(filter.toString(), sw - 34) + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "") : Lang.t("react.menu.search_hint");
        g.text(font, RainiumScreen.body(fit(q, sw - 28)), sx + 13, sy + 3, searching ? TEXT : FAINT, false);
        if (searching) {
            int cx = sx + sw - 11;
            boolean ch = in(mx, my, cx, sy, 11, 14);
            g.text(font, RainiumScreen.body("×"), cx + 3, sy + 3, ch ? PAPER : FAINT, false);
            hits.add(new Hit(cx, sy, 11, 14, () -> { filter.setLength(0); menuScroll = 0; }, null));
        }
        int ty = sy + 18;

        // tabs, then the tab's groups (both hidden while searching: results come from every tab)
        if (!searching) {
            int tx = x + 4;
            for (React.Cat c : React.Cat.values()) {
                String s = Lang.t("react.cat." + c.name().toLowerCase(Locale.ROOT) + "s").toUpperCase(Locale.ROOT);
                int cw = strong(s) + 10;
                boolean on = c == menuCat, empty = menuTypes(c, null).isEmpty();
                boolean hov = in(mx, my, tx, ty, cw, 12);
                slant(g, tx, ty, cw, 12, 3, on ? catColor(c) : hov ? FIELD_HOVER : 0xFF0E1A5C);
                g.text(font, RainiumScreen.styled(s, true, true), tx + 4, ty + 2, on ? INK : empty ? FAINT : PAPER, false);
                hits.add(new Hit(tx, ty, cw, 12, () -> { menuCat = c; menuGroup = null; menuScroll = 0; }, null));
                tx += cw + 2;
            }
            ty += 15;
            List<String> groups = new ArrayList<>();
            groups.add(null);
            for (String gname : React.GROUPS.getOrDefault(menuCat, List.of())) if (!menuTypes(menuCat, gname).isEmpty()) groups.add(gname);
            int gx = x + 4;
            for (String gname : groups) {
                String s = gname == null ? Lang.t("react.group.all") : groupName(gname);
                int cw = font.width(RainiumScreen.body(s)) + 8;
                if (gx + cw > x + w - 4) { gx = x + 4; ty += 12; }
                boolean on = java.util.Objects.equals(gname, menuGroup), hov = in(mx, my, gx, ty, cw, 11);
                capsule(g, gx, ty, cw, 11, on ? PAPER : hov ? FIELD_HOVER : 0xFF0E1A5C);
                g.text(font, RainiumScreen.body(s), gx + 4, ty + 1, on ? INK : MUTED, false);
                hits.add(new Hit(gx, ty, cw, 11, () -> { menuGroup = gname; menuScroll = 0; }, null));
                gx += cw + 2;
            }
            ty += 14;
        }

        int lt = ty, lb = y + h - 12;
        g.fill(x + 1, lt - 2, x + w - 1, lt - 1, 0x30FFFFFF);
        g.text(font, RainiumScreen.body(fit(Lang.t(searching ? "react.menu.hint_search" : "react.menu.hint"), w - 8)), x + 4, y + h - 10, FAINT, false);
        List<React.Type> list = searching ? searchTypes() : menuTypes(menuCat, menuGroup);
        int viewH = lb - lt;
        menuScroll = Math.max(0, Math.min(menuScroll, Math.max(0, menuContent - viewH)));
        g.enableScissor(x + 1, lt, x + w - 1, lb);
        int cy = lt - menuScroll + 1, start = cy;
        int before = hits.size();
        if (list.isEmpty()) {
            g.text(font, RainiumScreen.body(Lang.t(searching ? "react.menu.no_match" : "react.menu.none")), x + 6, cy + 4, FAINT, false);
            cy += 16;
        }
        String lastHead = null;
        for (React.Type t : list) {
            // a heading where the group (or, when searching, the tab) changes
            String head = searching ? Lang.t("react.cat." + t.cat().name().toLowerCase(Locale.ROOT) + "s") + " · " + groupName(t.group())
                : menuGroup == null ? groupName(t.group()) : null;
            if (head != null && !head.equals(lastHead)) {
                String hs = head.toUpperCase(Locale.ROOT);
                g.text(font, RainiumScreen.styled(fitStrong(hs, w - 16), true, true), x + 6, cy + 2, catColor(t.cat()), false);
                g.fill(x + 6 + Math.min(w - 16, strong(hs)) + 4, cy + 6, x + w - 6, cy + 7, 0x30FFFFFF);
                cy += 13;
                lastHead = head;
            }
            String nm = Lang.t("react.node." + t.id());
            List<String> d = wrap(Lang.t("react.node." + t.id() + ".desc"), w - 16);
            if (d.size() > 2) d = List.of(d.get(0), fit(d.get(1) + "…", w - 16));
            int rh = 12 + d.size() * 9 + 2;
            boolean hov = in(mx, my, x + 2, cy, w - 4, rh) && my >= lt && my < lb;
            if (hov) g.fill(x + 2, cy, x + w - 2, cy + rh, FIELD_HOVER);
            g.fill(x + 3, cy + 2, x + 5, cy + rh - 2, catColor(t.cat()));
            g.text(font, RainiumScreen.styled(nm, false, true), x + 8, cy + 2, PAPER, false);
            if (t.self()) { String you = Lang.t("react.you_only_short"); g.text(font, RainiumScreen.body(you), x + w - 6 - font.width(RainiumScreen.body(you)), cy + 2, FAINT, false); }
            int dy = cy + 12;
            for (String line : d) { g.text(font, RainiumScreen.body(line), x + 8, dy, MUTED, false); dy += 9; }
            final React.Type tt = t;
            hits.add(new Hit(x + 2, cy, w - 4, rh, () -> addFromMenu(tt), null));
            cy += rh + 1;
        }
        g.disableScissor();
        for (int i = hits.size() - 1; i >= before; i--) { Hit hh = hits.get(i); if (hh.y() + hh.h() <= lt || hh.y() >= lb) hits.remove(i); }
        menuContent = cy - start;
        if (menuContent > viewH) {
            int barH = Math.max(14, viewH * viewH / menuContent);
            int barY = lt + (viewH - barH) * menuScroll / Math.max(1, menuContent - viewH);
            g.fill(x + w - 3, barY, x + w - 2, barY + barH, 0xD048E6FF);
        }
        hits.add(first, new Hit(x, y, w, h, () -> { }, d2 -> menuScroll = Math.max(0, Math.min(menuScroll - d2 * 14, Math.max(0, menuContent - viewH)))));
    }

    /** A small magnifying glass (the search bar's icon). */
    private void magnifier(GuiGraphicsExtractor g, int cx, int cy, int col) {
        for (int a = 0; a < 16; a++) {
            double r = a * Math.PI / 8;
            g.fill(cx - 1 + (int) Math.round(Math.cos(r) * 2.5), cy - 1 + (int) Math.round(Math.sin(r) * 2.5), cx + (int) Math.round(Math.cos(r) * 2.5), cy + (int) Math.round(Math.sin(r) * 2.5), col);
        }
        g.fill(cx + 2, cy + 2, cx + 3, cy + 3, col);
        g.fill(cx + 3, cy + 3, cx + 4, cy + 4, col);
    }

    private int[] menuRect;

    private void addFromMenu(React.Type t) {
        React.Graph gr = ensure();
        React.Node link = linkNode < 0 ? null : gr.node(linkNode);
        float x = menuCX, y = menuCY;
        if (link != null && linkBackward) x -= NW;   // dropped to the left of an input: the new node ends there
        React.Node n = gr.add(t.id(), x, y);
        if (n == null) return;
        if (link != null) {
            if (!linkBackward) gr.connect(link, n, Math.max(0, inputFor(t, link.t().out())));
            else gr.connect(n, link, linkPort);
        }
        selected = n.id;
        menuOpen = false;
        save();
    }

    // ------------------------------------------------------------------ the file menu
    private void fileMenu(GuiGraphicsExtractor g, int mx, int my, int W) {
        List<String> items = new ArrayList<>(List.of("copy", "paste", "export", "import", "clear"));
        int w = 150, x = Math.min(fileX, W - w - 4), y = BAR + 1;
        int h = items.size() * (ROW + 2) + 6;
        List<String> files = importOpen ? assets.reactionFiles() : List.of();
        if (importOpen) h += Math.max(1, files.size()) * (ROW + 1) + 6;
        g.fill(x + 3, y + 3, x + w + 3, y + h + 3, 0x80000000);
        g.fill(x, y, x + w, y + h, 0xF8050B33);
        g.outline(x, y, w, h, PAPER);
        int cy = y + 3;
        for (String it : items) {
            String label = it.equals("clear") && confirmClear ? Lang.t("react.file.clear_confirm") : Lang.t("react.file." + it);
            boolean hov = in(mx, my, x + 2, cy, w - 4, ROW + 1);
            if (hov) g.fill(x + 2, cy, x + w - 2, cy + ROW + 1, it.equals("clear") ? 0xFF5A2338 : FIELD_HOVER);
            g.text(font, RainiumScreen.body(fit(label, w - 12)), x + 7, cy + 4, it.equals("clear") ? DANGER : TEXT, false);
            hits.add(new Hit(x + 2, cy, w - 4, ROW + 1, () -> fileAction(it), null));
            cy += ROW + 2;
            if (it.equals("import") && importOpen) {
                cy += 2;
                if (files.isEmpty()) { g.text(font, RainiumScreen.body(fit(Lang.t("react.file.none"), w - 16)), x + 12, cy + 3, FAINT, false); cy += ROW + 1; }
                for (String f : files) {
                    boolean hov2 = in(mx, my, x + 8, cy, w - 12, ROW);
                    if (hov2) g.fill(x + 8, cy, x + w - 4, cy + ROW, FIELD_HOVER);
                    g.text(font, RainiumScreen.body(fit(f.replace(".reactions.json", ""), w - 22)), x + 12, cy + 3, MENU, false);
                    hits.add(new Hit(x + 8, cy, w - 12, ROW, () -> importFile(f), null));
                    cy += ROW + 1;
                }
                cy += 4;
            }
        }
        fileRect = new int[]{x, y, w, h};
        hits.add(new Hit(x, y, w, h, () -> { }, null));
        // keep clicks on the menu's items above its background
        Hit bg = hits.remove(hits.size() - 1);
        int firstItem = hits.size() - (items.size() + (importOpen ? Math.max(0, files.size()) : 0));
        hits.add(Math.max(0, firstItem), bg);
    }

    private int[] fileRect;

    private void fileAction(String it) {
        React.Graph gr = graph();
        switch (it) {
            case "copy" -> {
                if (gr == null || gr.isEmpty()) { say(Lang.t("react.msg.nothing"), WARN); break; }
                try { Minecraft.getInstance().keyboardHandler.setClipboard(React.exportText(gr, targetName())); say(Lang.t("react.msg.copied"), CYAN); }
                catch (Throwable t) { say(Lang.t("react.msg.failed", String.valueOf(t.getMessage())), DANGER); }
                fileOpen = false;
            }
            case "paste" -> {
                String clip;
                try { clip = Minecraft.getInstance().keyboardHandler.getClipboard(); } catch (Throwable t) { clip = null; }
                try {
                    if (clip == null || clip.isBlank()) throw new IllegalArgumentException(Lang.t("react.msg.clipboard_empty"));
                    merge(React.importText(clip));
                    say(Lang.t("react.msg.pasted"), CYAN);
                } catch (RuntimeException e) {
                    say(Lang.t("react.msg.not_reactions"), DANGER);
                }
                fileOpen = false;
            }
            case "export" -> {
                if (gr == null || gr.isEmpty()) { say(Lang.t("react.msg.nothing"), WARN); break; }
                try {
                    java.nio.file.Path p = assets.exportReactions(targetName(), gr);
                    say(Lang.t("react.msg.exported", "exports/" + p.getFileName()), CYAN);
                } catch (java.io.IOException e) { say(Lang.t("react.msg.failed", e.getMessage()), DANGER); }
                fileOpen = false;
            }
            case "import" -> importOpen = !importOpen;
            case "clear" -> {
                if (!confirmClear) { confirmClear = true; return; }
                if (gr != null) { gr.nodes.clear(); gr.wires.clear(); }
                selected = -1;
                save();
                confirmClear = false;
                fileOpen = false;
                say(Lang.t("react.msg.cleared"), MUTED);
            }
            default -> { }
        }
        if (!it.equals("clear")) confirmClear = false;
    }

    private void importFile(String rel) {
        try {
            merge(assets.readReactions(rel));
            say(Lang.t("react.msg.imported", rel), CYAN);
        } catch (java.io.IOException e) {
            say(Lang.t("react.msg.failed", e.getMessage()), DANGER);
        }
        fileOpen = false;
        importOpen = false;
    }

    /** Adds another graph's nodes (new ids, placed to the right of what's there) and wires. */
    void merge(React.Graph other) {
        React.Graph gr = ensure();
        float dx = 0, minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        for (React.Node n : gr.nodes) dx = Math.max(dx, n.x + NW + 40);
        for (React.Node n : other.nodes) { minX = Math.min(minX, n.x); minY = Math.min(minY, n.y); }
        if (other.nodes.isEmpty()) return;
        java.util.Map<Integer, Integer> ids = new java.util.HashMap<>();
        for (React.Node n : other.nodes) {
            React.Node c = gr.add(n.type, n.x - minX + (gr.nodes.isEmpty() ? 0 : dx), n.y - minY);
            c.p.putAll(n.p);
            ids.put(n.id, c.id);
        }
        for (React.Wire w : other.wires) {
            Integer a = ids.get(w.from()), b = ids.get(w.to());
            if (a != null && b != null) gr.connect(gr.node(a), gr.node(b), w.port());
        }
        if (!other.enabled) gr.enabled = false;
        save();
        fit();
    }

    // ------------------------------------------------------------------ examples
    private record Template(String id, boolean particlesOnly, String[] types, double[][] at, int[][] wires, Object[][] settings) {}

    private static final List<Template> TEMPLATES = List.of(
        new Template("totem_show", false, new String[]{"totem_opponent", "show"}, new double[][]{{0, 0}, {150, 0}}, new int[][]{{0, 1, 0}},
            new Object[][]{{}, {"hold", 3.0, "in", 0.25, "out", 0.6}}),
        new Template("hurt_flash", false, new String[]{"hurt", "tint"}, new double[][]{{0, 0}, {150, 0}}, new int[][]{{0, 1, 0}},
            new Object[][]{{}, {"color", "#FF2030", "in", 0.05, "hold", 0.15, "out", 0.6}}),
        new Template("combat_glow", false, new String[]{"in_combat", "glow"}, new double[][]{{0, 0}, {150, 0}}, new int[][]{{0, 1, 0}},
            new Object[][]{{}, {"in", 0.5, "out", 1.5}}),
        new Template("sprint_spin", false, new String[]{"sprinting", "spin"}, new double[][]{{0, 0}, {150, 0}}, new int[][]{{0, 1, 0}},
            new Object[][]{{}, {"speed", 360.0, "in", 0.6, "out", 1.0}}),
        new Template("hold_totem", false, new String[]{"holding", "show"}, new double[][]{{0, 0}, {150, 0}}, new int[][]{{0, 1, 0}},
            new Object[][]{{}, {"in", 0.3, "out", 0.3}}),
        new Template("sneak_fade", false, new String[]{"sneaking", "opacity"}, new double[][]{{0, 0}, {150, 0}}, new int[][]{{0, 1, 0}},
            new Object[][]{{}, {"value", 0.25, "in", 0.3, "out", 0.3}}),
        new Template("combo_grow", false, new String[]{"hit", "combo", "scale"}, new double[][]{{0, 0}, {140, 0}, {280, 0}}, new int[][]{{0, 1, 0}, {1, 2, 0}},
            new Object[][]{{}, {"count", 3.0, "within", 2.0}, {"factor", 1.4, "hold", 2.0, "in", 0.1, "out", 0.8, "in_curve", "back_out"}}),
        new Template("low_health_shake", false, new String[]{"health", "shake", "tint"}, new double[][]{{0, 0}, {150, -10}, {150, 50}}, new int[][]{{0, 1, 0}, {0, 2, 0}},
            new Object[][]{{"percent", 25.0}, {"strength", 0.03}, {"color", "#FF2030", "strength", 0.6}}),
        new Template("kill_burst", true, new String[]{"kill", "particles"}, new double[][]{{0, 0}, {150, 0}}, new int[][]{{0, 1, 0}},
            new Object[][]{{}, {"burst", 40.0, "rate", 1.0, "hold", 0.2}})
    );

    private void addTemplate(Template tp) {
        React.Graph gr = ensure();
        float dx = 0, dy = 0;
        for (React.Node n : gr.nodes) { dx = Math.max(dx, 0); dy = Math.max(dy, n.y + nodeH(n) + 24); }
        React.Node[] made = new React.Node[tp.types.length];
        for (int i = 0; i < made.length; i++) {
            made[i] = gr.add(tp.types[i], (float) tp.at[i][0] + dx, (float) tp.at[i][1] + dy + 10);
            Object[] s = tp.settings[i];
            for (int k = 0; k + 1 < s.length; k += 2) made[i].set((String) s[k], s[k + 1]);
        }
        for (int[] w : tp.wires) gr.connect(made[w[0]], made[w[1]], w[2]);
        selected = made[made.length - 1].id;
        save();
        fit();
        say(Lang.t("react.msg.example_added"), CYAN);
    }

    // ------------------------------------------------------------------ editing nodes
    private void duplicate(React.Node n) {
        React.Graph gr = ensure();
        React.Node c = gr.add(n.type, n.x + 16, n.y + 16);
        c.p.putAll(n.p);
        for (React.Wire w : new ArrayList<>(gr.wires)) if (w.to() == n.id) gr.connect(gr.node(w.from()), c, w.port());   // same inputs
        selected = c.id;
        save();
    }

    private void delete(React.Node n) {
        React.Graph gr = graph();
        if (gr == null) return;
        gr.remove(n);
        React.testOn.remove(objKey() + "#" + n.id);
        if (selected == n.id) selected = -1;
        save();
        say(Lang.t("react.msg.deleted", Lang.t("react.node." + n.type)), MUTED);
    }

    /** The node and port under a canvas point: {node id, kind (0 body, 1 header test, 2 input, 3 output), port}, or null. */
    private int[] pick(float x, float y) {
        React.Graph gr = graph();
        if (gr == null) return null;
        for (int i = gr.nodes.size() - 1; i >= 0; i--) {
            React.Node n = gr.nodes.get(i);
            React.Type t = n.t();
            if (t == null) continue;
            if (t.out() != null && Math.abs(x - outX(n)) <= 6 && Math.abs(y - outY(n)) <= 6) return new int[]{n.id, 3, 0};
            for (int p = 0; p < t.in().length; p++) if (Math.abs(x - inX(n)) <= 6 && Math.abs(y - inY(n, p)) <= 6) return new int[]{n.id, 2, p};
        }
        for (int i = gr.nodes.size() - 1; i >= 0; i--) {
            React.Node n = gr.nodes.get(i);
            if (x >= n.x && x < n.x + NW && y >= n.y && y < n.y + nodeH(n)) {
                React.Type t = n.t();
                boolean test = (t.cat() == React.Cat.EVENT && !n.type.equals("interval") || t.cat() == React.Cat.STATE)
                    && x >= n.x + NW - 13 && x < n.x + NW - 2 && y >= n.y + 1 && y < n.y + 12;
                return new int[]{n.id, test ? 1 : 0, 0};
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ input
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        lastMx = mx; lastMy = my;
        int button = buttonOf(event);
        if (editId != null) {
            if (editRect != null && in(mx, my, editRect[0], editRect[1], editRect[2], editRect[3])) return true;
            commitEdit();
        }
        if (menuOpen && (menuRect == null || !in(mx, my, menuRect[0], menuRect[1], menuRect[2], menuRect[3]))) { menuOpen = false; return true; }
        if (fileOpen && (fileRect == null || !in(mx, my, fileRect[0], fileRect[1], fileRect[2], fileRect[3]))
            && !in(mx, my, fileX, 5, 60, 14)) { fileOpen = false; importOpen = false; confirmClear = false; return true; }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!in(mx, my, h.x(), h.y(), h.w(), h.h())) continue;
            if (h.click() != null) { h.click().run(); return true; }
            if (h.x() == cvX && h.y() == cvY && h.w() == cvW) break;   // the canvas itself
            if (h.scroll() != null) continue;
        }
        if (!in(mx, my, cvX, cvY, cvW, cvH)) return false;
        float cx = toCX(mx), cy = toCY(my);
        int[] p = pick(cx, cy);
        React.Graph gr = graph();
        if (button == 1) {   // right click: delete a node, or add one here
            if (p != null && gr != null && gr.node(p[0]) != null) {
                if (p[1] == 2 && gr.input(p[0], p[2]) != null) { gr.disconnect(p[0], p[2]); save(); }
                else delete(gr.node(p[0]));
            } else openMenu((int) mx, (int) my, cx, cy, -1, 0, false);
            return true;
        }
        if (p == null) {
            if (doubleClick) { openMenu((int) mx, (int) my, cx, cy, -1, 0, false); return true; }
            selected = -1;
            drag = Drag.PAN;
            return true;
        }
        React.Node n = gr.node(p[0]);
        switch (p[1]) {
            case 1 -> {
                if (n.t().cat() == React.Cat.EVENT) testEvent(n);
                else { String k = objKey() + "#" + n.id; if (!React.testOn.remove(k)) React.testOn.add(k); }
            }
            case 2 -> {
                React.Wire w = gr.input(n.id, p[2]);
                if (w != null) {   // pick up the end of an existing wire
                    gr.disconnect(n.id, p[2]);
                    drag = Drag.WIRE; wireNode = w.from(); wireBackward = false;
                    pendingSave = true;
                } else { drag = Drag.WIRE; wireNode = n.id; wirePort = p[2]; wireBackward = true; }
            }
            case 3 -> { drag = Drag.WIRE; wireNode = n.id; wireBackward = false; }
            default -> {
                selected = n.id;
                drag = Drag.NODE; dragNode = n.id; grabX = cx - n.x; grabY = cy - n.y; dragMoved = false;
                gr.nodes.remove(n); gr.nodes.add(n);   // on top
            }
        }
        return true;
    }

    private boolean pendingSave;

    private static int buttonOf(MouseButtonEvent e) {
        try { return e.button(); } catch (Throwable t) { return 0; }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double mx = event.x(), my = event.y();
        lastMx = mx; lastMy = my;
        React.Graph gr = graph();
        switch (drag) {
            case PAN -> { panX += (float) (dx / zoom); panY += (float) (dy / zoom); }
            case NODE -> {
                React.Node n = gr == null ? null : gr.node(dragNode);
                if (n != null) {
                    float nx = toCX(mx) - grabX, ny = toCY(my) - grabY;
                    if (!RainiumScreen.down(RainiumScreen.SC_LSHIFT)) { nx = Math.round(nx / 5f) * 5f; ny = Math.round(ny / 5f) * 5f; }   // a light snap; hold Shift to place freely
                    if (nx != n.x || ny != n.y) { n.x = nx; n.y = ny; dragMoved = true; }
                }
            }
            case HANDLE -> {
                React.Node n = gr == null ? null : gr.node(dragNode);
                if (n != null && curveBox != null) {
                    double hx = Math.max(0, Math.min(1, (mx - curveBox[0]) / curveBox[2]));
                    double hy = Math.max(-0.5, Math.min(1.5, (curveBox[1] + curveBox[2] - my) / curveBox[2]));
                    double[] b = React.bezier(n.str(handleKey));
                    if (handle == 0) { b[0] = hx; b[1] = hy; } else { b[2] = hx; b[3] = hy; }
                    n.set(handleKey, React.bezierText(b[0], b[1], b[2], b[3]));
                    dragMoved = true;
                }
            }
            default -> { }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        double mx = event.x(), my = event.y();
        React.Graph gr = graph();
        Drag d = drag;
        drag = Drag.NONE;
        switch (d) {
            case NODE -> { if (dragMoved) save(); }
            case HANDLE -> { if (dragMoved) save(); }
            case WIRE -> {
                if (gr == null) break;
                React.Node from = gr.node(wireNode);
                if (from == null) break;
                boolean onCanvas = in(mx, my, cvX, cvY, cvW, cvH);
                int[] p = onCanvas ? pick(toCX(mx), toCY(my)) : null;
                if (p != null && p[0] != from.id) {
                    React.Node to = gr.node(p[0]);
                    String why;
                    if (!wireBackward) {
                        int port = p[1] == 2 ? p[2] : firstFreeInput(gr, to, from);
                        why = port < 0 ? "no_input" : gr.whyNot(from, to, port);
                        if (why == null) { gr.connect(from, to, port); save(); pendingSave = false; break; }
                    } else {
                        why = gr.whyNot(to, from, wirePort);
                        if (why == null) { gr.connect(to, from, wirePort); save(); pendingSave = false; break; }
                    }
                    say(Lang.t("react.why." + why), WARN);
                } else if (p == null && onCanvas) {   // dropped on nothing: add a node there, wired
                    openMenu((int) mx, (int) my, toCX(mx), toCY(my) - 10, from.id, wirePort, wireBackward);
                }
                if (pendingSave) { save(); pendingSave = false; }
            }
            default -> { }
        }
        return true;
    }

    /** Where a wire dropped on a node's body goes: the first input that fits (free ones first). */
    private static int firstFreeInput(React.Graph gr, React.Node to, React.Node from) {
        React.Type t = to.t();
        if (t == null || from.t() == null) return -1;
        int any = -1;
        for (int i = 0; i < t.in().length; i++) {
            if (gr.whyNot(from, to, i) != null) continue;
            if (gr.input(to.id, i) == null) return i;
            if (any < 0) any = i;
        }
        return any;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        lastMx = mx; lastMy = my;
        int d = sy > 0 ? 1 : sy < 0 ? -1 : 0;
        if (d == 0) return false;
        if (editId != null) commitEdit();
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.scroll() != null && in(mx, my, h.x(), h.y(), h.w(), h.h())) { h.scroll().accept(d); return true; }
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        boolean ctrl = RainiumScreen.down(RainiumScreen.SC_LCTRL) || RainiumScreen.down(RainiumScreen.SC_RCTRL) || RainiumScreen.down(RainiumScreen.SC_LGUI) || RainiumScreen.down(RainiumScreen.SC_RGUI);
        boolean shift = RainiumScreen.down(RainiumScreen.SC_LSHIFT) || RainiumScreen.down(RainiumScreen.SC_RSHIFT);
        if (editId != null) {
            if (k == RainiumScreen.SC_ENTER || k == RainiumScreen.SC_KP_ENTER) { commitEdit(); return true; }
            if (k == RainiumScreen.SC_ESC) { editId = null; editCommit = null; return true; }
            if (k == RainiumScreen.SC_BACKSPACE || k == RainiumScreen.SC_DELETE) {
                if (editReplace) editBuf.setLength(0); else if (editBuf.length() > 0) editBuf.setLength(editBuf.length() - 1);
                editReplace = false;
                return true;
            }
            char c = typed(k, shift, editNumeric);
            if (c != 0) {
                if (editReplace) editBuf.setLength(0);
                editReplace = false;
                if (editBuf.length() < 80) editBuf.append(c);
            }
            return true;
        }
        if (menuOpen) {   // typing filters the list
            if (k == RainiumScreen.SC_ESC) { if (filter.length() > 0) filter.setLength(0); else menuOpen = false; return true; }
            if (k == RainiumScreen.SC_BACKSPACE) { if (filter.length() > 0) filter.setLength(filter.length() - 1); return true; }
            if (k == RainiumScreen.SC_ENTER || k == RainiumScreen.SC_KP_ENTER) {
                List<React.Type> l = filter.length() > 0 ? searchTypes() : menuTypes(menuCat, menuGroup);
                if (!l.isEmpty()) addFromMenu(l.get(0));
                return true;
            }
            char c = typed(k, false, false);
            if (c != 0 && (Character.isLetterOrDigit(c) || c == ' ' || c == '-') && filter.length() < 40) { filter.append(c); menuScroll = 0; return true; }
            return true;
        }
        if (ctrl && k == RainiumScreen.SC_Z) { if (shift) parent.redo(); else parent.undo(); return true; }
        if (ctrl && k == RainiumScreen.SC_Y) { parent.redo(); return true; }
        React.Graph gr = graph();
        React.Node sel = gr == null ? null : gr.node(selected);
        if (ctrl && k == RainiumScreen.SC_A + ('d' - 'a') && sel != null) { duplicate(sel); return true; }
        if (ctrl && k == RainiumScreen.SC_A + ('c' - 'a')) { fileAction("copy"); return true; }
        if (ctrl && k == RainiumScreen.SC_V) { fileAction("paste"); return true; }
        if ((k == RainiumScreen.SC_DELETE || k == RainiumScreen.SC_BACKSPACE) && sel != null) { delete(sel); return true; }
        if (!ctrl && k == RainiumScreen.SC_A + ('f' - 'a')) { fit(); return true; }
        if (!ctrl && (k == RainiumScreen.SC_A || k == RainiumScreen.SC_SPACE || k == RainiumScreen.SC_TAB)) {
            boolean over = in(lastMx, lastMy, cvX, cvY, cvW, cvH);
            openMenu(over ? (int) lastMx : cvX + cvW / 2 - MENU_W / 2, over ? (int) lastMy : cvY + 20, over ? toCX(lastMx) : centreX(), over ? toCY(lastMy) : centreY(), -1, 0, false);
            return true;
        }
        if (k == RainiumScreen.SC_ESC) {
            if (fileOpen) { fileOpen = false; return true; }
            if (selected >= 0) { selected = -1; return true; }
            back();
            return true;
        }
        return super.keyPressed(event);
    }

    /** The character a key types (letters, digits, and . - _ : / for ids), or 0. */
    private static char typed(int k, boolean shift, boolean numeric) {
        char d = RainiumScreen.digit(k);
        if (d != 0) return d;
        if (k == RainiumScreen.SC_PERIOD || k == RainiumScreen.SC_KP_PERIOD || k == RainiumScreen.SC_COMMA) return '.';
        if (k == RainiumScreen.SC_MINUS || k == RainiumScreen.SC_KP_MINUS) return shift && !numeric ? '_' : '-';
        if (numeric) return 0;
        if (k >= RainiumScreen.SC_A && k <= RainiumScreen.SC_Z) return (char) ((shift ? 'A' : 'a') + (k - RainiumScreen.SC_A));
        if (k == RainiumScreen.SC_SPACE) return ' ';
        if (k == RainiumScreen.SC_SEMICOLON) return ':';
        if (k == RainiumScreen.SC_SLASH) return '/';
        return 0;
    }

    @Override
    public boolean isPauseScreen() { return false; }   // so you can watch reactions happen in the world behind

    @Override
    public void onClose() { back(); }

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

    private static Double parse(String s) {
        try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }

    // ------------------------------------------------------------------ messages
    private void say(String s, int color) {
        toast = s;
        toastColor = color;
        toastUntil = System.currentTimeMillis() + 2600;
    }

    private void toast(GuiGraphicsExtractor g) {
        if (System.currentTimeMillis() > toastUntil || toast.isEmpty()) return;
        String s = fit(toast, cvW - 30);
        int w = font.width(RainiumScreen.body(s)) + 14, x = cvX + (cvW - w) / 2, y = cvY + cvH - 24;
        g.fill(x, y, x + w, y + 15, 0xF4050B33);
        g.fill(x, y, x + 2, y + 15, toastColor);
        g.text(font, RainiumScreen.body(s), x + 8, y + 4, toastColor, false);
    }

    private void tooltip(GuiGraphicsExtractor g, int mx, int my, String s) {
        List<String> lines = wrap(s, 160);
        int w = 8, h = lines.size() * 10 + 6;
        for (String l : lines) w = Math.max(w, font.width(RainiumScreen.body(l)) + 10);
        int x = Math.min(mx + 10, width - w - 4), y = Math.min(my + 12, height - h - 4);
        g.fill(x, y, x + w, y + h, 0xF4050B33);
        g.outline(x, y, w, h, CYAN);
        int yy = y + 4;
        for (String l : lines) { g.text(font, RainiumScreen.body(l), x + 5, yy, TEXT, false); yy += 10; }
    }

    // ------------------------------------------------------------------ drawing helpers
    private int strong(String s) { return font.width(RainiumScreen.styled(s, true, true)); }

    private String fitStrong(String s, int w) {
        if (strong(s) <= w) return s;
        int n = s.length();
        while (n > 0 && strong(s.substring(0, n) + "…") > w) n--;
        return s.substring(0, n) + "…";
    }

    private String fit(String s, int w) {
        if (s == null) return "";
        if (font.width(RainiumScreen.body(s)) <= w) return s;
        int n = s.length();
        while (n > 0 && font.width(RainiumScreen.body(s.substring(0, n) + "…")) > w) n--;
        return s.substring(0, n) + "…";
    }

    private String tail(String s, int w) {
        if (font.width(RainiumScreen.body(s)) <= w) return s;
        int a = 0;
        while (a < s.length() && font.width(RainiumScreen.body("…" + s.substring(a))) > w) a++;
        return "…" + s.substring(a);
    }

    private List<String> wrap(String text, int w) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String tryLine = line.length() == 0 ? word : line + " " + word;
                if (font.width(RainiumScreen.body(tryLine)) > w && line.length() > 0) { out.add(line.toString()); line.setLength(0); line.append(word); }
                else { line.setLength(0); line.append(tryLine); }
            }
            out.add(line.toString());
        }
        return out;
    }

    private int wrapped(GuiGraphicsExtractor g, int x, int y, int w, String text, int color) {
        for (String line : wrap(text, w)) { g.text(font, RainiumScreen.body(line), x, y, color, false); y += 10; }
        return y;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    private static float guiScale() {
        try { return Math.max(1f, (float) Minecraft.getInstance().getWindow().getGuiScale()); } catch (Throwable t) { return 1f; }
    }

    private interface Span { float[] at(float y); }

    private void fillShape(GuiGraphicsExtractor g, float top, float bottom, Span span, int color) {
        float s = guiScale() * (drawingCanvas ? zoom : 1f);
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

    private boolean drawingCanvas;

    private void slant(GuiGraphicsExtractor g, int x, int y, int w, int h, int cut, int color) {
        fillShape(g, y, y + h, yy -> new float[]{x, x + w - cut * (yy - y) / h}, color);
    }

    private void capsule(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        float r = h / 2f, cy = y + r;
        fillShape(g, y, y + h, yy -> {
            float dy = yy - cy, d = r * r - dy * dy;
            if (d < 0) return null;
            float dx = (float) Math.sqrt(d);
            return new float[]{x + r - dx, x + w - r + dx};
        }, color);
    }

    private void triangle(GuiGraphicsExtractor g, float ax, float ay, float bx, float by, float cx, float cy, int color) {
        fillShape(g, Math.min(ay, Math.min(by, cy)), Math.max(ay, Math.max(by, cy)), yy -> triSpan(ax, ay, bx, by, cx, cy, yy), color);
    }

    private static float[] triSpan(float ax, float ay, float bx, float by, float cx, float cy, float y) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        float[][] e = {{ax, ay, bx, by}, {bx, by, cx, cy}, {cx, cy, ax, ay}};
        for (float[] s : e) {
            float y0 = s[1], y1 = s[3];
            if ((y < Math.min(y0, y1)) || (y > Math.max(y0, y1)) || y0 == y1) continue;
            float t = (y - y0) / (y1 - y0);
            float x = s[0] + (s[2] - s[0]) * t;
            lo = Math.min(lo, x); hi = Math.max(hi, x);
        }
        return lo > hi ? null : new float[]{lo, hi};
    }
}
