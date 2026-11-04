package io.github.raincandyfx.rainium;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Timed lyrics in the LRC format: "[mm:ss.xx]a line", several time tags on one line, an
 * "[offset:+/-ms]" tag, and word times written inside a line as "<mm:ss.xx>word" (enhanced LRC).
 * Lines without word times get them by spreading the line's time over its words by length.
 * Pure (no game classes), so the tests can run it.
 */
final class LyricsLrc {
    record Word(String text, double t, double end) {}
    record Line(String text, double t, double end, List<Word> words) {}

    final List<Line> lines;
    final boolean wordTimed;   // the file had its own word times

    private LyricsLrc(List<Line> lines, boolean wordTimed) { this.lines = lines; this.wordTimed = wordTimed; }

    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern WORD_TIME = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern META = Pattern.compile("^\\[[a-zA-Z#]+:.*]$");

    static double seconds(Matcher m) {
        double frac = 0;
        if (m.group(3) != null) {
            String f = m.group(3);
            frac = Integer.parseInt(f) / Math.pow(10, f.length());
        }
        return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) + frac;
    }

    /** Parses LRC text. `songLength` (seconds, 0 if unknown) caps the last line. Returns null when nothing is timed. */
    static LyricsLrc parse(String text, double songLength) {
        if (text == null) return null;
        double offset = 0;
        Matcher om = OFFSET.matcher(text);
        if (om.find()) offset = Integer.parseInt(om.group(1)) / 1000.0;   // positive: lyrics come sooner
        record Raw(double t, String body) {}
        List<Raw> raw = new ArrayList<>();
        for (String row : text.replace("\r", "").split("\n")) {
            String s = row.strip();
            if (s.isEmpty()) continue;
            List<Double> times = new ArrayList<>();
            Matcher m = TIME.matcher(s);
            int pos = 0;
            while (m.find() && m.start() == pos) { times.add(seconds(m) - offset); pos = m.end(); }
            if (times.isEmpty()) continue;                  // untimed rows and metadata ([ar:...]) are skipped
            String body = s.substring(pos).strip();
            if (META.matcher(s).matches() && times.isEmpty()) continue;
            for (double t : times) raw.add(new Raw(Math.max(0, t), body));
        }
        if (raw.isEmpty()) return null;
        raw.sort((a, b) -> Double.compare(a.t, b.t));
        List<Line> out = new ArrayList<>();
        boolean anyWordTimes = false;
        for (int i = 0; i < raw.size(); i++) {
            Raw r = raw.get(i);
            String plain = WORD_TIME.matcher(r.body).replaceAll("").replaceAll("\\s+", " ").strip();
            if (plain.isEmpty()) continue;                  // an empty timed row only ends the line before it
            double next = i + 1 < raw.size() ? raw.get(i + 1).t : (songLength > r.t ? songLength : r.t + 4 + plain.length() * 0.08);
            double end = Math.max(r.t + 0.3, next);
            List<Word> words = wordTimes(r.body, r.t, end, offset);
            if (words != null) anyWordTimes = true;
            else words = spread(plain, r.t, end);
            out.add(new Line(plain, r.t, end, words));
        }
        return out.isEmpty() ? null : new LyricsLrc(List.copyOf(out), anyWordTimes);
    }

    /** "<00:01.20>we <00:01.50>built" → words with their own times; null when the line has none. */
    private static List<Word> wordTimes(String body, double lineT, double lineEnd, double offset) {
        Matcher m = WORD_TIME.matcher(body);
        if (!m.find()) return null;
        m.reset();
        List<double[]> marks = new ArrayList<>();   // {time, textStart, tagStart}
        while (m.find()) marks.add(new double[]{seconds(m) - offset, m.end(), m.start()});
        List<Word> out = new ArrayList<>();
        String lead = body.substring(0, (int) marks.get(0)[2]).strip();
        if (!lead.isEmpty()) for (String w : lead.split("\\s+")) out.add(new Word(w, lineT, marks.get(0)[0]));
        for (int i = 0; i < marks.size(); i++) {
            int from = (int) marks.get(i)[1], to = i + 1 < marks.size() ? (int) marks.get(i + 1)[2] : body.length();
            String chunk = body.substring(from, to).strip();
            if (chunk.isEmpty()) continue;
            double t0 = Math.max(lineT, marks.get(i)[0]);
            double t1 = i + 1 < marks.size() ? Math.max(t0, marks.get(i + 1)[0]) : lineEnd;
            String[] parts = chunk.split("\\s+");
            for (int k = 0; k < parts.length; k++)
                out.add(new Word(parts[k], t0 + (t1 - t0) * k / parts.length, t0 + (t1 - t0) * (k + 1) / parts.length));
        }
        return out.isEmpty() ? null : out;
    }

    /** No word times: share the sung part of the line among its words by length (a long gap after a line isn't sung). */
    static List<Word> spread(String plain, double t, double end) {
        String[] ws = plain.split(" ");
        int chars = 0;
        for (String w : ws) chars += w.length() + 1;
        double sung = Math.min((end - t) * 0.88, 0.6 + chars * 0.085);
        List<Word> out = new ArrayList<>(ws.length);
        int acc = 0;
        for (String w : ws) {
            double a = t + sung * acc / chars;
            acc += w.length() + 1;
            out.add(new Word(w, a, t + sung * acc / chars));
        }
        return out;
    }

    /** Index of the line showing at time t (the last one that has started), or -1 before the first. */
    int lineAt(double t) {
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).t <= t) { ans = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return ans;
    }

    /** The lyrics back as plain LRC, one timed line each (what JIZURA reads). */
    String toLrc() {
        StringBuilder b = new StringBuilder();
        for (Line l : lines) b.append(stamp(l.t)).append(l.text).append('\n');
        return b.toString();
    }

    static String stamp(double t) {
        int cs = (int) Math.round(Math.max(0, t) * 100);
        return String.format(Locale.ROOT, "[%02d:%02d.%02d]", cs / 6000, (cs / 100) % 60, cs % 100);
    }

    /** Original words, written for Rainium, for trying the lyrics with no music playing. */
    static final String DEMO = """
        [00:00.00]we built a fire out of falling stars
        [00:03.40]and every spark was singing back to us
        [00:06.80]hold on, the night is turning gold
        [00:10.20]we are not alone, not alone
        [00:13.40]
        """;
    static final String DEMO_TITLE = "Fire of Falling Stars", DEMO_ARTIST = "Rainium Demo";
    static final double DEMO_LENGTH = 15.0;
}
