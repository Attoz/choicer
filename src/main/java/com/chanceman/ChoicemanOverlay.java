package com.chanceman;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Alternate overlay used when Choiceman mode is enabled.
 * Presents the roll strip vertically with a configurable number of visible slots.
 */
@Singleton
@Slf4j
public class ChoicemanOverlay extends Overlay implements RollOverlay
{
    private static final float SNAP_NEXT_THRESHOLD = 0.55f;
    private static final long SNAP_DURATION_MS = 350L;
    private static final long HIGHLIGHT_DURATION_MS = 3000L;
    private static final float INITIAL_SPEED = 820f;
    private static final float MIN_SPEED = 95f;
    private static final float MAX_DT = 0.05f;

    private static final int ICON_W = 32;
    private static final int ICON_H = 32;
    private static final int SPACING = 5;
    private static final float STEP = ICON_H + SPACING;

    private static final int OFFSET_TOP = 20;
    private static final int BOX_SHIFT_X = 120;
    private static final int FRAME_CONTENT_INSET = 4;
    private static final int SLOT_PADDING_X = 18;
    private static final int SLOT_PADDING_Y = 12;
    private static final int SELECTION_TOP_MARGIN = 45;
    private static final Color SLOT_BORDER = new Color(201, 168, 92, 230);
    private static final Color SLOT_FILL_TOP = new Color(22, 22, 22, 235);
    private static final Color SLOT_FILL_BOTTOM = new Color(10, 10, 10, 235);
    private static final Color LABEL_COLOR = new Color(247, 247, 247);
    private static final Font LABEL_FONT = new Font("SansSerif", Font.BOLD, 11);

    private static final int ICON_COUNT = 3;
    private static final int DRAW_COUNT = ICON_COUNT + 1;
    private static final int COLUMN_SPACING = 16;

    private final Client client;
    private final ItemManager itemManager;

    private final List<List<Integer>> rollingColumns = Collections.synchronizedList(new ArrayList<>());
    private final float[] columnOffsetAdjust = new float[5];
    private final float[] columnSpeedScale = new float[5];
    private final Random spinRandom = new Random();

    @Inject private AudioPlayer audioPlayer;
    @Inject private ChanceManConfig config;

    private final BufferedImage iconFrameImage =
            ImageUtil.loadImageResource(getClass(), "/com/chanceman/icon_slot.png");

    private volatile boolean isAnimating = false;
    private long rollDurationMs;
    private long rollStartMs = 0L;

    private float rollOffset = 0f;
    private float currentSpeed = INITIAL_SPEED;
    private Supplier<Integer> randomLockedItemSupplier;
    private long lastUpdateNanos = 0L;

    private boolean isSnapping = false;
    private long snapStartMs = 0L;
    private float snapBase;
    private float snapResidualStart;
    private float snapTarget;
    private int winnerDelta = 0;
    private int columnCount = 2;
    private final List<Rectangle> columnHitboxes = new ArrayList<>();
    private volatile boolean selectionPending = false;

    private List<Integer> currentOptions = Collections.emptyList();

    public void setChoicemanOptions(List<Integer> options)
    {
        if (options == null)
        {
            currentOptions = Collections.emptyList();
        }
        else
        {
            currentOptions = new ArrayList<>(options);
        }
        columnCount = determineColumnCount();
        for (int i = 0; i < columnOffsetAdjust.length; i++)
        {
            columnOffsetAdjust[i] = spinRandom.nextFloat() * STEP;
            columnSpeedScale[i] = 0.75f + spinRandom.nextFloat() * 0.45f; // 0.75x – 1.2x
        }
    }

    private int determineColumnCount()
    {
        if (currentOptions == null || currentOptions.isEmpty())
        {
            return 2;
        }
        int capped = Math.max(2, Math.min(5, currentOptions.size()));
        if (currentOptions.size() > capped)
        {
            currentOptions = new ArrayList<>(currentOptions.subList(0, capped));
        }
        return capped;
    }

    public void setSelectionPending(boolean pending)
    {
        this.selectionPending = pending;
        if (pending)
        {
            syncSelectionOptionsWithColumns();
        }
        else
        {
            synchronized (columnHitboxes)
            {
                columnHitboxes.clear();
            }
        }
    }

    public boolean isSelectionPending()
    {
        return selectionPending;
    }

