package com.chanceman;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.Point;
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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
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

    private static final int FRAME_CONTENT_INSET = 2;
    private static final int SLOT_PADDING_X = 10;
    private static final int SLOT_PADDING_Y = 8;
    private static final int SELECTION_TOP_MARGIN = 15;
    private static final float CHOICE_SLOT_SCALE = 1.0f;
    private static final int MIN_CHOICE_SLOT_WIDTH = 110;
    private static final int MIN_CHOICE_SLOT_HEIGHT = 80;
    // Keep scroll slots as wide as the eventual choice buttons so icon centers align.
    private static final int MIN_SCROLL_SLOT_WIDTH = MIN_CHOICE_SLOT_WIDTH;
    private static final int SCROLL_ICON_TARGET = 48;
    private static final int SCROLL_ICON_TARGET_COMPACT = 36;
    private static final int SCROLL_ITEM_GAP = 6;
    private static final float DEFAULT_STEP = SCROLL_ICON_TARGET + SCROLL_ITEM_GAP;
    private static final Color SLOT_BORDER = new Color(201, 168, 92, 230);
    private static final Color SLOT_FILL_TOP = new Color(22, 22, 22, 235);
    private static final Color SLOT_FILL_BOTTOM = new Color(10, 10, 10, 235);
    private static final Font LABEL_FONT = new Font("SansSerif", Font.BOLD, 11);
    private static final Color TRADEABLE_GLOW_INNER = new Color(255, 255, 160, 150);
    private static final Color TRADEABLE_GLOW_OUTER = new Color(255, 255, 160, 0);
    private static final Color UNTRADEABLE_GLOW_INNER = new Color(140, 210, 255, 160);
    private static final Color UNTRADEABLE_GLOW_OUTER = new Color(140, 210, 255, 0);
    private static final Color[] TRADEABLE_GLOW = new Color[]{TRADEABLE_GLOW_INNER, TRADEABLE_GLOW_OUTER};
    private static final Color[] UNTRADEABLE_GLOW = new Color[]{UNTRADEABLE_GLOW_INNER, UNTRADEABLE_GLOW_OUTER};

    private static final int ICON_COUNT = 3;
    private static final int DRAW_COUNT = ICON_COUNT + 1;
    // Ensure scroll frames mirror the spacing seen in the final choice buttons.
    private static final int COLUMN_SPACING = 24;
    private static final int VISIBLE_ROLLING_ITEM_COUNT = 3;
    private static final int CHOICE_BUTTON_CORNER_RADIUS = 12;
    private static final int CHOICE_BUTTON_INSET = 6;
    private static final int CHOICE_BUTTON_VERTICAL_OFFSET = 10;
    private static final Color CHOICE_BUTTON_FILL_TOP = new Color(28, 28, 28, 235);
    private static final Color CHOICE_BUTTON_FILL_BOTTOM = new Color(10, 10, 10, 235);
    private static final Color CHOICE_BUTTON_BORDER = SLOT_BORDER;
    private static final Color CHOICE_BUTTON_BORDER_HOVER = new Color(255, 220, 140, 245);
    private static final Color CHOICE_BUTTON_BORDER_INNER = new Color(30, 30, 30, 210);
    private static final Color CHOICE_BUTTON_SHADOW = new Color(0, 0, 0, 90);

    private final Client client;
    private final ItemManager itemManager;

    private final List<List<Integer>> rollingColumns = Collections.synchronizedList(new ArrayList<>());
    private final float[] columnOffsetAdjust = new float[5];
    private final float[] columnSpeedScale = new float[5];
    private final Random spinRandom = new Random();
    private final Set<Integer> uniqueRollItems = Collections.synchronizedSet(new HashSet<>());

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
            columnOffsetAdjust[i] = spinRandom.nextFloat() * DEFAULT_STEP;
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
        uniqueRollItems.clear();

        synchronized (rollingColumns) {
            rollingColumns.clear();
            for (int c = 0; c < columnCount; c++)
            {
                List<Integer> column = new ArrayList<>();
                for (int i = 0; i < DRAW_COUNT; i++) {
                    column.add(nextUniqueRollItem());
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
        
        final Shape oldClip = g.getClip();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        final long nowMs = System.currentTimeMillis();
        final long elapsed = nowMs - rollStartMs;
        final boolean clickableSelection = selectionPending && !currentOptions.isEmpty();
        final boolean highlightPhase = clickableSelection || (elapsed > rollDurationMs);

        if (clickableSelection && currentOptions.size() < columnCount)
        {
            syncSelectionOptionsWithColumns();
        }

        if (!clickableSelection && elapsed > rollDurationMs + HIGHLIGHT_DURATION_MS) {
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

        if (!highlightPhase) {
            final float t = (rollDurationMs > 0) ? Math.min(1f, elapsed / (float) rollDurationMs) : 1f;
            final float eased = (float) Math.pow(1f - t, 3);
            currentSpeed = MIN_SPEED + (INITIAL_SPEED - MIN_SPEED) * eased;
        }

        final int vpX = client.getViewportXOffset();
        final int vpY = client.getViewportYOffset();
        final int vpWidth = client.getViewportWidth();
        final int centerX = vpX + (vpWidth / 2);

        final float slotScale = CHOICE_SLOT_SCALE;
        final int baseSlotWidth = ICON_W + SLOT_PADDING_X * 2;
        final int baseSlotHeight = ICON_H + SLOT_PADDING_Y * 2;
        final int rollingVisibleItems = Math.max(2, VISIBLE_ROLLING_ITEM_COUNT);
        final float iconTargetSize = clickableSelection ? SCROLL_ICON_TARGET : SCROLL_ICON_TARGET_COMPACT;
        final int rollingContentSpan = Math.round(
                iconTargetSize * rollingVisibleItems
                        + SCROLL_ITEM_GAP * (rollingVisibleItems - 1)
        );
        final int minSlotWidth = clickableSelection ? MIN_CHOICE_SLOT_WIDTH : MIN_SCROLL_SLOT_WIDTH;
        final int slotWidth = Math.max(Math.round(baseSlotWidth * slotScale), minSlotWidth);
        final int scaledSlotHeight = Math.max(Math.round(baseSlotHeight * slotScale), MIN_CHOICE_SLOT_HEIGHT);
        final int slotHeight = Math.max(scaledSlotHeight, rollingContentSpan + SLOT_PADDING_Y * 2);
        final int spacing = Math.max(14, Math.round(COLUMN_SPACING * slotScale));
        final int totalWidth = columnCount * slotWidth + (columnCount - 1) * spacing;
        final int slotsLeftX = centerX - ( totalWidth / 2 );
        final int slotTopY = vpY + SELECTION_TOP_MARGIN;

        final float middleIndex = (ICON_COUNT - 1) / 2f;
        final float scrollGap = SCROLL_ITEM_GAP;
        final float maxIconWidth = slotWidth - SLOT_PADDING_X * 2f;
        final float maxIconHeight = (slotHeight - SLOT_PADDING_Y * 2f - (rollingVisibleItems - 1) * scrollGap) / rollingVisibleItems;
        final float rollingIconSize = Math.max(ICON_W, Math.min(maxIconWidth, maxIconHeight));
        final int iconSize = Math.max(1, Math.round(rollingIconSize));
        final float activeStep = iconSize + scrollGap;
        final int iconPadX = Math.max(SLOT_PADDING_X, (slotWidth - iconSize) / 2);
        final int iconPadY = SLOT_PADDING_Y;
        final int rollingContentHeight = slotHeight - iconPadY * 2;
        final float contentCenterY = slotTopY + iconPadY + rollingContentHeight / 2f;
        final float iconsTopYF = contentCenterY - middleIndex * activeStep - iconSize / 2f;
        final int[] columnXs = new int[columnCount];
        for (int col = 0; col < columnCount; col++)
        {
            columnXs[col] = slotsLeftX + col * (slotWidth + spacing);
        }

        final int centerIndex = ICON_COUNT / 2;
        final int innerBoxXInset = FRAME_CONTENT_INSET;
        final int innerBoxYInset = FRAME_CONTENT_INSET;
        final int innerBoxW = iconSize - innerBoxXInset * 2;
        final int innerBoxH = iconSize - innerBoxYInset * 2;

        if (!clickableSelection)
        {
            for (int col = 0; col < columnCount; col++)
            {
                drawSlotWindow(g, columnXs[col], slotTopY, slotWidth, slotHeight, slotScale);
            }

            for (int col = 0; col < columnCount; col++)
            {
                float adjust = columnOffsetAdjust[col];
                if (!highlightPhase && !isSnapping)
                {
                    adjust += (columnSpeedScale[col] - 1f) * currentSpeed * dt;
                    adjust = normalizeStep(adjust, activeStep);
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

            g.setClip(slotsLeftX, slotTopY, totalWidth, slotHeight);

            synchronized (columnHitboxes)
            {
                columnHitboxes.clear();
            }

            synchronized (rollingColumns)
            {
                if (!highlightPhase && !isSnapping && (rollStartMs + rollDurationMs - nowMs) <= SNAP_DURATION_MS) {
                    isSnapping = true;
                    snapStartMs = nowMs;

                    final float k = (float) Math.floor(rollOffset / activeStep);
                    snapBase = k * activeStep;
                    snapResidualStart = rollOffset - snapBase;
                    final boolean goNext = (snapResidualStart / activeStep) >= SNAP_NEXT_THRESHOLD;
                    winnerDelta = goNext ? 1 : 0;
                    snapTarget = goNext ? (snapBase + activeStep) : snapBase;
                }

                if (!highlightPhase) {
                    if (isSnapping) {
                        final float u = Math.min(1f, (nowMs - snapStartMs) / (float) SNAP_DURATION_MS);
                        final float s = u * u * (3f - 2f * u);
                        final float start = rollOffset;
                        final float end = snapTarget;
                        rollOffset = start + (end - start) * s;

                        if (rollOffset >= activeStep) {
                            normalizeOnce(activeStep);
                            winnerDelta = 0;
                            snapBase = 0f;
                            snapTarget = 0f;
                            snapResidualStart = 0f;
                        }
                    } else {
                        rollOffset += currentSpeed * dt;
                        while (rollOffset >= activeStep) {
                            normalizeOnce(activeStep);
                        }
                    }
                } else if (isSnapping) {
                    rollOffset = snapTarget;
                    if (rollOffset >= activeStep) {
                        normalizeOnce(activeStep);
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
                        final float drawYF = iconsTopYF + i * activeStep - columnOffset;
                        final int drawY = Math.round(drawYF);

                        if (iconFrameImage != null) {
                            g.drawImage(iconFrameImage, iconsX, drawY, iconSize, iconSize, null);
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
                        final float columnBaseF = iconsTopYF + centerIndex * activeStep - columnOffset;
                        final int columnBaseY = Math.round(columnBaseF);
                        drawHighlight(g, columnXs[col] + iconPadX, columnBaseY, centerItemId, iconSize, false);
                        if (clickableSelection && col < currentOptions.size()) {
                            final int iconX = columnXs[col] + iconPadX;
                            final int iconY = columnBaseY;
                            Rectangle rect = new Rectangle(iconX, iconY, iconSize, iconSize);
                            synchronized (columnHitboxes) {
                                columnHitboxes.add(rect);
                            }
                        }
                    }
                }
            }
        }
        else
        {
            renderSelectionButtons(g, columnXs, slotTopY, slotWidth, slotHeight, iconSize, iconPadX);
        }

        g.setClip(oldClip);

        if (clickableSelection)
        {
            Point mouse = client.getMouseCanvasPosition();
            if (mouse != null)
            {
                Integer hoveredItem = getOptionAt(mouse.getX(), mouse.getY());
                if (hoveredItem != null)
                {
                    String hoverName = getItemNameSafe(hoveredItem);
                    if (!hoverName.isEmpty())
                    {
                        drawHoverTooltip(g, hoverName, mouse, oldClip);
                    }
                }
            }
        }
        return null;
    }

    private void renderSelectionButtons(
            Graphics2D g,
            int[] columnXs,
            int topY,
            int slotWidth,
            int slotHeight,
            int iconSize,
            int iconPadX)
    {
        List<Integer> optionsSnapshot = currentOptions == null
                ? Collections.emptyList()
                : new ArrayList<>(currentOptions);
        final int availableSlots = columnXs == null ? 0 : columnXs.length;
        final int drawCount = Math.min(optionsSnapshot.size(), availableSlots);
        if (drawCount <= 0)
        {
            synchronized (columnHitboxes)
            {
                columnHitboxes.clear();
            }
            return;
        }

        List<Rectangle> buttonRects = new ArrayList<>(drawCount);
        List<Rectangle> iconRects = new ArrayList<>(drawCount);
        final int insetX = CHOICE_BUTTON_INSET;
        final int insetY = CHOICE_BUTTON_INSET;
        final int slotBottom = topY + slotHeight - insetY;
        final int iconPaddingTop = 14;
        final int iconPaddingBottom = 14;
        final int iconY = topY + (slotHeight - iconSize) / 2 - CHOICE_BUTTON_VERTICAL_OFFSET;
        for (int i = 0; i < drawCount; i++)
        {
            final int iconX = columnXs[i] + iconPadX;
            Rectangle iconRect = new Rectangle(iconX, iconY, iconSize, iconSize);
            iconRects.add(iconRect);

            final int buttonTop = Math.max(topY + insetY, iconY - iconPaddingTop);
            final int buttonBottom = Math.min(slotBottom, iconY + iconSize + iconPaddingBottom);
            Rectangle rect = new Rectangle(
                    columnXs[i] + insetX,
                    buttonTop,
                    Math.max(1, slotWidth - insetX * 2),
                    Math.max(1, buttonBottom - buttonTop)
            );
            buttonRects.add(rect);
        }

        Point mouse = client.getMouseCanvasPosition();
        int hoveredIndex = -1;
        if (mouse != null)
        {
            for (int i = 0; i < buttonRects.size(); i++)
            {
                if (buttonRects.get(i).contains(mouse.getX(), mouse.getY()))
                {
                    hoveredIndex = i;
                    break;
                }
            }
        }

        synchronized (columnHitboxes)
        {
            columnHitboxes.clear();
            columnHitboxes.addAll(buttonRects);
        }

        for (int i = 0; i < drawCount; i++)
        {
            boolean hovered = (i == hoveredIndex);
            drawChoiceButton(g, buttonRects.get(i), iconRects.get(i), optionsSnapshot.get(i), hovered);
        }
    }

    private void drawChoiceButton(
            Graphics2D g,
            Rectangle rect,
            Rectangle iconRect,
            int itemId,
            boolean hovered)
    {
        Paint previousPaint = g.getPaint();
        Stroke previousStroke = g.getStroke();
        Composite previousComposite = g.getComposite();

        final float cornerRadius = CHOICE_BUTTON_CORNER_RADIUS;
        RoundRectangle2D.Float buttonShape = new RoundRectangle2D.Float(
                rect.x,
                rect.y,
                rect.width,
                rect.height,
                cornerRadius,
                cornerRadius
        );

        RoundRectangle2D.Float shadowShape = new RoundRectangle2D.Float(
                rect.x + 1,
                rect.y + 3,
                rect.width,
                rect.height,
                cornerRadius + 4,
                cornerRadius + 4
        );
        g.setColor(CHOICE_BUTTON_SHADOW);
        g.fill(shadowShape);

        Color top = hovered
                ? blendColors(CHOICE_BUTTON_FILL_TOP, Color.WHITE, 0.15f)
                : CHOICE_BUTTON_FILL_TOP;
        Color bottom = hovered
                ? blendColors(CHOICE_BUTTON_FILL_BOTTOM, Color.WHITE, 0.12f)
                : CHOICE_BUTTON_FILL_BOTTOM;
        GradientPaint paint = new GradientPaint(
                rect.x,
                rect.y,
                top,
                rect.x,
                rect.y + rect.height,
                bottom
        );
        g.setPaint(paint);
        g.fill(buttonShape);

        g.setColor(hovered ? CHOICE_BUTTON_BORDER_HOVER : CHOICE_BUTTON_BORDER);
        g.setStroke(new BasicStroke(hovered ? 3f : 2f));
        g.draw(buttonShape);

        RoundRectangle2D.Float innerBorder = new RoundRectangle2D.Float(
                rect.x + 2,
                rect.y + 2,
                rect.width - 4,
                rect.height - 4,
                Math.max(4f, cornerRadius - 4f),
                Math.max(4f, cornerRadius - 4f)
        );
        g.setColor(CHOICE_BUTTON_BORDER_INNER);
        g.setStroke(new BasicStroke(1.4f));
        g.draw(innerBorder);

        g.setPaint(previousPaint);
        g.setStroke(previousStroke);
        g.setComposite(previousComposite);

        drawIconGlow(g, iconRect, itemId, hovered);

        final int iconSize = iconRect.width;
        final int iconX = iconRect.x;
        final int iconY = iconRect.y;
        BufferedImage icon = itemManager.getImage(itemId, 1, false);
        if (icon != null)
        {
            g.drawImage(icon, iconX, iconY, iconSize, iconSize, null);
        }

    }

    private Color blendColors(Color base, Color accent, float mix)
    {
        mix = Math.max(0f, Math.min(1f, mix));
        final float inv = 1f - mix;
        int r = Math.round(base.getRed() * inv + accent.getRed() * mix);
        int g = Math.round(base.getGreen() * inv + accent.getGreen() * mix);
        int b = Math.round(base.getBlue() * inv + accent.getBlue() * mix);
        int a = Math.round(base.getAlpha() * inv + accent.getAlpha() * mix);
        r = Math.min(255, Math.max(0, r));
        g = Math.min(255, Math.max(0, g));
        b = Math.min(255, Math.max(0, b));
        a = Math.min(255, Math.max(0, a));
        return new Color(r, g, b, a);
    }

    private void drawIconGlow(Graphics2D g, Rectangle iconRect, int itemId, boolean hovered)
    {
        Color[] palette = getHighlightPalette(itemId);
        final float cx = iconRect.x + iconRect.width / 2f;
        final float cy = iconRect.y + iconRect.height / 2f;
        final float radius = Math.max(iconRect.width, iconRect.height) * (hovered ? 0.95f : 0.82f);
        final Color centerColor = blendColors(
                new Color(30, 30, 30, hovered ? 230 : 210),
                palette[0],
                hovered ? 0.6f : 0.45f
        );
        final Color midColor = new Color(
                palette[0].getRed(),
                palette[0].getGreen(),
                palette[0].getBlue(),
                hovered ? 150 : 120
        );
        final Color edgeColor = new Color(
                palette[1].getRed(),
                palette[1].getGreen(),
                palette[1].getBlue(),
                0
        );
        final RadialGradientPaint glow = new RadialGradientPaint(
                new Point2D.Float(cx, cy),
                radius,
                new float[]{0f, 0.7f, 1f},
                new Color[]{centerColor, midColor, edgeColor}
        );
        Composite oldComposite = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(0.9f));
        g.setPaint(glow);
        g.fill(new Ellipse2D.Float(
                cx - radius,
                cy - radius,
                radius * 2f,
                radius * 2f
        ));
        g.setComposite(oldComposite);
    }

    private void normalizeOnce(float step)
    {
        if (rollOffset >= step) {
            rollOffset -= step;
            if (!rollingColumns.isEmpty()) {
                for (List<Integer> column : rollingColumns) {
                    if (column == null || column.isEmpty()) {
                        continue;
                    }
                    column.remove(0);
                    if (randomLockedItemSupplier != null) {
                        column.add(nextUniqueRollItem());
                    }
                }
            }
        }
    }

    private int nextUniqueRollItem()
    {
        if (randomLockedItemSupplier == null)
        {
            return 0;
        }

        final int maxAttempts = Math.max(10, columnCount * DRAW_COUNT);
        int fallback = 0;

        for (int attempt = 0; attempt < maxAttempts; attempt++)
        {
            Integer rolled = randomLockedItemSupplier.get();
            int candidate = rolled != null ? rolled : 0;
            fallback = candidate;
            if (candidate == 0)
            {
                break;
            }
            if (uniqueRollItems.add(candidate))
            {
                return candidate;
            }
        }

        if (fallback != 0)
        {
            uniqueRollItems.add(fallback);
        }
        return fallback;
    }

    private float normalizeStep(float value, float step)
    {
        if (step == 0f)
        {
            return 0f;
        }
        float adjusted = value % step;
        if (adjusted < 0)
        {
            adjusted += step;
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

    private void drawHoverTooltip(Graphics2D g, String text, Point mouse, Shape clipShape)
    {
        if (text == null || text.isEmpty())
        {
            return;
        }
        Font oldFont = g.getFont();
        g.setFont(LABEL_FONT);
        FontMetrics fm = g.getFontMetrics();
        int padding = 6;
        int width = fm.stringWidth(text) + padding * 2;
        int height = fm.getHeight() + padding * 2;

        Rectangle clipBounds = clipShape != null ? clipShape.getBounds()
                : new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight());

        int x = mouse.getX() + 12;
        int y = mouse.getY() - 12;
        x = Math.max(clipBounds.x, Math.min(x, clipBounds.x + clipBounds.width - width));
        y = Math.max(clipBounds.y + height, Math.min(y, clipBounds.y + clipBounds.height));
        int top = y - height;

        g.setColor(new Color(0, 0, 0, 190));
        g.fillRoundRect(x, top, width, height, 8, 8);
        g.setColor(new Color(200, 200, 200, 220));
        g.drawRoundRect(x, top, width, height, 8, 8);

        g.setColor(Color.WHITE);
        g.drawString(text, x + padding, top + padding + fm.getAscent());
        g.setFont(oldFont);
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
        if (name.isEmpty() || name.equalsIgnoreCase("null") || name.equalsIgnoreCase("Members") || name.equalsIgnoreCase("(Members)") || name.matches("(?i)null\\s*\\(Members\\)"))
        {
            return "";
        }
        return name;
    }

    private void drawHighlight(Graphics2D g, int iconsX, int baseY, int itemId, int iconDimension, boolean emphasize)
    {
        final float glowScale = emphasize ? 1.8f : 1.4f;
        final float glowHeightScale = emphasize ? 2.0f : 1.6f;
        final int glowW = (int) (iconDimension * glowScale);
        final int glowH = (int) (iconDimension * glowHeightScale);
        final float cx = iconsX + iconDimension / 2f;
        final float cy = baseY + iconDimension / 2f;

        final Color[] glowPalette = getHighlightPalette(itemId);
        final RadialGradientPaint glow = new RadialGradientPaint(
                new Point2D.Float(cx, cy),
                glowH / 2f,
                new float[]{0f, 1f},
                new Color[]{
                        glowPalette[0],
                        glowPalette[1]
                }
        );
        final Composite old = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(0.75f));
        g.setPaint(glow);
        g.fill(new Ellipse2D.Float(cx - glowW / 2f, cy - glowH / 2f, glowW, glowH));
        g.setComposite(old);

        final float centerScale = emphasize ? 1.25f : 1.05f;
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

    private Color[] getHighlightPalette(int itemId)
    {
        return isTradeableItem(itemId) ? TRADEABLE_GLOW : UNTRADEABLE_GLOW;
    }

    private boolean isTradeableItem(int itemId)
    {
        if (itemId <= 0)
        {
            return true;
        }
        ItemComposition composition = itemManager.getItemComposition(itemId);
        return composition == null || composition.isTradeable();
    }
}
