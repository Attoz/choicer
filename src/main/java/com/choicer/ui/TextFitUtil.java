package com.choicer.ui;

import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared text fitting helpers for compact UI labels and tooltips.
 */
public final class TextFitUtil {
    private static final String ELLIPSIS = "...";

    private TextFitUtil() {
    }

    /**
     * Fit text to a single line, appending ellipsis if needed.
     */
    public static String elideToWidth(String text, FontMetrics fm, int maxWidth) {
        if (fm == null || maxWidth <= 0) {
            return ELLIPSIS;
        }

        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (fm.stringWidth(trimmed) <= maxWidth) {
            return trimmed;
        }

        int ellipsisWidth = fm.stringWidth(ELLIPSIS);
        if (ellipsisWidth >= maxWidth) {
            return ELLIPSIS;
        }

        int end = trimmed.length();
        while (end > 0 && fm.stringWidth(trimmed.substring(0, end)) + ellipsisWidth > maxWidth) {
            end--;
        }
        if (end <= 0) {
            return ELLIPSIS;
        }
        return trimmed.substring(0, end) + ELLIPSIS;
    }

    /**
     * Wrap text without truncation. Falls back to character splitting for long
     * unbroken tokens.
     */
    public static List<String> wrapToWidth(String text, FontMetrics fm, int maxWidth) {
        if (fm == null || maxWidth <= 0) {
            return Collections.emptyList();
        }

        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> lines = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        String[] words = trimmed.split("\\s+");

        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }

            if (currentLine.length() == 0) {
                appendWordWithFallback(lines, currentLine, word, fm, maxWidth);
                continue;
            }

            String candidate = currentLine + " " + word;
            if (fm.stringWidth(candidate) <= maxWidth) {
                currentLine.append(' ').append(word);
                continue;
            }

            lines.add(currentLine.toString());
            currentLine.setLength(0);
            appendWordWithFallback(lines, currentLine, word, fm, maxWidth);
        }

        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }

        return lines;
    }

    private static void appendWordWithFallback(
            List<String> lines,
            StringBuilder currentLine,
            String word,
            FontMetrics fm,
            int maxWidth) {
        if (fm.stringWidth(word) <= maxWidth) {
            currentLine.append(word);
            return;
        }

        String remaining = word;
        while (!remaining.isEmpty()) {
            int fitLength = maxPrefixThatFits(remaining, fm, maxWidth);
            if (fitLength <= 0) {
                fitLength = 1;
            }

            String chunk = remaining.substring(0, fitLength);
            remaining = remaining.substring(fitLength);

            if (remaining.isEmpty()) {
                currentLine.append(chunk);
            } else {
                lines.add(chunk);
            }
        }
    }

    private static int maxPrefixThatFits(String text, FontMetrics fm, int maxWidth) {
        int low = 1;
        int high = text.length();
        int best = 0;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            int width = fm.stringWidth(text.substring(0, mid));
            if (width <= maxWidth) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        return best;
    }
}