    public Integer getOptionAt(int x, int y)
    {
        synchronized (columnHitboxes)
        {
            for (int i = 0; i < columnHitboxes.size(); i++)
            {
                Rectangle rect = columnHitboxes.get(i);
                if (rect.contains(x, y))
                {
                    if (i < currentOptions.size())
                    {
                        return currentOptions.get(i);
                    }
                }
            }
        }
        return null;
    }

    private void syncSelectionOptionsWithColumns()
    {
        List<Integer> snapped = captureSnappedItems();
        if (!snapped.isEmpty())
        {
            currentOptions = snapped;
        }
    }

    private List<Integer> captureSnappedItems()
    {
        List<Integer> snapped = new ArrayList<>();
        synchronized (rollingColumns)
        {
            if (rollingColumns.isEmpty())
            {
                return snapped;
            }
            final int centerIndex = ICON_COUNT / 2;
            final int columns = Math.min(columnCount, rollingColumns.size());
            for (int col = 0; col < columns; col++)
            {
                List<Integer> column = rollingColumns.get(col);
                if (column.isEmpty())
                {
                    snapped.add(0);
                    continue;
                }
                final int winnerIndex = Math.min(centerIndex + winnerDelta, column.size() - 1);
                if (winnerIndex >= 0 && winnerIndex < column.size())
                {
                    snapped.add(column.get(winnerIndex));
                }
                else
                {
                    snapped.add(0);
                }
            }
        }
        return snapped;
    }
    @Inject
    public ChoicemanOverlay(Client client, ItemManager itemManager)
    {
        this.client = client;
        this.itemManager = itemManager;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    private static float toDb(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        if (p == 0) {
            return -80.0f;
        }
        double lin = p / 100.0;
        return (float) (20.0 * Math.log10(lin));
    }

    @Override
    public void startRollAnimation(int dummy, int rollDurationMs, Supplier<Integer> randomLockedItemSupplier)
    {
        setSelectionPending(false);
        if (config.enableRollSounds()) {
            try {
                float volumeDb = toDb(config.rollSoundVolume());
                audioPlayer.play(ChoicemanOverlay.class, "/com/chanceman/tick.wav", volumeDb);
            } catch (IOException | UnsupportedAudioFileException | LineUnavailableException ex) {
                log.warn("Choiceman: failed to play tick.wav", ex);
            }
        }

        columnCount = determineColumnCount();

        this.rollDurationMs = rollDurationMs;
        this.rollStartMs = System.currentTimeMillis();
        this.rollOffset = 0f;
        this.currentSpeed = INITIAL_SPEED;
        this.randomLockedItemSupplier = randomLockedItemSupplier;
        this.isAnimating = true;
        this.lastUpdateNanos = System.nanoTime();

        this.isSnapping = false;
        this.snapStartMs = 0L;
        this.snapBase = 0f;
        this.snapResidualStart = 0f;
        this.snapTarget = 0f;
        this.winnerDelta = 0;

        synchronized (rollingColumns) {
            rollingColumns.clear();
            for (int c = 0; c < columnCount; c++)
            {
                List<Integer> column = new ArrayList<>();
                for (int i = 0; i < DRAW_COUNT; i++) {
                    column.add(randomLockedItemSupplier.get());
                }
                rollingColumns.add(column);
            }
        }
    }

    @Override
    public int getFinalItem()
    {
        if (!currentOptions.isEmpty())
        {
            return currentOptions.get(0);
        }
        synchronized (rollingColumns) {
            if (rollingColumns.isEmpty()) {
                return 0;
            }
            List<Integer> firstColumn = rollingColumns.get(0);
            int centerIndex = ICON_COUNT / 2;
            int idx = Math.min(centerIndex + winnerDelta, firstColumn.size() - 1);
            if (idx >= 0 && idx < firstColumn.size()) {
                return firstColumn.get(idx);
            }
        }
        return 0;
    }

    @Override
    public int getHighlightDurationMs()
    {
        return (int) HIGHLIGHT_DURATION_MS;
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!isAnimating && !selectionPending) {
            return null;
        }

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        final long nowMs = System.currentTimeMillis();
        final long elapsed = nowMs - rollStartMs;
        final boolean selectionMode = selectionPending && !currentOptions.isEmpty();
        final boolean highlightPhase = selectionMode || (elapsed > rollDurationMs);

        if (!selectionMode && elapsed > rollDurationMs + HIGHLIGHT_DURATION_MS) {
            isAnimating = false;
            return null;
        }

        final long nowNanos = System.nanoTime();
        float dt = 0f;
        if (lastUpdateNanos != 0L) {
            dt = (nowNanos - lastUpdateNanos) / 1_000_000_000f;
            if (dt > MAX_DT) dt = MAX_DT;
        }
        lastUpdateNanos = nowNanos;

        if (!selectionMode) {
            final float t = (rollDurationMs > 0) ? Math.min(1f, elapsed / (float) rollDurationMs) : 1f;
            final float eased = (float) Math.pow(1f - t, 3);
            currentSpeed = MIN_SPEED + (INITIAL_SPEED - MIN_SPEED) * eased;
        }

        final int vpX = client.getViewportXOffset();
        final int vpY = client.getViewportYOffset();
        final int vpWidth = client.getViewportWidth();
        final int centerX = vpX + (vpWidth / 2);

        final float slotScale = selectionMode ? 1.85f : 1f;
        final int baseSlotWidth = ICON_W + SLOT_PADDING_X * 2;
        final int baseSlotHeight = ICON_H + SLOT_PADDING_Y * 2;
        final int slotWidth = selectionMode
                ? Math.max(Math.round(baseSlotWidth * slotScale), 180)
                : baseSlotWidth;
        final int slotHeight = selectionMode
                ? Math.max(Math.round(baseSlotHeight * slotScale), 120)
                : baseSlotHeight;
        final int spacing = Math.max(14, Math.round(COLUMN_SPACING * slotScale));
        final int totalWidth = columnCount * slotWidth + (columnCount - 1) * spacing;
        final int slotsLeftX = centerX - ( totalWidth / 2 ) + (selectionMode ? 0 : BOX_SHIFT_X);
        final int slotTopY = selectionMode
                ? (vpY + SELECTION_TOP_MARGIN)
                : vpY + OFFSET_TOP;

        final float middleIndex = (ICON_COUNT - 1) / 2f;
        final int iconSize = selectionMode
                ? Math.max(Math.min(slotWidth, slotHeight) - 32, Math.round(ICON_W * 1.6f))
                : ICON_W;
        final int iconPadX;
        final int iconPadY;
        if (selectionMode)
        {
            iconPadX = Math.max(18, (slotWidth - iconSize) / 2);
            iconPadY = Math.max(12, (slotHeight - iconSize) / 2 - LABEL_FONT.getSize());
        }
        else
        {
            iconPadX = SLOT_PADDING_X;
            iconPadY = SLOT_PADDING_Y;
        }
        final float contentCenterY = slotTopY + iconPadY + ICON_H / 2f;
        final float iconsTopYF = contentCenterY - middleIndex * STEP - ICON_H / 2f;
        final int[] columnXs = new int[columnCount];
        for (int col = 0; col < columnCount; col++)
        {
            columnXs[col] = slotsLeftX + col * (slotWidth + spacing);
        }
        for (int col = 0; col < columnCount; col++)
        {
            if (selectionMode)
            {
                continue;
            }
            float adjust = columnOffsetAdjust[col];
            if (!highlightPhase && !isSnapping)
            {
                adjust += (columnSpeedScale[col] - 1f) * currentSpeed * dt;
                adjust = normalizeStep(adjust);
            }
            else if (isSnapping)
            {
                adjust *= 0.82f;
                if (Math.abs(adjust) < 0.01f)
                {
                    adjust = 0f;
                }
            }
            else if (highlightPhase)
            {
                adjust *= 0.9f;
                if (Math.abs(adjust) < 0.01f)
                {
                    adjust = 0f;
                }
            }
            columnOffsetAdjust[col] = adjust;
        }
        final int centerIndex = ICON_COUNT / 2;
        final int innerBoxXInset = FRAME_CONTENT_INSET;
        final int innerBoxYInset = FRAME_CONTENT_INSET;
        final int innerBoxW = iconSize - innerBoxXInset * 2;
        final int innerBoxH = iconSize - innerBoxYInset * 2;

        for (int col = 0; col < columnCount; col++)
        {
            drawSlotWindow(g, columnXs[col], slotTopY, slotWidth, slotHeight, slotScale);
        }

        final Shape oldClip = g.getClip();
        g.setClip(slotsLeftX, slotTopY, totalWidth, slotHeight);

        if (!selectionMode) {
            synchronized (columnHitboxes)
            {
                columnHitboxes.clear();
            }
        }

        if (selectionMode)
        {
            synchronized (columnHitboxes)
            {
                columnHitboxes.clear();
                for (int col = 0; col < columnCount; col++)
                {
                    int slotX = columnXs[col] + iconPadX;
                    int slotY = slotTopY + iconPadY;
                    if (!selectionMode && iconFrameImage != null) {
                        g.drawImage(iconFrameImage, slotX, slotY, iconSize, iconSize, null);
                    }
                    int optionId = col < currentOptions.size() ? currentOptions.get(col) : 0;
                    if (optionId != 0)
                    {
                        BufferedImage optionImage = itemManager.getImage(optionId, 1, false);
                        if (optionImage != null)
                        {
                            g.drawImage(optionImage,
                                    slotX + innerBoxXInset,
                                    slotY + innerBoxYInset,
                                    innerBoxW,
                                    innerBoxH,
                                    null);
                        }
                    }
                    columnHitboxes.add(new Rectangle(columnXs[col], slotTopY, slotWidth, slotHeight));
                    drawItemLabel(
                            g,
                            columnXs[col],
                            slotTopY,
                            slotWidth,
                            slotHeight,
                            getItemNameSafe(optionId),
                            true
                    );
                }
            }

            if (highlightPhase)
            {
                for (int col = 0; col < columnCount; col++)
                {
                    int optionId = col < currentOptions.size() ? currentOptions.get(col) : 0;
                    if (optionId == 0)
                    {
                        continue;
                    }
                    int slotX = columnXs[col] + iconPadX;
                    int slotY = slotTopY + iconPadY;
                    drawHighlight(g, slotX, slotY, optionId, iconSize, true);
                    drawItemLabel(
                            g,
                            columnXs[col],
                            slotTopY,
                            slotWidth,
                            slotHeight,
                            getItemNameSafe(optionId),
                            true
                    );
                }
            }
        }
        else
        {
            synchronized (rollingColumns) {
                if (!highlightPhase && !isSnapping && (rollStartMs + rollDurationMs - nowMs) <= SNAP_DURATION_MS) {
                    isSnapping = true;
                    snapStartMs = nowMs;

                    final float k = (float) Math.floor(rollOffset / STEP);
                    snapBase = k * STEP;
                    snapResidualStart = rollOffset - snapBase;
                    final boolean goNext = (snapResidualStart / STEP) >= SNAP_NEXT_THRESHOLD;
                    winnerDelta = goNext ? 1 : 0;
                    snapTarget = goNext ? (snapBase + STEP) : snapBase;
                }

                if (!highlightPhase) {
                    if (isSnapping) {
                        final float u = Math.min(1f, (nowMs - snapStartMs) / (float) SNAP_DURATION_MS);
                        final float s = u * u * (3f - 2f * u);
                        final float start = rollOffset;
                        final float end = snapTarget;
                        rollOffset = start + (end - start) * s;

                        if (rollOffset >= STEP) {
                            normalizeOnce();
                            winnerDelta = 0;
                            snapBase = 0f;
                            snapTarget = 0f;
                            snapResidualStart = 0f;
                        }
                    } else {
                        rollOffset += currentSpeed * dt;
                        while (rollOffset >= STEP) {
                            normalizeOnce();
                        }
                    }
                } else if (isSnapping) {
                    rollOffset = snapTarget;
                    if (rollOffset >= STEP) {
                        normalizeOnce();
                        winnerDelta = 0;
                    }
                }

                for (int col = 0; col < columnCount; col++)
                {
                    List<Integer> column = col < rollingColumns.size() ? rollingColumns.get(col) : Collections.emptyList();
                    final int iconsX = columnXs[col] + iconPadX;
                    final int itemsToDraw = Math.min(column.size(), DRAW_COUNT);
                    for (int i = 0; i < itemsToDraw; i++) {
                        final int itemId = column.get(i);
                        final BufferedImage image = itemManager.getImage(itemId, 1, false);
                        if (image == null) continue;

                        final float columnOffset = rollOffset + columnOffsetAdjust[col];
                        final float drawYF = iconsTopYF + i * STEP - columnOffset;
                        final int drawY = Math.round(drawYF);

                        if (!selectionMode && iconFrameImage != null) {
                            g.drawImage(iconFrameImage, iconsX, drawY, ICON_W, ICON_H, null);
                        }

                        final int x = iconsX + innerBoxXInset;
                        final int y = drawY + innerBoxYInset;
                        g.drawImage(image, x, y, innerBoxW, innerBoxH, null);
                    }
                }

                if (highlightPhase) {
                    for (int col = 0; col < columnCount; col++)
                    {
                        List<Integer> column = col < rollingColumns.size() ? rollingColumns.get(col) : Collections.emptyList();
                        final int winnerIndex = Math.min(centerIndex + winnerDelta, column.size() - 1);
                        if (winnerIndex < 0 || winnerIndex >= column.size())
                        {
                            continue;
                        }

                        final int centerItemId = column.get(winnerIndex);
                        final float columnOffset = rollOffset + columnOffsetAdjust[col];
                        final float columnBaseF = iconsTopYF + centerIndex * STEP - columnOffset;
                        final int columnBaseY = Math.round(columnBaseF);
                        drawHighlight(g, columnXs[col] + iconPadX, columnBaseY, centerItemId, iconSize, false);
                        drawItemLabel(
                                g,
                                columnXs[col],
                                slotTopY,
                                slotWidth,
                                slotHeight,
                                getItemNameSafe(centerItemId),
                                false
                        );
                    }
                }
            }
        }

        g.setClip(oldClip);
        return null;
    }

