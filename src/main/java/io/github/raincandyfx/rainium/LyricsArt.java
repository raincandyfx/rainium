package io.github.raincandyfx.rainium;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/** The Simple look, drawn into an image: the sung line (words fill in as they're sung) and the next line small under it. Pure, so the tests can run it. */
final class LyricsArt {
    private LyricsArt() {}

    static BufferedImage draw(LyricsLrc.Line line, int sung, LyricsLrc.Line next, int W, int H) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        FontRenderContext frc = g.getFontRenderContext();
        if (line != null) {
            Font f = fit(line.text(), new Font(Font.SANS_SERIF, Font.BOLD, 76), W - 48, frc);
            float space = (float) f.getStringBounds(" ", frc).getWidth();
            float total = 0;
            for (LyricsLrc.Word w : line.words()) total += (float) f.getStringBounds(w.text(), frc).getWidth() + space;
            total -= space;
            float x = (W - total) / 2, y = 132;
            for (int i = 0; i < line.words().size(); i++) {
                String w = line.words().get(i).text();
                Shape s = new TextLayout(w, f, frc).getOutline(AffineTransform.getTranslateInstance(x, y));
                g.setColor(new Color(0, 0, 0, 150));
                g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(s);
                g.setColor(i < sung ? Color.WHITE : new Color(255, 255, 255, 120));
                g.fill(s);
                x += (float) f.getStringBounds(w, frc).getWidth() + space;
            }
        }
        if (next != null) {
            Font f = fit(next.text(), new Font(Font.SANS_SERIF, Font.BOLD, 38), W - 120, frc);
            float tw = (float) f.getStringBounds(next.text(), frc).getWidth();
            Shape s = new TextLayout(next.text(), f, frc).getOutline(AffineTransform.getTranslateInstance((W - tw) / 2, 212));
            g.setColor(new Color(0, 0, 0, 110));
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(s);
            g.setColor(new Color(255, 255, 255, 105));
            g.fill(s);
        }
        g.dispose();
        return img;
    }

    private static Font fit(String text, Font f, int maxW, FontRenderContext frc) {
        double w = f.getStringBounds(text, frc).getWidth();
        return w <= maxW ? f : f.deriveFont((float) (f.getSize2D() * maxW / w));
    }
}
