package io.github.raincandyfx.rainium;

import java.util.List;

/**
 * Offline checks for Scatter's typed lines (no game needed). From the repo root:
 *   javac -d /tmp/st src/main/java/io/github/raincandyfx/rainium/{LyricsWords,LyricsLrc,LyricsWorld}.java tools/lyrictest/ScatterTest.java
 *   java -cp /tmp/st io.github.raincandyfx.rainium.ScatterTest
 */
public class ScatterTest {
    static int fails = 0;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL " + what); } }

    public static void main(String[] a) {
        // wrapping
        List<String> rows = LyricsWords.wrap("Come on,  baby, let's have fun", 18);
        check(rows.equals(List.of("Come on, baby,", "let's have fun")), "wrap: " + rows);
        check(String.join(" ", rows).equals(LyricsWords.clean("Come on,  baby, let's have fun")), "rows join back to the cleaned line");
        check(LyricsWords.wrap("Supercalifragilisticexpialidocious yes", 18).equals(List.of("Supercalifragilisticexpialidocious", "yes")), "a long word gets its own row");
        check(LyricsWords.wrap("   ", 18).isEmpty(), "blank line, no rows");
        for (String s : new String[]{"Hop inside of my Mercedes", "Take my clothes off, tell me I'm hot", "a b c d e f g h i j k l m n o p q r s t"})
            for (String r : LyricsWords.wrap(s, 18)) check(r.length() <= 18, "rows fit: " + r);

        // typing with word times
        var line = new LyricsLrc.Line("Look me in my perfect face", 10.0, 14.0, List.of(
            new LyricsLrc.Word("Look", 10.0, 10.4), new LyricsLrc.Word("me", 10.4, 10.6), new LyricsLrc.Word("in", 10.6, 10.8),
            new LyricsLrc.Word("my", 10.8, 11.0), new LyricsLrc.Word("perfect", 11.2, 11.8), new LyricsLrc.Word("face", 12.0, 13.0)));
        check(LyricsWords.typed(line, 9.0) == 0, "nothing before the line");
        int early = LyricsWords.typed(line, 10.05), look = LyricsWords.typed(line, 10.39);
        check(early > 0 && early < 4, "the first word is typed a letter at a time: " + early);
        check(look == 4, "the first word is whole by its end: " + look);
        check(LyricsWords.typed(line, 11.1) == "Look me in my".length(), "waits for the next word: " + LyricsWords.typed(line, 11.1));
        int prev = -1; boolean grows = true;
        for (double t = 9.5; t < 14; t += 0.01) { int n = LyricsWords.typed(line, t); if (n < prev) grows = false; prev = n; }
        check(grows && prev == line.text().length(), "typing only grows, and ends with the whole line");
        // typing without word times
        var plain = new LyricsLrc.Line("No word times here", 5.0, 9.0, List.of());
        check(LyricsWords.typed(plain, 5.0) == 0 && LyricsWords.typed(plain, 5.5) > 0 && LyricsWords.typed(plain, 8.9) == plain.text().length(), "plain lines are typed evenly");

        // typed text onto rows
        List<String> r2 = LyricsWords.wrap("Look me in my perfect face", 18);
        check(r2.equals(List.of("Look me in my", "perfect face")), "rows " + r2);
        check(LyricsWords.typedRows(r2, 4).equals(List.of("Look", "")), "typed rows 4");
        check(LyricsWords.typedRows(r2, 13).equals(List.of("Look me in my", "")), "typed rows 13");
        check(LyricsWords.typedRows(r2, 17).equals(List.of("Look me in my", "per")), "typed rows 17: " + LyricsWords.typedRows(r2, 17));
        check(LyricsWords.typedRows(r2, 99).equals(r2), "all typed");

        // colours
        check(LyricsWords.colorOf("#ff8800", 1) == 0xFF8800 && LyricsWords.colorOf("", 0xFFF2D8) == 0xFFF2D8, "colour");
        check(LyricsWords.tint(LyricsWords.COLOR_PEACH, "", 0, 0) == LyricsWords.PEACH && LyricsWords.tint(LyricsWords.COLOR_WHITE, "#123456", 3, 9) == 0xFFFFFF, "peach and white");
        check(LyricsWords.tint(LyricsWords.COLOR_CUSTOM, "#ff0000", 0, 0) == 0xFF0000 && LyricsWords.tint(LyricsWords.COLOR_CUSTOM, "", 0, 0) == LyricsWords.PEACH, "custom, peach when unset");
        check(LyricsWords.tint(LyricsWords.COLOR_RAINBOW, "", 0, 0) != LyricsWords.tint(LyricsWords.COLOR_RAINBOW, "", 0, 4), "rainbow moves with time");
        check(LyricsWords.tint(LyricsWords.COLOR_PER_LINE, "", 1, 0) != LyricsWords.tint(LyricsWords.COLOR_PER_LINE, "", 2, 0) && LyricsWords.tint(LyricsWords.COLOR_PER_LINE, "", 2, 0) == LyricsWords.tint(LyricsWords.COLOR_PER_LINE, "", 2, 50), "per line: differs by line, steady in time");
        for (int m = 0; m < LyricsWords.COLOR_KEYS.length; m++) { int c = LyricsWords.tint(m, "", 5, 3.3); check((c & 0xFF000000) == 0 && Math.max(c >> 16 & 255, Math.max(c >> 8 & 255, c & 255)) > 200, "colour " + m + " is light and has no alpha bits"); }
        check(LyricsWords.typedRows(r2, Integer.MAX_VALUE).equals(r2), "typing off shows the whole line");
        // the Words style's helpers are back
        var w = new LyricsLrc.Word("hey", 10, 10.1);
        check(Math.abs(LyricsWords.fadeAt(w) - 11.2) < 1e-9, "word life");
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(LyricsWords.ROW_W, LyricsWords.ROW_H, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var gw = img.createGraphics(); float[] ink = LyricsWords.drawWord(gw, "Hi", 0, 0xFFFFFF); gw.dispose();
        check(ink[1] > ink[0], "word picture");
        // placement maths
        double[] c0 = LyricsWords.column(0, 14, 8), c1 = LyricsWords.column(1, 14, 8);
        check(c0[0] == -14 && c1[1] < 0 && c0[1] > 0, "columns");
        var spots = List.of(new LyricsWords.Spot(1, 3, 0, 0, 5, 3));
        check(LyricsWords.gap(0, 0, 5, 3, spots, 1.5, 2.5) < 0 && LyricsWords.gap(40, 0, 5, 3, spots, 1.5, 2.5) == 6.0, "gap");
        double[] eye = {0, 10, 0};
        double[] pulled = LyricsWorld.inFront((x, y, z) -> x > 5 && x < 6, eye, new double[]{9, 10, 0});
        check(pulled[0] < 5, "walls still pull lines in front");

        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