    private void normalizeOnce()
    {
        if (rollOffset >= STEP) {
            rollOffset -= STEP;
            if (!rollingColumns.isEmpty()) {
                for (List<Integer> column : rollingColumns)
                {
                    if (column.isEmpty()) {
                        continue;
                    }
                    column.remove(0);
                    if (randomLockedItemSupplier != null) {
                        column.add(randomLockedItemSupplier.get());
                    }
                }
            }
        }
    }

    private float normalizeStep(float value)
    {
        if (STEP == 0)
        {
            return 0f;
        }
        float adjusted = value % STEP;
        if (adjusted < 0)
        {
            adjusted += STEP;
        }
        return adjusted;
    }

    private void drawSlotWindow(Graphics2D g, int x, int y, int width, int height, float scale)
    {
        float radius = Math.max(18f, 18f * scale * 0.9f);
        RoundRectangle2D.Float frame = new RoundRectangle2D.Float(x, y, width, height, radius, radius);
        GradientPaint paint = new GradientPaint(
                x,
                y,
                SLOT_FILL_TOP,
                x,
                y + height,
                SLOT_FILL_BOTTOM
        );
        Paint oldPaint = g.getPaint();
        Stroke oldStroke = g.getStroke();
        g.setPaint(paint);
        g.fill(frame);

        // inner glossy stroke
        RoundRectangle2D.Float inner = new RoundRectangle2D.Float(
                x + 3,
                y + 3,
                width - 6,
                height - 6,
                Math.max(12f, radius * 0.8f),
                Math.max(12f, radius * 0.8f)
        );
        g.setPaint(new Color(255, 255, 255, scale > 1f ? 80 : 45));
        g.setStroke(new BasicStroke(2f));
        g.draw(inner);

        g.setPaint(SLOT_BORDER);
        g.setStroke(new BasicStroke(scale > 1f ? 3f : 2f));
        g.draw(frame);
        g.setPaint(oldPaint);
        g.setStroke(oldStroke);
    }

