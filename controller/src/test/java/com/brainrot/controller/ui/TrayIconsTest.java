package com.brainrot.controller.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrayIconsTest {
    private static int pixel(BufferedImage image, double x, double y) {
        double u = image.getWidth() / 22.0;
        return image.getRGB((int) (x * u), (int) (y * u));
    }

    @Test
    void theImageHasTheRequestedSize() {
        BufferedImage image = TrayIcons.padlock(true, 44);

        assertEquals(44, image.getWidth());
        assertEquals(44, image.getHeight());
    }

    @Test
    void lockedIsRedAndUnlockedIsGreen() {
        // Just inside the body, beside the keyhole, so we sample the body colour itself.
        Color locked = new Color(pixel(TrayIcons.padlock(true, 88), 6, 17), true);
        Color unlocked = new Color(pixel(TrayIcons.padlock(false, 88), 6, 17), true);

        assertTrue(locked.getRed() > 180 && locked.getGreen() < 120, "locked should be red: " + locked);
        assertTrue(unlocked.getGreen() > 140 && unlocked.getRed() < 100, "unlocked should be green: " + unlocked);
    }

    @Test
    void theTwoStatesDrawDifferentShackles() {
        // Above the body on the right: closed shackle has paint there, the open one has swung away.
        int closed = pixel(TrayIcons.padlock(true, 88), 15, 8) >>> 24;
        int open = pixel(TrayIcons.padlock(false, 88), 15, 8) >>> 24;

        assertTrue(closed > 200, "closed shackle should cover this spot");
        assertNotEquals(closed, open);
    }

    @Test
    void theCornersStayTransparent() {
        for (boolean locked : new boolean[] {true, false}) {
            BufferedImage image = TrayIcons.padlock(locked, 44);
            assertEquals(0, image.getRGB(0, 0) >>> 24);
            assertEquals(0, image.getRGB(43, 43) >>> 24);
        }
    }
}
