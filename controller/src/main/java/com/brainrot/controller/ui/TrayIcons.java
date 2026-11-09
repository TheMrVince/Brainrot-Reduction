package com.brainrot.controller.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * The padlock drawn in code, so there are no image files to ship. Red and closed means locked;
 * green with the shackle swung open means unlocked. The colours stay readable on light and dark
 * menu bars.
 */
public final class TrayIcons {
    private static final Color LOCKED = new Color(0xD9, 0x53, 0x4F);
    private static final Color UNLOCKED = new Color(0x3F, 0xA3, 0x4D);
    private static final double GRID = 22.0;  // the drawing is laid out on a 22 x 22 grid

    private TrayIcons() {}

    public static BufferedImage padlock(boolean locked, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            double u = size / GRID;
            Color colour = locked ? LOCKED : UNLOCKED;
            g.translate(0, 1.4 * u);  // leave room above for the open shackle

            g.setColor(colour);
            g.setStroke(new BasicStroke((float) (2.6 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (!locked) g.rotate(Math.toRadians(-32), 7 * u, 10 * u);  // swing the free end up and away
            g.draw(new Arc2D.Double(7 * u, 2.5 * u, 8 * u, 9 * u, 0, 180, Arc2D.OPEN));
            g.draw(new Line2D.Double(7 * u, 7 * u, 7 * u, 10 * u));
            g.draw(new Line2D.Double(15 * u, 7 * u, 15 * u, 10 * u));
            if (!locked) g.rotate(Math.toRadians(32), 7 * u, 10 * u);  // back to upright for the body

            g.fill(new RoundRectangle2D.Double(4 * u, 10 * u, 14 * u, 10 * u, 3 * u, 3 * u));

            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(9.6 * u, 12.6 * u, 2.8 * u, 2.8 * u));
            g.fill(new RoundRectangle2D.Double(10.4 * u, 14.2 * u, 1.2 * u, 3.2 * u, 0.6 * u, 0.6 * u));
        } finally {
            g.dispose();
        }
        return image;
    }
}