    private void drawItemLabel(Graphics2D g, int slotX, int slotY, int slotWidth, int slotHeight, String text, boolean allowWrap)
    {
        if (text == null || text.isEmpty())
        {
            return;
        }
        Font oldFont = g.getFont();
        g.setFont(LABEL_FONT);
        FontMetrics fm = g.getFontMetrics();
        int maxWidth = slotWidth - 14;
        int maxLines = allowWrap ? 2 : 1;
        List<String> lines = wrapLabelText(text, maxWidth, fm, maxLines);
        int lineHeight = fm.getAscent();
        int totalHeight = lineHeight * lines.size();
        int startY = slotY + slotHeight - 8 - totalHeight + lineHeight;
        int currentY = startY;
        for (String line : lines)
        {
            int textWidth = fm.stringWidth(line);
            int textX = slotX + (slotWidth - textWidth) / 2;
            g.setColor(Color.BLACK);
            g.drawString(line, textX + 1, currentY + 1);
            g.setColor(LABEL_COLOR);
            g.drawString(line, textX, currentY);
            currentY += lineHeight;
        }
        g.setFont(oldFont);
    }

    private List<String> wrapLabelText(String text, int maxWidth, FontMetrics fm, int maxLines)
    {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty())
        {
            return lines;
        }
        String remaining = text.trim();
        while (!remaining.isEmpty() && lines.size() < maxLines)
        {
            String line = fitLine(remaining, maxWidth, fm);
            if (line.isEmpty())
            {
                break;
            }
            lines.add(line);
            if (remaining.length() <= line.length())
            {
                remaining = "";
            }
            else
            {
                remaining = remaining.substring(line.length()).trim();
            }
        }
        if (!remaining.isEmpty() && !lines.isEmpty())
        {
            int lastIdx = lines.size() - 1;
            lines.set(lastIdx, trimWithEllipsis(lines.get(lastIdx), maxWidth, fm));
        }
        return lines;
    }

    private String fitLine(String text, int maxWidth, FontMetrics fm)
    {
        if (fm.stringWidth(text) <= maxWidth)
        {
            return text;
        }
        String[] words = text.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String word : words)
        {
            String candidate = builder.length() == 0 ? word : builder + " " + word;
            if (fm.stringWidth(candidate) <= maxWidth)
            {
                builder.setLength(0);
                builder.append(candidate);
            }
            else
            {
                if (builder.length() == 0)
                {
                    return trimWithEllipsis(word, maxWidth, fm);
                }
                break;
            }
        }
        return builder.toString();
    }

    private String trimWithEllipsis(String text, int maxWidth, FontMetrics fm)
    {
        String ellipsis = "...";
        int ellipsisWidth = fm.stringWidth(ellipsis);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++)
        {
            char ch = text.charAt(i);
            sb.append(ch);
            if (fm.stringWidth(sb.toString()) + ellipsisWidth > maxWidth)
            {
                sb.deleteCharAt(sb.length() - 1);
                break;
            }
        }
        return sb.toString() + ellipsis;
    }

    private String getItemNameSafe(int itemId)
    {
        if (itemId <= 0)
        {
            return "";
        }
        ItemComposition composition = itemManager.getItemComposition(itemId);
        if (composition == null)
        {
            return "";
        }
        String name = composition.getName();
        if (name == null)
        {
            return "";
        }
        name = name.trim();
        if (name.isEmpty() || name.equalsIgnoreCase("Members"))
        {
            return "";
        }
        return name;
    }

    private void drawHighlight(Graphics2D g, int iconsX, int baseY, int itemId, int iconDimension, boolean emphasize)
    {
        final float glowScale = emphasize ? 2.6f : 2.0f;
        final float glowHeightScale = emphasize ? 2.8f : 2.2f;
        final int glowW = (int) (iconDimension * glowScale);
        final int glowH = (int) (iconDimension * glowHeightScale);
        final float cx = iconsX + iconDimension / 2f;
        final float cy = baseY + iconDimension / 2f;

        final RadialGradientPaint glow = new RadialGradientPaint(
                new Point2D.Float(cx, cy),
                glowH / 2f,
                new float[]{0f, 1f},
                new Color[]{
                        new Color(255, 255, 160, 150),
                        new Color(255, 255, 160, 0)
                }
        );
        final Composite old = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(0.75f));
        g.setPaint(glow);
        g.fill(new Ellipse2D.Float(cx - glowW / 2f, cy - glowH / 2f, glowW, glowH));
        g.setComposite(old);

        final float centerScale = emphasize ? 1.6f : 1.15f;
        final int innerBoxXInset = FRAME_CONTENT_INSET;
        final int innerBoxYInset = FRAME_CONTENT_INSET;
        final int innerBoxW = iconDimension - innerBoxXInset * 2;
        final int innerBoxH = iconDimension - innerBoxYInset * 2;

        final int innerBoxX = iconsX + innerBoxXInset;
        final int innerBoxY = baseY + innerBoxYInset;

        final int scaledW = (int) (innerBoxW * centerScale);
        final int scaledH = (int) (innerBoxH * centerScale);
        final int scaledX = innerBoxX + (innerBoxW - scaledW) / 2;
        final int scaledY = innerBoxY + (innerBoxH - scaledH) / 2;

        final BufferedImage centerImg = itemManager.getImage(itemId, 1, false);
        if (centerImg != null) {
            g.drawImage(centerImg, scaledX, scaledY, scaledW, scaledH, null);
        }
    }
}
