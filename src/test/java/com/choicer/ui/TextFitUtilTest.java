package com.choicer.ui;

import org.junit.Before;
import org.junit.Test;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TextFitUtilTest {
    private FontMetrics fontMetrics;

    @Before
    public void setUp() {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            fontMetrics = g.getFontMetrics();
        } finally {
            g.dispose();
        }
    }

    @Test
    public void elideToWidthReturnsOriginalWhenTextFits() {
        String text = "Abyssal whip";
        int width = fontMetrics.stringWidth(text) + 4;

        String result = TextFitUtil.elideToWidth(text, fontMetrics, width);

        assertEquals(text, result);
    }

    @Test
    public void elideToWidthAddsEllipsisWhenTextDoesNotFit() {
        String text = "Dragon claws ornament kit";
        int width = fontMetrics.stringWidth("Dragon claws");

        String result = TextFitUtil.elideToWidth(text, fontMetrics, width);

        assertTrue(result.endsWith("..."));
        assertTrue(fontMetrics.stringWidth(result) <= width);
    }

    @Test
    public void elideToWidthReturnsEllipsisWhenWidthIsTiny() {
        int width = fontMetrics.stringWidth("..");

        String result = TextFitUtil.elideToWidth("Rune platebody", fontMetrics, width);

        assertEquals("...", result);
    }

    @Test
    public void wrapToWidthWrapsWordsAcrossLines() {
        String text = "Dragon claws ornament kit";
        int width = fontMetrics.stringWidth("Dragon claws");

        List<String> lines = TextFitUtil.wrapToWidth(text, fontMetrics, width);

        assertTrue(lines.size() > 1);
        for (String line : lines) {
            assertTrue(fontMetrics.stringWidth(line) <= width);
        }
        assertEquals(text, String.join(" ", lines));
    }

    @Test
    public void wrapToWidthFallsBackToCharacterSplittingForLongToken() {
        String token = "Supercalifragilisticexpialidocious";
        int width = fontMetrics.stringWidth("Supercali");

        List<String> lines = TextFitUtil.wrapToWidth(token, fontMetrics, width);

        assertTrue(lines.size() > 1);
        for (String line : lines) {
            assertFalse(line.isEmpty());
            assertTrue(fontMetrics.stringWidth(line) <= width);
        }
        assertEquals(token, String.join("", lines));
    }
}
