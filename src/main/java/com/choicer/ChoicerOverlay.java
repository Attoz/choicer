package com.choicer;

import com.choicer.filters.QuestItemAllowlist;
import com.choicer.ui.TextFitUtil;
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
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.awt.LinearGradientPaint;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.awt.event.MouseEvent;
import java.util.function.Supplier;

/**
 * Overlay used for Choicer rolls.
 * Presents the roll strip vertically with a configurable number of visible
 * slots.
 */
@Singleton
@Slf4j
public class ChoicerOverlay extends Overlay implements RollOverlay {
    private static final float SNAP_NEXT_THRESHOLD = 0.55f;
    private static final long SNAP_DURATION_MS = 420L;
    private static final long HIGHLIGHT_DURATION_MS = 3000L;
    private static final float INITIAL_SPEED = 980f;
    private static final float MIN_SPEED = 120f;
    private static final float MAX_DT = 0.05f;

    private static final int ICON_W = 32;
    private static final int ICON_H = 32;
    private static final int SPACING = 5;

    private static final int FRAME_CONTENT_INSET = 2;
    private static final int SLOT_PADDING_X = 6;
    private static final int SLOT_PADDING_Y = 5;
    private static final int SELECTION_TOP_MARGIN = 15;
    private static final float CHOICE_SLOT_SCALE = 1.0f;
    private static final int MIN_CHOICE_SLOT_WIDTH = 150;
    private static final int MIN_CHOICE_SLOT_HEIGHT = 150;
    // Keep scroll slots as wide as the eventual choice buttons so icon centers
    // align.
    private static final int MIN_SCROLL_SLOT_WIDTH = MIN_CHOICE_SLOT_WIDTH;
    private static final int SCROLL_ICON_TARGET = 44;
    private static final int SCROLL_ICON_TARGET_COMPACT = 36;
    private static final int SCROLL_ITEM_GAP = 6;
    private static final float DEFAULT_STEP = SCROLL_ICON_TARGET + SCROLL_ITEM_GAP;
    private static final Color SLOT_BORDER = new Color(186, 148, 96, 235);
    private static final Color SLOT_FILL_TOP = new Color(54, 46, 34, 235);
    private static final Color SLOT_FILL_BOTTOM = new Color(33, 28, 22, 235);
    private static final Color SLOT_HIGHLIGHT = new Color(220, 188, 135, 95);
    private static final Font LABEL_FONT = new Font("SansSerif", Font.BOLD, 10);
    private static final Font HOVER_TOOLTIP_FONT = new Font("SansSerif", Font.PLAIN, 12);
    private static final Color HOVER_TOOLTIP_BG = new Color(0, 0, 0, 190);
    private static final Color HOVER_TOOLTIP_BORDER = new Color(110, 92, 64, 230);
    private static final Color TRADEABLE_GLOW_INNER = new Color(214, 184, 112, 170);
    private static final Color TRADEABLE_GLOW_OUTER = new Color(196, 168, 110, 0);
    private static final Color UNTRADEABLE_GLOW_INNER = new Color(128, 176, 148, 170);
    private static final Color UNTRADEABLE_GLOW_OUTER = new Color(148, 178, 160, 0);
    private static final Color[] TRADEABLE_GLOW = new Color[] { TRADEABLE_GLOW_INNER, TRADEABLE_GLOW_OUTER };
    private static final Color[] UNTRADEABLE_GLOW = new Color[] { UNTRADEABLE_GLOW_INNER, UNTRADEABLE_GLOW_OUTER };

    private static final int ICON_COUNT = 3;
    private static final int DRAW_COUNT = ICON_COUNT + 1;
    // Ensure scroll frames mirror the spacing seen in the final choice buttons.
    private static final int COLUMN_SPACING = 18;
    private static final int VISIBLE_ROLLING_ITEM_COUNT = 4;
    private static final int VISIBLE_SELECTION_ITEM_COUNT = 2;
    private static final int CHOICE_BUTTON_INSET = 6;
    private static final Color CHOICE_BUTTON_FILL_TOP = new Color(64, 53, 37, 235);
    private static final Color CHOICE_BUTTON_FILL_BOTTOM = new Color(42, 35, 25, 235);
    private static final Color CHOICE_BUTTON_BORDER = SLOT_BORDER;
    private static final Color CHOICE_BUTTON_BORDER_HOVER = new Color(214, 176, 118, 245);
    private static final Color CHOICE_BUTTON_BORDER_INNER = new Color(64, 46, 30, 210);
    private static final Color CORNER_HIGHLIGHT = new Color(196, 146, 90, 235);
    private static final Color CORNER_SHADOW = new Color(18, 10, 6, 235);
    private static final Color CHOICE_BUTTON_SHADOW = new Color(0, 0, 0, 100);
    private static final Color TOOLTIP_TEXT = new Color(238, 224, 186);
    private static final int MIN_LABEL_FONT_SIZE = 9;
    private static final int CHOICE_LABEL_RESERVED_HEIGHT = 78;
    private static final int CHOICE_ICON_TOP_PADDING = 8;
    private static final int CHOICE_BUTTON_Y_OFFSET = 12;
    private static final float RESPONSIVE_BASE_VIEWPORT_HEIGHT = 900f;
    private static final float RESPONSIVE_MIN_SCALE = 0.62f;
    private static final float RESPONSIVE_MAX_SCALE = 1.24f;
    private static final long PULSE_PERIOD_NS = 1_400_000_000L;
    private static final long SHIMMER_PERIOD_NS = 2_600_000_000L;
    private static final float PULSE_MIN_ALPHA = 0.07f;
    private static final float PULSE_MAX_ALPHA = 0.18f;
    private static final float SHIMMER_ALPHA = 0.16f;
    private static final float SHIMMER_WIDTH_RATIO = 0.28f;
    private static final long RESOLVE_DURATION_MS = 900L;
    private static final float RESOLVE_SELECTED_SCALE = 1.12f;
    private static final float RESOLVE_NON_SELECTED_SCALE = 0.9f;
    private static final float RESOLVE_FALL_DISTANCE = 0.45f;
    private static final long RESOLVE_HOLD_MS = 650L;
    private static final long SELECTION_REVEAL_MS = 900L;
    private static final long IMPACT_FLASH_MS = 420L;
    private static final int ANTICIPATION_WINDOW_MS = 650;
    private static final Color CENTER_MARKER = new Color(210, 180, 98, 150);
    private static final Color CENTER_MARKER_SHADOW = new Color(22, 18, 12, 120);

    private final Client client;
    private final ItemManager itemManager;

    private final List<List<Integer>> rollingColumns = Collections.synchronizedList(new ArrayList<>());
    private final float[] columnOffsetAdjust = new float[5];
    private final float[] columnSpeedScale = new float[5];
    private final Random spinRandom = new Random();
    private final Set<Integer> uniqueRollItems = Collections.synchronizedSet(new HashSet<>());

    @Inject
    private AudioPlayer audioPlayer;
    @Inject
    private ChoicerConfig config;

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
    private long selectionStartMs = 0L;
    private boolean impactSoundPlayed = false;

    private List<Integer> currentOptions = Collections.emptyList();
    private volatile boolean resolveAnimating = false;
    private long resolveStartMs = 0L;
    private volatile List<Integer> resolveOptions = Collections.emptyList();
    private volatile int resolveSelectedIndex = -1;
    private volatile int resolveSelectedItemId = 0;

    public void setChoicerOptions(List<Integer> options) {
        if (options == null) {
            currentOptions = Collections.emptyList();
        } else {
            currentOptions = new ArrayList<>(options);
        }
        columnCount = determineColumnCount();
        for (int i = 0; i < columnOffsetAdjust.length; i++) {
            columnOffsetAdjust[i] = spinRandom.nextFloat() * DEFAULT_STEP;
            columnSpeedScale[i] = 0.75f + spinRandom.nextFloat() * 0.45f; // 0.75x – 1.2x
        }
    }

    private int determineColumnCount() {
        if (currentOptions == null || currentOptions.isEmpty()) {
            return 2;
        }
        int capped = Math.max(2, Math.min(5, currentOptions.size()));
        if (currentOptions.size() > capped) {
            currentOptions = new ArrayList<>(currentOptions.subList(0, capped));
        }
        return capped;
    }

    public void setSelectionPending(boolean pending) {
        boolean wasPending = this.selectionPending;
        this.selectionPending = pending;
        if (pending) {
            if (!wasPending) {
                selectionStartMs = System.currentTimeMillis();
            }
            syncSelectionOptionsWithColumns();
        } else {
            synchronized (columnHitboxes) {
                columnHitboxes.clear();
            }
        }
    }

    public boolean isSelectionPending() {
        return selectionPending;
    }

    public long startSelectionResolveAnimation(int selectedItemId) {
        if (selectedItemId <= 0) {
            resolveAnimating = false;
            return 0L;
        }
        List<Integer> snapshot = currentOptions == null
                ? Collections.emptyList()
                : new ArrayList<>(currentOptions);
        resolveOptions = snapshot;
        resolveSelectedItemId = selectedItemId;
        int index = snapshot.indexOf(selectedItemId);
        resolveSelectedIndex = index >= 0 ? index : 0;
        resolveStartMs = System.currentTimeMillis();
        resolveAnimating = true;
        return RESOLVE_DURATION_MS + RESOLVE_HOLD_MS;
    }

    public Integer getOptionAt(int x, int y) {
        synchronized (columnHitboxes) {
            for (int i = 0; i < columnHitboxes.size(); i++) {
                Rectangle rect = columnHitboxes.get(i);
                if (rect.contains(x, y) && i < currentOptions.size()) {
                    return currentOptions.get(i);
                }
            }
        }
        return null;
    }

    /**
     * Exposes the current hitboxes for tests without leaking the internal list.
     */
    List<Rectangle> getCurrentHitboxes() {
        synchronized (columnHitboxes) {
            List<Rectangle> copy = new ArrayList<>(columnHitboxes.size());
            for (Rectangle rect : columnHitboxes) {
                copy.add(new Rectangle(rect));
            }
            return copy;
        }
    }

    private void syncSelectionOptionsWithColumns() {
        List<Integer> snapped = captureSnappedItems();
        if (!snapped.isEmpty()) {
            currentOptions = snapped;
        }
    }

    private List<Integer> captureSnappedItems() {
        List<Integer> snapped = new ArrayList<>();
        synchronized (rollingColumns) {
            if (rollingColumns.isEmpty()) {
                return snapped;
            }
            final int centerIndex = ICON_COUNT / 2;
            final int columns = Math.min(columnCount, rollingColumns.size());
            for (int col = 0; col < columns; col++) {
                List<Integer> column = rollingColumns.get(col);
                if (column.isEmpty()) {
                    snapped.add(0);
                    continue;
                }
                final int winnerIndex = Math.min(centerIndex + winnerDelta, column.size() - 1);
                if (winnerIndex >= 0 && winnerIndex < column.size()) {
                    snapped.add(column.get(winnerIndex));
                } else {
                    snapped.add(0);
                }
            }
        }
        return snapped;
    }

    /**
     * Allows tests (or other non-DI contexts) to provide a config instance.
     */
    public void setConfig(ChoicerConfig config) {
        this.config = config;
    }

    @Inject
    public ChoicerOverlay(Client client, ItemManager itemManager) {
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
    public void startRollAnimation(int dummy, int rollDurationMs, Supplier<Integer> randomLockedItemSupplier) {
        setSelectionPending(false);
        resolveAnimating = false;
        resolveOptions = Collections.emptyList();
        resolveSelectedIndex = -1;
        resolveSelectedItemId = 0;
        if (config.enableRollSounds()) {
            try {
                float volumeDb = toDb(config.rollSoundVolume());
                audioPlayer.play(ChoicerOverlay.class, "/com/choicer/tick.wav", volumeDb);
            } catch (Exception ex) {
                log.warn("Choicer: failed to play tick.wav", ex);
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
        this.impactSoundPlayed = false;
        this.selectionStartMs = 0L;
        uniqueRollItems.clear();

        synchronized (rollingColumns) {
            rollingColumns.clear();
            for (int c = 0; c < columnCount; c++) {
                List<Integer> column = new ArrayList<>();
                for (int i = 0; i < DRAW_COUNT; i++) {
                    column.add(nextUniqueRollItem());
                }
                rollingColumns.add(column);
            }
        }
    }

    @Override
    public int getFinalItem() {
        if (!currentOptions.isEmpty()) {
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
    public int getHighlightDurationMs() {
        return (int) HIGHLIGHT_DURATION_MS;
    }

    @Override
    public void stopAnimation() {
        isAnimating = false;
    }

    @Override
    public Dimension render(Graphics2D g) {
        if (!isAnimating && !selectionPending && !resolveAnimating) {
            return null;
        }

        final Shape oldClip = g.getClip();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        final long nowMs = System.currentTimeMillis();
        if (resolveAnimating) {
            return renderResolveAnimation(g, nowMs);
        }
        final long elapsed = nowMs - rollStartMs;
        final boolean clickableSelection = selectionPending && !currentOptions.isEmpty();
        final boolean highlightPhase = clickableSelection || (elapsed > rollDurationMs);

        if (clickableSelection && currentOptions.size() < columnCount) {
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
            if (dt > MAX_DT)
                dt = MAX_DT;
        }
        lastUpdateNanos = nowNanos;

        if (!highlightPhase) {
            final float t = (rollDurationMs > 0) ? Math.min(1f, elapsed / (float) rollDurationMs) : 1f;
            final float eased = (float) Math.pow(1f - t, 3);
            currentSpeed = MIN_SPEED + (INITIAL_SPEED - MIN_SPEED) * eased;
            int remainingMs = (int) (rollStartMs + rollDurationMs - nowMs);
            if (remainingMs > 0 && remainingMs <= ANTICIPATION_WINDOW_MS) {
                float factor = 0.6f + 0.4f * (remainingMs / (float) ANTICIPATION_WINDOW_MS);
                currentSpeed *= factor;
            }
        }

        final int vpX = client.getViewportXOffset();
        final int vpY = client.getViewportYOffset();
        final int vpWidth = client.getViewportWidth();
        final int vpHeight = client.getViewportHeight();
        final int centerX = vpX + (vpWidth / 2);

        final float layoutScale = getResponsiveLayoutScale(vpHeight);
        final float slotScale = CHOICE_SLOT_SCALE * layoutScale;
        final int baseSlotWidth = ICON_W + SLOT_PADDING_X * 2;
        final int baseSlotHeight = ICON_H + SLOT_PADDING_Y * 2;
        final int rollingVisibleItems = Math.max(2,
                clickableSelection ? VISIBLE_SELECTION_ITEM_COUNT : VISIBLE_ROLLING_ITEM_COUNT);
        final float iconTargetSize = (clickableSelection ? SCROLL_ICON_TARGET : SCROLL_ICON_TARGET_COMPACT)
                * layoutScale;
        final int rollingContentSpan = Math.round(
                iconTargetSize * rollingVisibleItems
                        + SCROLL_ITEM_GAP * (rollingVisibleItems - 1));
        final int minSlotWidth = clickableSelection ? MIN_CHOICE_SLOT_WIDTH : MIN_SCROLL_SLOT_WIDTH;
        final int scaledMinSlotWidth = scaleLayoutValue(minSlotWidth, layoutScale, baseSlotWidth);
        final int slotWidth = Math.max(Math.round(baseSlotWidth * slotScale), scaledMinSlotWidth);
        final int scaledMinSlotHeight = scaleLayoutValue(MIN_CHOICE_SLOT_HEIGHT, layoutScale, baseSlotHeight);
        final int scaledSlotHeight = Math.max(Math.round(baseSlotHeight * slotScale), scaledMinSlotHeight);
        final int slotHeight = Math.max(scaledSlotHeight, rollingContentSpan + SLOT_PADDING_Y * 2);
        final int spacing = Math.max(scaleLayoutValue(14, layoutScale, 10), Math.round(COLUMN_SPACING * slotScale));
        final int totalWidth = columnCount * slotWidth + (columnCount - 1) * spacing;
        final int slotsLeftX = centerX - (totalWidth / 2);
        final int selectionTopMargin = scaleLayoutValue(SELECTION_TOP_MARGIN, layoutScale, 8);
        final int choiceButtonYOffset = scaleLayoutValue(CHOICE_BUTTON_Y_OFFSET, layoutScale, 6);
        final int slotTopY = vpY + selectionTopMargin + (clickableSelection ? choiceButtonYOffset : 0);

        final float middleIndex = (ICON_COUNT - 1) / 2f;
        final float scrollGap = SCROLL_ITEM_GAP;
        final float maxIconWidth = slotWidth - SLOT_PADDING_X * 2f;
        final float maxIconHeight = (slotHeight - SLOT_PADDING_Y * 2f - (rollingVisibleItems - 1) * scrollGap)
                / rollingVisibleItems;
        final float targetIconSize = Math.max(ICON_W, iconTargetSize);
        final float rollingIconSize = Math.max(ICON_W, Math.min(targetIconSize, Math.min(maxIconWidth, maxIconHeight)));
        final int iconSize = Math.max(1, Math.round(rollingIconSize));
        final float activeStep = iconSize + scrollGap;
        final int iconPadX = Math.max(SLOT_PADDING_X, (slotWidth - iconSize) / 2);
        final int iconPadY = SLOT_PADDING_Y;
        final int rollingContentHeight = slotHeight - iconPadY * 2;
        final float contentCenterY = slotTopY + iconPadY + rollingContentHeight / 2f;
        final float iconsTopYF = contentCenterY - middleIndex * activeStep - iconSize / 2f;
        final int[] columnXs = new int[columnCount];
        for (int col = 0; col < columnCount; col++) {
            columnXs[col] = slotsLeftX + col * (slotWidth + spacing);
        }

        final int centerIndex = ICON_COUNT / 2;
        final int innerBoxXInset = FRAME_CONTENT_INSET;
        final int innerBoxYInset = FRAME_CONTENT_INSET;
        final int innerBoxW = iconSize - innerBoxXInset * 2;
        final int innerBoxH = iconSize - innerBoxYInset * 2;

        if (!clickableSelection) {
            for (int col = 0; col < columnCount; col++) {
                drawSlotWindow(g, columnXs[col], slotTopY, slotWidth, slotHeight, slotScale);
            }

            for (int col = 0; col < columnCount; col++) {
                float adjust = columnOffsetAdjust[col];
                if (!highlightPhase && !isSnapping) {
                    adjust += (columnSpeedScale[col] - 1f) * currentSpeed * dt;
                    adjust = normalizeStep(adjust, activeStep);
                } else if (isSnapping) {
                    adjust *= 0.82f;
                    if (Math.abs(adjust) < 0.01f) {
                        adjust = 0f;
                    }
                } else if (highlightPhase) {
                    adjust *= 0.9f;
                    if (Math.abs(adjust) < 0.01f) {
                        adjust = 0f;
                    }
                }
                columnOffsetAdjust[col] = adjust;
            }

            g.setClip(slotsLeftX, slotTopY, totalWidth, slotHeight);

            synchronized (columnHitboxes) {
                columnHitboxes.clear();
            }

            synchronized (rollingColumns) {
                if (!highlightPhase && !isSnapping && (rollStartMs + rollDurationMs - nowMs) <= SNAP_DURATION_MS) {
                    isSnapping = true;
                    snapStartMs = nowMs;

                    final float k = (float) Math.floor(rollOffset / activeStep);
                    snapBase = k * activeStep;
                    snapResidualStart = rollOffset - snapBase;
                    final boolean goNext = (snapResidualStart / activeStep) >= SNAP_NEXT_THRESHOLD;
                    winnerDelta = goNext ? 1 : 0;
                    snapTarget = goNext ? (snapBase + activeStep) : snapBase;
                    if (snapTarget < rollOffset) {
                        snapTarget += activeStep;
                        winnerDelta = 1;
                    }
                }

                if (!highlightPhase) {
                    if (isSnapping) {
                        final float u = Math.min(1f, (nowMs - snapStartMs) / (float) SNAP_DURATION_MS);
                        final float s = u * u * (3f - 2f * u);
                        final float start = rollOffset;
                        final float end = snapTarget;
                        rollOffset = start + (end - start) * s;

                        if (u >= 1f) {
                            rollOffset = end;
                            if (rollOffset >= activeStep) {
                                normalizeOnce(activeStep);
                                winnerDelta = 0;
                            }
                            isSnapping = false;
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
                    isSnapping = false;
                    snapBase = 0f;
                    snapTarget = 0f;
                    snapResidualStart = 0f;
                }

                for (int col = 0; col < columnCount; col++) {
                    List<Integer> column = col < rollingColumns.size() ? rollingColumns.get(col)
                            : Collections.emptyList();
                    final int iconsX = columnXs[col] + iconPadX;
                    final int itemsToDraw = Math.min(column.size(), DRAW_COUNT);
                    for (int i = 0; i < itemsToDraw; i++) {
                        final int itemId = column.get(i);
                        final BufferedImage image = itemManager.getImage(itemId, 1, false);
                        if (image == null)
                            continue;

                        final float columnOffset = rollOffset + columnOffsetAdjust[col];
                        final float drawYF = iconsTopYF + i * activeStep - columnOffset;
                        final int drawY = Math.round(drawYF);

                        Object oldInterpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
                        Object oldAntialias = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
                        if (highlightPhase) {
                            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
                        }
                        drawIconFrame(g, iconsX, drawY, iconSize);
                        final int x = iconsX + innerBoxXInset;
                        final int y = drawY + innerBoxYInset;
                        g.drawImage(image, x, y, innerBoxW, innerBoxH, null);
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAntialias);
                    }
                }

                if (highlightPhase) {
                    for (int col = 0; col < columnCount; col++) {
                        List<Integer> column = col < rollingColumns.size() ? rollingColumns.get(col)
                                : Collections.emptyList();
                        final int winnerIndex = Math.min(centerIndex + winnerDelta, column.size() - 1);
                        if (winnerIndex < 0 || winnerIndex >= column.size()) {
                            continue;
                        }

                        final int centerItemId = column.get(winnerIndex);
                        final float columnOffset = rollOffset + columnOffsetAdjust[col];
                        final float columnBaseF = iconsTopYF + centerIndex * activeStep - columnOffset;
                        final int columnBaseY = Math.round(columnBaseF);
                        drawHighlight(g, columnXs[col] + iconPadX, columnBaseY, centerItemId, iconSize, false);
                        float impactAlpha = getImpactAlpha(nowMs);
                        if (impactAlpha > 0f) {
                            drawImpactFlash(g, columnXs[col] + iconPadX, columnBaseY, iconSize, impactAlpha);
                        }
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
        } else {
            renderSelectionButtons(g, columnXs, slotTopY, slotWidth, slotHeight, iconSize, iconPadX, layoutScale);
        }

        g.setClip(oldClip);

        if (!clickableSelection) {
            final int centerSlotY = Math.round(iconsTopYF + centerIndex * activeStep);
            for (int col = 0; col < columnCount; col++) {
                int iconX = columnXs[col] + iconPadX;
                float alpha = highlightPhase ? 0.65f : 0.45f;
                drawCenterMarker(g, iconX, centerSlotY, iconSize, alpha);
            }
        }

        if (clickableSelection) {
            return new Dimension(totalWidth, slotHeight);
        }
        return null;
    }

    private Dimension renderResolveAnimation(Graphics2D g, long nowMs) {
        List<Integer> optionsSnapshot = resolveOptions == null
                ? Collections.emptyList()
                : new ArrayList<>(resolveOptions);
        if (optionsSnapshot.isEmpty() || resolveSelectedIndex < 0) {
            resolveAnimating = false;
            isAnimating = false;
            return null;
        }
        int selectedIndex = Math.min(Math.max(resolveSelectedIndex, 0), optionsSnapshot.size() - 1);
        long elapsed = nowMs - resolveStartMs;
        long total = RESOLVE_DURATION_MS + RESOLVE_HOLD_MS;
        if (elapsed >= total) {
            resolveAnimating = false;
            isAnimating = false;
            return null;
        }
        float t = Math.min(1f, elapsed / (float) RESOLVE_DURATION_MS);
        boolean holdPhase = elapsed >= RESOLVE_DURATION_MS;
        float selectT = easeOutCubic(t);
        float fallT = easeInCubic(t);

        final int vpX = client.getViewportXOffset();
        final int vpY = client.getViewportYOffset();
        final int vpWidth = client.getViewportWidth();
        final int vpHeight = client.getViewportHeight();
        final int centerX = vpX + (vpWidth / 2);
        final float layoutScale = getResponsiveLayoutScale(vpHeight);
        final float slotScale = CHOICE_SLOT_SCALE * layoutScale;

        final int localColumnCount = Math.max(2, Math.min(5, optionsSnapshot.size()));
        final int baseSlotWidth = ICON_W + SLOT_PADDING_X * 2;
        final int baseSlotHeight = ICON_H + SLOT_PADDING_Y * 2;
        final int rollingVisibleItems = Math.max(2, VISIBLE_SELECTION_ITEM_COUNT);
        final float iconTargetSize = SCROLL_ICON_TARGET * layoutScale;
        final int rollingContentSpan = Math.round(
                iconTargetSize * rollingVisibleItems
                        + SCROLL_ITEM_GAP * (rollingVisibleItems - 1));
        final int scaledMinSlotWidth = scaleLayoutValue(MIN_CHOICE_SLOT_WIDTH, layoutScale, baseSlotWidth);
        final int slotWidth = Math.max(Math.round(baseSlotWidth * slotScale), scaledMinSlotWidth);
        final int scaledMinSlotHeight = scaleLayoutValue(MIN_CHOICE_SLOT_HEIGHT, layoutScale, baseSlotHeight);
        final int scaledSlotHeight = Math.max(Math.round(baseSlotHeight * slotScale), scaledMinSlotHeight);
        final int slotHeight = Math.max(scaledSlotHeight, rollingContentSpan + SLOT_PADDING_Y * 2);
        final int spacing = Math.max(scaleLayoutValue(14, layoutScale, 10), Math.round(COLUMN_SPACING * slotScale));
        final int totalWidth = localColumnCount * slotWidth + (localColumnCount - 1) * spacing;
        final int slotsLeftX = centerX - (totalWidth / 2);
        final int selectionTopMargin = scaleLayoutValue(SELECTION_TOP_MARGIN, layoutScale, 8);
        final int choiceButtonYOffset = scaleLayoutValue(CHOICE_BUTTON_Y_OFFSET, layoutScale, 6);
        final int slotTopY = vpY + selectionTopMargin + choiceButtonYOffset;

        final float scrollGap = SCROLL_ITEM_GAP;
        final float maxIconWidth = slotWidth - SLOT_PADDING_X * 2f;
        final float maxIconHeight = (slotHeight - SLOT_PADDING_Y * 2f - (rollingVisibleItems - 1) * scrollGap)
                / rollingVisibleItems;
        final float targetIconSize = Math.max(ICON_W, iconTargetSize);
        final float rollingIconSize = Math.max(ICON_W, Math.min(targetIconSize, Math.min(maxIconWidth, maxIconHeight)));
        final int iconSize = Math.max(1, Math.round(rollingIconSize));
        final int iconPadX = Math.max(SLOT_PADDING_X, (slotWidth - iconSize) / 2);
        final int[] columnXs = new int[localColumnCount];
        for (int col = 0; col < localColumnCount; col++) {
            columnXs[col] = slotsLeftX + col * (slotWidth + spacing);
        }

        List<Rectangle> buttonRects = new ArrayList<>(localColumnCount);
        List<Rectangle> iconRects = new ArrayList<>(localColumnCount);
        final int insetX = scaleLayoutValue(CHOICE_BUTTON_INSET, layoutScale, 4);
        final int insetY = scaleLayoutValue(CHOICE_BUTTON_INSET, layoutScale, 4);
        final int iconTopPadding = scaleLayoutValue(CHOICE_ICON_TOP_PADDING, layoutScale, 4);
        final int labelReservedHeight = scaleLayoutValue(CHOICE_LABEL_RESERVED_HEIGHT, layoutScale, 20);
        final int slotBottom = slotTopY + slotHeight - insetY;
        final int baseIconSize = iconSize;
        for (int i = 0; i < localColumnCount; i++) {
            final int buttonTop = slotTopY + insetY;
            final int buttonBottom = slotBottom;
            int x = columnXs[i] + insetX;
            int y = buttonTop;
            int width = Math.max(1, slotWidth - insetX * 2);
            int height = Math.max(1, buttonBottom - buttonTop);
            buttonRects.add(new Rectangle(x, y, width, height));

            int iconAreaTop = buttonTop + iconTopPadding;
            int iconAreaBottom = buttonBottom - labelReservedHeight;
            if (iconAreaBottom <= iconAreaTop + 4) {
                iconAreaBottom = buttonBottom - scaleLayoutValue(6, layoutScale, 4);
            }
            int iconAreaHeight = Math.max(1, iconAreaBottom - iconAreaTop);
            int choiceIconSize = Math.max(ICON_W, Math.min(baseIconSize, iconAreaHeight));
            int iconY = iconAreaTop + Math.max(0, (iconAreaHeight - choiceIconSize) / 2);
            int choiceIconPadX = Math.max(SLOT_PADDING_X, (slotWidth - choiceIconSize) / 2);
            final int iconX = columnXs[i] + choiceIconPadX;
            iconRects.add(new Rectangle(iconX, iconY, choiceIconSize, choiceIconSize));
        }

        synchronized (columnHitboxes) {
            columnHitboxes.clear();
        }

        final float targetCx = vpX + vpWidth / 2f;
        for (int i = 0; i < optionsSnapshot.size() && i < buttonRects.size(); i++) {
            Rectangle baseRect = buttonRects.get(i);
            Rectangle baseIcon = iconRects.get(i);
            float rectCx = baseRect.x + baseRect.width / 2f;
            float rectCy = baseRect.y + baseRect.height / 2f;
            float iconCx = baseIcon.x + baseIcon.width / 2f;
            float iconCy = baseIcon.y + baseIcon.height / 2f;
            float iconOffsetX = iconCx - rectCx;
            float iconOffsetY = iconCy - rectCy;

            float alpha = 1f;
            float scale = 1f;
            float drawCx = rectCx;
            float drawCy = rectCy;
            boolean hovered = false;
            float intensity = 0.75f;
            float dimAlpha = 0f;

            if (i == selectedIndex && optionsSnapshot.get(i) == resolveSelectedItemId) {
                drawCx = lerp(rectCx, targetCx, selectT);
                drawCy = rectCy;
                scale = lerp(1f, RESOLVE_SELECTED_SCALE, selectT);
                hovered = true;
                intensity = 0.95f;
            } else {
                if (holdPhase) {
                    continue;
                }
                float drop = (vpHeight * RESOLVE_FALL_DISTANCE + baseRect.height) * fallT;
                float drift = (i - selectedIndex) * 10f * fallT;
                float bumpPhase = Math.min(1f, t * 2f);
                float bump = (float) Math.sin(bumpPhase * Math.PI) * (baseRect.height * 0.5f);
                drawCx = rectCx + drift;
                drawCy = rectCy - bump + drop;
                scale = lerp(1f, RESOLVE_NON_SELECTED_SCALE, fallT);
                float fadeT = (float) Math.pow(t, 0.6f);
                alpha = Math.max(0f, 1f - fadeT);
                intensity = 0.35f;
                dimAlpha = 0.2f + 0.15f * fallT;
            }

            Rectangle drawRect = scaleRect(baseRect, scale, drawCx, drawCy);
            Rectangle drawIcon = scaleRect(
                    baseIcon,
                    scale,
                    drawCx + iconOffsetX * scale,
                    drawCy + iconOffsetY * scale);

            Composite oldComposite = g.getComposite();
            if (alpha < 1f) {
                g.setComposite(AlphaComposite.SrcOver.derive(alpha));
            }
            drawChoiceButton(g, drawRect, drawIcon, optionsSnapshot.get(i), hovered, true, intensity, dimAlpha);
            g.setComposite(oldComposite);
        }

        return new Dimension(totalWidth, slotHeight);
    }

    private float easeOutCubic(float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        float inv = 1f - clamped;
        return 1f - inv * inv * inv;
    }

    private float easeInCubic(float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        return clamped * clamped * clamped;
    }

    private float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private Rectangle scaleRect(Rectangle base, float scale, float centerX, float centerY) {
        int w = Math.max(1, Math.round(base.width * scale));
        int h = Math.max(1, Math.round(base.height * scale));
        int x = Math.round(centerX - w / 2f);
        int y = Math.round(centerY - h / 2f);
        return new Rectangle(x, y, w, h);
    }

    private void renderSelectionButtons(
            Graphics2D g,
            int[] columnXs,
            int topY,
            int slotWidth,
            int slotHeight,
            int iconSize,
            int iconPadX,
            float layoutScale) {
        List<Integer> optionsSnapshot = currentOptions == null
                ? Collections.emptyList()
                : new ArrayList<>(currentOptions);
        final int availableSlots = columnXs == null ? 0 : columnXs.length;
        final int drawCount = Math.min(optionsSnapshot.size(), availableSlots);
        if (drawCount <= 0) {
            synchronized (columnHitboxes) {
                columnHitboxes.clear();
            }
            return;
        }

        List<Rectangle> buttonRects = new ArrayList<>(drawCount);
        List<Rectangle> hitboxRects = new ArrayList<>(drawCount);
        List<Rectangle> iconRects = new ArrayList<>(drawCount);
        final int insetX = scaleLayoutValue(CHOICE_BUTTON_INSET, layoutScale, 4);
        final int insetY = scaleLayoutValue(CHOICE_BUTTON_INSET, layoutScale, 4);
        final int iconTopPadding = scaleLayoutValue(CHOICE_ICON_TOP_PADDING, layoutScale, 4);
        final int labelReservedHeight = scaleLayoutValue(CHOICE_LABEL_RESERVED_HEIGHT, layoutScale, 20);
        final int slotBottom = topY + slotHeight - insetY;
        final int baseIconSize = iconSize;
        for (int i = 0; i < drawCount; i++) {
            final int buttonTop = topY + insetY;
            final int buttonBottom = slotBottom;
            int x = columnXs[i] + insetX;
            int y = buttonTop;
            int width = Math.max(1, slotWidth - insetX * 2);
            int height = Math.max(1, buttonBottom - buttonTop);

            Rectangle drawRect = new Rectangle(x, y, width, height);
            buttonRects.add(drawRect);

            int iconAreaTop = buttonTop + iconTopPadding;
            int iconAreaBottom = buttonBottom - labelReservedHeight;
            if (iconAreaBottom <= iconAreaTop + 4) {
                iconAreaBottom = buttonBottom - scaleLayoutValue(6, layoutScale, 4);
            }
            int iconAreaHeight = Math.max(1, iconAreaBottom - iconAreaTop);
            int choiceIconSize = Math.max(ICON_W, Math.min(baseIconSize, iconAreaHeight));
            int iconY = iconAreaTop + Math.max(0, (iconAreaHeight - choiceIconSize) / 2);
            int choiceIconPadX = Math.max(SLOT_PADDING_X, (slotWidth - choiceIconSize) / 2);
            final int iconX = columnXs[i] + choiceIconPadX;
            Rectangle iconRect = new Rectangle(iconX, iconY, choiceIconSize, choiceIconSize);
            iconRects.add(iconRect);

            hitboxRects.add(drawRect);
        }

        Point mouse = client.getMouseCanvasPosition();
        int hoveredIndex = -1;
        if (mouse != null) {
            for (int i = 0; i < buttonRects.size(); i++) {
                Rectangle rect = buttonRects.get(i);
                if (rect.contains(new java.awt.Point(mouse.getX(), mouse.getY()))) {
                    hoveredIndex = i;
                    break;
                }
            }
        }

        synchronized (columnHitboxes) {
            columnHitboxes.clear();
            columnHitboxes.addAll(hitboxRects);
        }

        final long nowMs = System.currentTimeMillis();
        final float revealPulse = getSelectionPulseAlpha(nowMs);

        for (int i = 0; i < drawCount; i++) {
            boolean hovered = (i == hoveredIndex);
            boolean animate = selectionPending || hovered;
            float intensity = hovered ? 1.0f : 0.55f + revealPulse;
            float dimAlpha = (selectionPending && !hovered) ? 0.10f : 0f;
            drawChoiceButton(
                    g,
                    buttonRects.get(i),
                    iconRects.get(i),
                    optionsSnapshot.get(i),
                    hovered,
                    animate,
                    intensity,
                    dimAlpha);
        }

        if (hoveredIndex >= 0 && hoveredIndex < drawCount && mouse != null) {
            String hoverText = buildHoverText(optionsSnapshot.get(hoveredIndex));
            if (hoverText != null && !hoverText.trim().isEmpty()) {
                drawChoiceHoverTooltip(g, hoverText, mouse, buttonRects.get(hoveredIndex));
            }
        }
    }

    private void drawChoiceButton(
            Graphics2D g,
            Rectangle rect,
            Rectangle iconRect,
            int itemId,
            boolean hovered,
            boolean animate,
            float intensity,
            float dimAlpha) {
        Paint previousPaint = g.getPaint();
        Stroke previousStroke = g.getStroke();
        Composite previousComposite = g.getComposite();

        Rectangle2D.Float buttonShape = new Rectangle2D.Float(
                rect.x,
                rect.y,
                rect.width,
                rect.height);

        Rectangle2D.Float shadowShape = new Rectangle2D.Float(
                rect.x + 1,
                rect.y + 3,
                rect.width,
                rect.height);
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
                bottom);
        g.setPaint(paint);
        g.fill(buttonShape);

        Composite oldComposite = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(0.28f));
        g.setColor(new Color(238, 230, 210, 150));
        g.drawLine(rect.x + 2, rect.y + 2, rect.x + rect.width - 3, rect.y + 2);
        g.setColor(new Color(20, 18, 14, 120));
        g.drawLine(rect.x + 2, rect.y + rect.height - 3, rect.x + rect.width - 3, rect.y + rect.height - 3);
        g.setComposite(AlphaComposite.SrcOver.derive(0.4f));
        g.setColor(CHOICE_BUTTON_BORDER_INNER);
        g.setStroke(new BasicStroke(1.6f));
        g.drawRect(rect.x + 1, rect.y + 1, rect.width - 2, rect.height - 2);
        int corner = Math.max(6, Math.round(Math.min(rect.width, rect.height) * 0.18f));
        int bracketPad = Math.max(2, Math.round(Math.min(rect.width, rect.height) * 0.03f));
        g.setComposite(AlphaComposite.SrcOver.derive(0.6f));
        g.setStroke(new BasicStroke(4.2f));
        g.setColor(CORNER_SHADOW);
        drawRectBracket(g, rect.x - bracketPad, rect.y - bracketPad, rect.width + bracketPad * 2,
                rect.height + bracketPad * 2, corner + bracketPad);
        g.setComposite(AlphaComposite.SrcOver.derive(0.9f));
        g.setStroke(new BasicStroke(3.2f));
        g.setColor(CORNER_HIGHLIGHT);
        drawRectBracket(g, rect.x - bracketPad, rect.y - bracketPad, rect.width + bracketPad * 2,
                rect.height + bracketPad * 2, corner + bracketPad);
        g.setComposite(oldComposite);

        if (animate) {
            drawPulse(g, buttonShape, rect, intensity);
        }
        if (hovered) {
            drawShimmer(g, buttonShape, rect, intensity);
        }

        if (dimAlpha > 0f) {
            Composite dimOldComposite = g.getComposite();
            Shape oldClip = g.getClip();
            g.setClip(buttonShape);
            g.setComposite(AlphaComposite.SrcOver.derive(Math.min(0.35f, dimAlpha)));
            g.setColor(new Color(0, 0, 0, 220));
            g.fillRect(rect.x, rect.y, rect.width, rect.height);
            g.setClip(oldClip);
            g.setComposite(dimOldComposite);
        }

        g.setColor(hovered ? CHOICE_BUTTON_BORDER_HOVER : CHOICE_BUTTON_BORDER);
        g.setStroke(new BasicStroke(hovered ? 3f : 2f));
        g.draw(buttonShape);

        g.setPaint(previousPaint);
        g.setStroke(previousStroke);
        g.setComposite(previousComposite);

        final int iconSize = iconRect.width;
        final int iconX = iconRect.x;
        final int iconY = iconRect.y;
        BufferedImage icon = itemManager.getImage(itemId, 1, false);
        if (icon != null) {
            Object oldInterpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            Object oldAntialias = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.drawImage(icon, iconX, iconY, iconSize, iconSize, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAntialias);
        }

        drawMarkerBadge(g, rect, itemId);
        drawChoiceLabel(g, rect, iconRect, itemId);

    }

    private void drawMarkerBadge(Graphics2D g, Rectangle buttonRect, int itemId) {
        if (config == null || !config.includeUntradeable()) {
            return;
        }
        boolean tradeable = isTradeableItem(itemId);
        int size = Math.max(8, Math.round(Math.min(buttonRect.width, buttonRect.height) * 0.18f));
        int inset = Math.max(6, Math.round(size * 0.6f));
        int x = buttonRect.x + buttonRect.width - size - inset;
        int y = buttonRect.y + inset;

        Composite oldComposite = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(0.85f));
        g.setColor(new Color(0, 0, 0, 130));
        if (tradeable) {
            g.fillOval(x + 1, y + 1, size, size);
        } else {
            Polygon shadow = new Polygon(
                    new int[] { x + size / 2 + 1, x + size + 1, x + size / 2 + 1, x + 1 },
                    new int[] { y + 1, y + size / 2 + 1, y + size + 1, y + size / 2 + 1 },
                    4);
            g.fillPolygon(shadow);
        }

        g.setComposite(AlphaComposite.SrcOver.derive(0.95f));
        Color accent = tradeable ? TRADEABLE_GLOW_INNER : UNTRADEABLE_GLOW_INNER;
        g.setColor(accent);
        if (tradeable) {
            g.fillOval(x, y, size, size);
        } else {
            Polygon diamond = new Polygon(
                    new int[] { x + size / 2, x + size, x + size / 2, x },
                    new int[] { y, y + size / 2, y + size, y + size / 2 },
                    4);
            g.fillPolygon(diamond);
        }

        g.setComposite(AlphaComposite.SrcOver.derive(0.9f));
        g.setColor(new Color(0, 0, 0, 110));
        if (tradeable) {
            g.drawOval(x, y, size, size);
        } else {
            Polygon outline = new Polygon(
                    new int[] { x + size / 2, x + size, x + size / 2, x },
                    new int[] { y, y + size / 2, y + size, y + size / 2 },
                    4);
            g.drawPolygon(outline);
        }
        g.setComposite(oldComposite);
    }

    private void drawPulse(Graphics2D g, Shape clipShape, Rectangle rect, float intensity) {
        float alpha = getPulseAlpha() * Math.max(0.2f, Math.min(1f, intensity));
        if (alpha <= 0f) {
            return;
        }
        Composite oldComposite = g.getComposite();
        Shape oldClip = g.getClip();
        Stroke oldStroke = g.getStroke();
        g.setClip(clipShape);
        g.setComposite(AlphaComposite.SrcOver.derive(alpha));
        float strokeWidth = 1.8f + 1.4f * Math.max(0f, Math.min(1f, intensity));
        g.setStroke(new BasicStroke(strokeWidth));
        g.setColor(SLOT_BORDER);
        g.draw(new Rectangle2D.Float(
                rect.x + 1,
                rect.y + 1,
                rect.width - 2,
                rect.height - 2));
        g.setClip(oldClip);
        g.setComposite(oldComposite);
        g.setStroke(oldStroke);
    }

    private void drawShimmer(Graphics2D g, Shape clipShape, Rectangle rect, float intensity) {
        float alpha = SHIMMER_ALPHA * Math.max(0.2f, Math.min(1f, intensity));
        if (alpha <= 0f) {
            return;
        }
        long now = System.nanoTime();
        float phase = (now % SHIMMER_PERIOD_NS) / (float) SHIMMER_PERIOD_NS;
        float shimmerWidth = Math.max(18f, rect.width * SHIMMER_WIDTH_RATIO);
        float travel = rect.width + rect.height + shimmerWidth;
        float start = rect.x - rect.height - shimmerWidth + travel * phase;
        float end = start + shimmerWidth;

        Point2D.Float p1 = new Point2D.Float(start, rect.y);
        Point2D.Float p2 = new Point2D.Float(end, rect.y + rect.height);
        LinearGradientPaint shimmerPaint = new LinearGradientPaint(
                p1,
                p2,
                new float[] { 0f, 0.5f, 1f },
                new Color[] {
                        new Color(230, 210, 150, 0),
                        new Color(230, 210, 150, 180),
                        new Color(230, 210, 150, 0)
                });

        Composite oldComposite = g.getComposite();
        Shape oldClip = g.getClip();
        g.setClip(clipShape);
        g.setComposite(AlphaComposite.SrcOver.derive(alpha));
        g.setPaint(shimmerPaint);
        g.fillRect(rect.x, rect.y, rect.width, rect.height);
        g.setClip(oldClip);
        g.setComposite(oldComposite);
    }

    private float getSelectionPulseAlpha(long nowMs) {
        if (!selectionPending || selectionStartMs <= 0L) {
            return 0f;
        }
        long elapsed = nowMs - selectionStartMs;
        if (elapsed < 0 || elapsed > SELECTION_REVEAL_MS) {
            return 0f;
        }
        float t = elapsed / (float) SELECTION_REVEAL_MS;
        float wave = 0.5f + 0.5f * (float) Math.sin(t * Math.PI * 2.0);
        return 0.18f * (1f - t) * wave;
    }

    private float getImpactAlpha(long nowMs) {
        if (rollDurationMs <= 0) {
            return 0f;
        }
        long impactStart = rollStartMs + rollDurationMs;
        long elapsed = nowMs - impactStart;
        if (elapsed < 0 || elapsed > IMPACT_FLASH_MS) {
            return 0f;
        }
        float t = elapsed / (float) IMPACT_FLASH_MS;
        float easeOut = 1f - (float) Math.pow(t, 2);
        return 0.35f * easeOut;
    }

    private void drawImpactFlash(Graphics2D g, int x, int y, int size, float alpha) {
        if (alpha <= 0f) {
            return;
        }
        float radius = size * 0.9f;
        float cx = x + size / 2f;
        float cy = y + size / 2f;
        RadialGradientPaint glow = new RadialGradientPaint(
                new Point2D.Float(cx, cy),
                radius,
                new float[] { 0f, 0.6f, 1f },
                new Color[] {
                        new Color(255, 235, 170, Math.round(180 * alpha)),
                        new Color(255, 210, 120, Math.round(120 * alpha)),
                        new Color(255, 200, 90, 0)
                });
        Composite oldComposite = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(Math.min(1f, alpha)));
        Paint oldPaint = g.getPaint();
        g.setPaint(glow);
        g.fill(new Ellipse2D.Float(cx - radius, cy - radius, radius * 2f, radius * 2f));
        g.setPaint(oldPaint);
        g.setComposite(oldComposite);
    }

    private void drawIconFrame(Graphics2D g, int x, int y, int size) {
        Paint oldPaint = g.getPaint();
        g.setPaint(new LinearGradientPaint(
                new Point2D.Float(x, y),
                new Point2D.Float(x, y + size),
                new float[] { 0f, 1f },
                new Color[] { SLOT_FILL_TOP, SLOT_FILL_BOTTOM }));
        g.fillRect(x, y, size, size);

        g.setColor(SLOT_BORDER);
        g.drawRect(x, y, size - 1, size - 1);
        if (size > 4) {
            g.setColor(new Color(20, 16, 10, 170));
            g.drawRect(x + 1, y + 1, size - 3, size - 3);
            g.setColor(new Color(220, 188, 135, 70));
            g.drawLine(x + 1, y + 1, x + size - 3, y + 1);
        }
        g.setPaint(oldPaint);
    }

    private void drawCenterMarker(Graphics2D g, int iconX, int iconY, int iconSize, float alpha) {
        int pad = Math.max(3, Math.round(iconSize * 0.12f));
        int x = iconX - pad;
        int w = iconSize + pad * 2;
        int yTop = iconY - pad;
        int yBottom = iconY + iconSize + pad;

        Composite oldComposite = g.getComposite();
        Stroke oldStroke = g.getStroke();
        g.setComposite(AlphaComposite.SrcOver.derive(Math.max(0.15f, Math.min(1f, alpha))));
        g.setStroke(new BasicStroke(2f));

        g.setColor(CENTER_MARKER_SHADOW);
        g.drawLine(x + 1, yTop + 1, x + w - 1, yTop + 1);
        g.drawLine(x + 1, yBottom + 1, x + w - 1, yBottom + 1);

        g.setColor(CENTER_MARKER);
        g.drawLine(x, yTop, x + w, yTop);
        g.drawLine(x, yBottom, x + w, yBottom);

        g.setStroke(oldStroke);
        g.setComposite(oldComposite);
    }

    private void drawRectBracket(Graphics2D g, int x, int y, int w, int h, int corner) {
        // top-left
        g.drawLine(x, y, x + corner, y);
        g.drawLine(x, y, x, y + corner);
        // top-right
        g.drawLine(x + w - corner, y, x + w, y);
        g.drawLine(x + w, y, x + w, y + corner);
        // bottom-left
        g.drawLine(x, y + h - corner, x, y + h);
        g.drawLine(x, y + h, x + corner, y + h);
        // bottom-right
        g.drawLine(x + w - corner, y + h, x + w, y + h);
        g.drawLine(x + w, y + h - corner, x + w, y + h);
    }

    private float getPulseAlpha() {
        long now = System.nanoTime();
        float phase = (now % PULSE_PERIOD_NS) / (float) PULSE_PERIOD_NS;
        float wave = 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2.0);
        return PULSE_MIN_ALPHA + (PULSE_MAX_ALPHA - PULSE_MIN_ALPHA) * wave;
    }

    private Color blendColors(Color base, Color accent, float mix) {
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

    private void drawIconGlow(Graphics2D g, Rectangle iconRect, int itemId, boolean hovered) {
        Color[] palette = getHighlightPalette(itemId);
        Color accent = palette[0];
        float alpha = hovered ? 0.85f : 0.6f;
        Composite oldComposite = g.getComposite();
        Stroke oldStroke = g.getStroke();
        g.setComposite(AlphaComposite.SrcOver.derive(alpha));
        g.setColor(accent);
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(
                iconRect.x - 1,
                iconRect.y - 1,
                iconRect.width + 2,
                iconRect.height + 2,
                6,
                6);
        boolean tradeable = isTradeableItem(itemId);
        int badge = Math.max(6, Math.round(iconRect.width * 0.22f));
        int badgeX = iconRect.x + iconRect.width - badge - 2;
        int badgeY = iconRect.y + 2;

        g.setComposite(AlphaComposite.SrcOver.derive(0.8f));
        g.setColor(new Color(0, 0, 0, 120));
        if (tradeable) {
            g.fillOval(badgeX + 1, badgeY + 1, badge, badge);
        } else {
            Polygon shadow = new Polygon(
                    new int[] { badgeX + badge / 2 + 1, badgeX + badge + 1, badgeX + badge / 2 + 1, badgeX + 1 },
                    new int[] { badgeY + 1, badgeY + badge / 2 + 1, badgeY + badge + 1, badgeY + badge / 2 + 1 },
                    4);
            g.fillPolygon(shadow);
        }

        g.setComposite(AlphaComposite.SrcOver.derive(0.95f));
        g.setColor(accent);
        if (tradeable) {
            g.fillOval(badgeX, badgeY, badge, badge);
        } else {
            Polygon diamond = new Polygon(
                    new int[] { badgeX + badge / 2, badgeX + badge, badgeX + badge / 2, badgeX },
                    new int[] { badgeY, badgeY + badge / 2, badgeY + badge, badgeY + badge / 2 },
                    4);
            g.fillPolygon(diamond);
        }

        g.setComposite(AlphaComposite.SrcOver.derive(0.9f));
        g.setStroke(new BasicStroke(1.1f));
        g.setColor(new Color(0, 0, 0, 110));
        if (tradeable) {
            g.drawOval(badgeX, badgeY, badge, badge);
        } else {
            Polygon outline = new Polygon(
                    new int[] { badgeX + badge / 2, badgeX + badge, badgeX + badge / 2, badgeX },
                    new int[] { badgeY, badgeY + badge / 2, badgeY + badge, badgeY + badge / 2 },
                    4);
            g.drawPolygon(outline);
        }
        g.setStroke(oldStroke);
        g.setComposite(oldComposite);
    }

    private void normalizeOnce(float step) {
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

    private int nextUniqueRollItem() {
        if (randomLockedItemSupplier == null) {
            return 0;
        }

        final int maxAttempts = Math.max(10, columnCount * DRAW_COUNT);
        int fallback = 0;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Integer rolled = randomLockedItemSupplier.get();
            int candidate = rolled != null ? rolled : 0;
            fallback = candidate;
            if (candidate == 0) {
                break;
            }
            if (uniqueRollItems.add(candidate)) {
                return candidate;
            }
        }

        if (fallback != 0) {
            uniqueRollItems.add(fallback);
        }
        return fallback;
    }

    private float normalizeStep(float value, float step) {
        if (step == 0f) {
            return 0f;
        }
        float adjusted = value % step;
        if (adjusted < 0) {
            adjusted += step;
        }
        return adjusted;
    }

    private float getResponsiveLayoutScale(int viewportHeight) {
        if (viewportHeight <= 0) {
            return 1f;
        }
        float scale = viewportHeight / RESPONSIVE_BASE_VIEWPORT_HEIGHT;
        if (scale < RESPONSIVE_MIN_SCALE) {
            return RESPONSIVE_MIN_SCALE;
        }
        if (scale > RESPONSIVE_MAX_SCALE) {
            return RESPONSIVE_MAX_SCALE;
        }
        return scale;
    }

    private int scaleLayoutValue(int value, float scale, int min) {
        return Math.max(min, Math.round(value * scale));
    }

    private void drawSlotWindow(Graphics2D g, int x, int y, int width, int height, float scale) {
        Rectangle2D.Float frame = new Rectangle2D.Float(x, y, width, height);
        GradientPaint paint = new GradientPaint(
                x,
                y,
                SLOT_FILL_TOP,
                x,
                y + height,
                SLOT_FILL_BOTTOM);
        Paint oldPaint = g.getPaint();
        Stroke oldStroke = g.getStroke();
        Composite oldComposite = g.getComposite();
        g.setPaint(paint);
        g.fill(frame);

        // inner glossy stroke
        Rectangle2D.Float inner = new Rectangle2D.Float(
                x + 3,
                y + 3,
                width - 6,
                height - 6);
        g.setPaint(scale > 1f ? SLOT_HIGHLIGHT : new Color(236, 222, 180, 45));
        g.setStroke(new BasicStroke(2f));
        g.draw(inner);

        g.setComposite(AlphaComposite.SrcOver.derive(0.26f));
        g.setColor(new Color(236, 226, 204, 150));
        g.drawLine(x + 2, y + 2, x + width - 3, y + 2);
        g.setColor(new Color(18, 16, 12, 120));
        g.drawLine(x + 2, y + height - 3, x + width - 3, y + height - 3);
        g.setComposite(oldComposite);

        g.setPaint(SLOT_BORDER);
        g.setStroke(new BasicStroke(scale > 1f ? 3f : 2f));
        g.draw(frame);

        int corner = Math.max(6, Math.round(Math.min(width, height) * 0.12f));
        int bracketPad = Math.max(2, Math.round(Math.min(width, height) * 0.03f));
        g.setComposite(AlphaComposite.SrcOver.derive(scale > 1f ? 0.6f : 0.45f));
        g.setStroke(new BasicStroke(4.2f));
        g.setColor(CORNER_SHADOW);
        drawRectBracket(g, x - bracketPad, y - bracketPad, width + bracketPad * 2, height + bracketPad * 2,
                corner + bracketPad);
        g.setComposite(AlphaComposite.SrcOver.derive(scale > 1f ? 0.9f : 0.75f));
        g.setStroke(new BasicStroke(3.2f));
        g.setColor(CORNER_HIGHLIGHT);
        drawRectBracket(g, x - bracketPad, y - bracketPad, width + bracketPad * 2, height + bracketPad * 2,
                corner + bracketPad);
        g.setComposite(oldComposite);

        g.setPaint(oldPaint);
        g.setStroke(oldStroke);
    }

    private void drawChoiceLabel(Graphics2D g, Rectangle rect, Rectangle iconRect, int itemId) {
        String label = buildHoverText(itemId);
        if (label == null || label.isEmpty()) {
            return;
        }
        Font oldFont = g.getFont();
        int paddingX = 8;
        int paddingY = 6;
        int labelTopGap = 6;
        int maxWidth = Math.max(12, rect.width - paddingX * 2);
        int availableTop = iconRect.y + iconRect.height + labelTopGap;
        int availableBottom = rect.y + rect.height - paddingY;
        int availableHeight = Math.max(0, availableBottom - availableTop);
        if (availableHeight <= 0) {
            g.setFont(oldFont);
            return;
        }

        List<String> lines = Collections.emptyList();
        FontMetrics fm = null;
        Font labelFont = LABEL_FONT;
        for (int size = LABEL_FONT.getSize(); size >= MIN_LABEL_FONT_SIZE; size--) {
            labelFont = LABEL_FONT.deriveFont((float) size);
            g.setFont(labelFont);
            fm = g.getFontMetrics();
            lines = wrapToTwoLines(label, fm, maxWidth);
            if (lines.isEmpty()) {
                continue;
            }
            int lineHeight = fm.getHeight();
            int maxLinesThatFit = Math.max(1, availableHeight / Math.max(1, lineHeight));
            if (lines.size() > maxLinesThatFit) {
                lines = Collections.singletonList(TextFitUtil.elideToWidth(label, fm, maxWidth));
            }
            int textBlockHeight = lineHeight * lines.size();
            if (textBlockHeight <= availableHeight || size == MIN_LABEL_FONT_SIZE) {
                break;
            }
        }
        int lineCount = lines.size();
        if (lineCount == 0 || fm == null) {
            g.setFont(oldFont);
            return;
        }

        int lineHeight = fm.getHeight();
        if (lineHeight > availableHeight) {
            g.setFont(oldFont);
            return;
        }
        int startY = availableTop + fm.getAscent();

        for (int i = 0; i < lineCount; i++) {
            String line = lines.get(i);
            int textWidth = fm.stringWidth(line);
            int x = rect.x + Math.max(paddingX, (rect.width - textWidth) / 2);
            int y = startY + i * lineHeight;
            if (y > availableBottom) {
                break;
            }
            g.setColor(new Color(0, 0, 0, 140));
            g.drawString(line, x + 1, y + 1);
            g.setColor(TOOLTIP_TEXT);
            g.drawString(line, x, y);
        }
        g.setFont(oldFont);
    }

    private void drawChoiceHoverTooltip(Graphics2D g, String text, Point mouse, Rectangle anchorRect) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        Font oldFont = g.getFont();
        Color oldColor = g.getColor();
        Composite oldComposite = g.getComposite();

        g.setFont(HOVER_TOOLTIP_FONT);
        FontMetrics fm = g.getFontMetrics();
        if (fm == null) {
            g.setFont(oldFont);
            return;
        }

        Rectangle clip = g.getClipBounds();
        if (clip == null || clip.width <= 0 || clip.height <= 0) {
            clip = new Rectangle(
                    client.getViewportXOffset(),
                    client.getViewportYOffset(),
                    Math.max(1, client.getViewportWidth()),
                    Math.max(1, client.getViewportHeight()));
        }

        final int paddingX = 6;
        final int paddingY = 5;
        final int lineGap = 2;
        final int tooltipMaxWidth = Math.max(80, Math.min(320, clip.width - 16));
        final int contentMaxWidth = Math.max(12, tooltipMaxWidth - paddingX * 2);

        List<String> lines = TextFitUtil.wrapToWidth(trimmed, fm, contentMaxWidth);
        if (lines.isEmpty()) {
            g.setFont(oldFont);
            g.setColor(oldColor);
            g.setComposite(oldComposite);
            return;
        }

        int lineHeight = fm.getHeight();
        int maxLinesByHeight = Math.max(1, (clip.height - 12 - paddingY * 2 + lineGap) / (lineHeight + lineGap));
        if (lines.size() > maxLinesByHeight) {
            List<String> limited = new ArrayList<>(lines.subList(0, maxLinesByHeight));
            int last = limited.size() - 1;
            limited.set(last, TextFitUtil.elideToWidth(limited.get(last), fm, contentMaxWidth));
            lines = limited;
        }

        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, fm.stringWidth(line));
        }
        int boxWidth = Math.min(tooltipMaxWidth, textWidth + paddingX * 2);
        int boxHeight = paddingY * 2 + lines.size() * lineHeight + (Math.max(0, lines.size() - 1) * lineGap);

        int x = mouse.getX() + 12;
        int y = mouse.getY() - boxHeight - 10;

        int minX = clip.x + 4;
        int maxX = clip.x + clip.width - boxWidth - 4;
        if (maxX >= minX) {
            x = Math.max(minX, Math.min(x, maxX));
        } else {
            x = clip.x;
        }

        int minY = clip.y + 4;
        int maxY = clip.y + clip.height - boxHeight - 4;
        if (y < minY) {
            int belowAnchor = anchorRect.y + anchorRect.height + 8;
            int belowCursor = mouse.getY() + 12;
            y = Math.max(belowAnchor, belowCursor);
        }
        if (maxY >= minY) {
            y = Math.max(minY, Math.min(y, maxY));
        } else {
            y = clip.y;
        }

        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(HOVER_TOOLTIP_BG);
        g.fillRoundRect(x, y, boxWidth, boxHeight, 6, 6);
        g.setColor(HOVER_TOOLTIP_BORDER);
        g.drawRoundRect(x, y, boxWidth, boxHeight, 6, 6);

        int textX = x + paddingX;
        int textY = y + paddingY + fm.getAscent();
        for (String line : lines) {
            g.setColor(new Color(0, 0, 0, 150));
            g.drawString(line, textX + 1, textY + 1);
            g.setColor(TOOLTIP_TEXT);
            g.drawString(line, textX, textY);
            textY += lineHeight + lineGap;
        }

        g.setFont(oldFont);
        g.setColor(oldColor);
        g.setComposite(oldComposite);
    }

    private List<String> wrapToTwoLines(String text, FontMetrics fm, int maxWidth) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return Collections.emptyList();
        }
        if (fm.stringWidth(trimmed) <= maxWidth) {
            return Collections.singletonList(trimmed);
        }

        String bestFirst = "";
        String bestSecond = "";
        int bestScore = Integer.MAX_VALUE;
        String[] parts = trimmed.split("\\s+");
        for (int i = 1; i < parts.length; i++) {
            String first = String.join(" ", Arrays.copyOfRange(parts, 0, i));
            String second = String.join(" ", Arrays.copyOfRange(parts, i, parts.length));
            int w1 = fm.stringWidth(first);
            int w2 = fm.stringWidth(second);
            int score = Math.max(w1, w2);
            if (score < bestScore) {
                bestScore = score;
                bestFirst = first;
                bestSecond = second;
            }
        }

        List<String> lines = new ArrayList<>(2);
        if (!bestFirst.isEmpty() && !bestSecond.isEmpty()) {
            lines.add(TextFitUtil.elideToWidth(bestFirst, fm, maxWidth));
            lines.add(TextFitUtil.elideToWidth(bestSecond, fm, maxWidth));
            return lines;
        }

        int cutoff = Math.max(1, Math.min(trimmed.length(), trimmed.length() / 2));
        String first = trimmed.substring(0, cutoff);
        String second = trimmed.substring(cutoff);
        lines.add(TextFitUtil.elideToWidth(first, fm, maxWidth));
        lines.add(TextFitUtil.elideToWidth(second, fm, maxWidth));
        return lines;
    }

    private String getItemNameSafe(int itemId) {
        if (itemId <= 0) {
            return "";
        }
        ItemComposition composition = itemManager.getItemComposition(itemId);
        if (composition == null) {
            return "";
        }
        String name = composition.getName();
        if (name == null) {
            return "";
        }
        name = name.trim();
        if (name.isEmpty() || name.equalsIgnoreCase("null") || name.equalsIgnoreCase("Members")
                || name.equalsIgnoreCase("(Members)") || name.matches("(?i)null\\s*\\(Members\\)")) {
            return "";
        }
        return name;
    }

    private String buildHoverText(int itemId) {
        String baseName = getItemNameSafe(itemId);
        String questName = QuestItemAllowlist.getQuestNameForItem(itemId);
        if (questName == null || questName.trim().isEmpty()) {
            return baseName;
        }
        if (baseName.isEmpty()) {
            return questName;
        }
        return baseName + " (" + questName + ")";
    }

    private void drawHighlight(Graphics2D g, int iconsX, int baseY, int itemId, int iconDimension, boolean emphasize) {
        final float glowScale = emphasize ? 1.8f : 1.4f;
        final float glowHeightScale = emphasize ? 2.0f : 1.6f;
        final int glowW = (int) (iconDimension * glowScale);
        final int glowH = (int) (iconDimension * glowHeightScale);
        final float cx = iconsX + iconDimension / 2f;
        final float cy = baseY + iconDimension / 2f;

        final Color[] glowPalette = getHighlightPalette(itemId);
        final Color accent = glowPalette[0];
        final Color innerGlow = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), emphasize ? 130 : 95);
        final Color outerGlow = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0);
        final RadialGradientPaint glow = new RadialGradientPaint(
                new Point2D.Float(cx, cy),
                glowH / 2f,
                new float[] { 0f, 1f },
                new Color[] {
                        innerGlow,
                        outerGlow
                });
        final Composite old = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(0.6f));
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
            Object oldInterpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            Object oldAntialias = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.drawImage(centerImg, scaledX, scaledY, scaledW, scaledH, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAntialias);
        }

        Stroke oldStroke = g.getStroke();
        Composite oldComposite = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(emphasize ? 0.9f : 0.7f));
        g.setStroke(new BasicStroke(emphasize ? 2.2f : 1.6f));
        g.setColor(accent);
        g.drawRoundRect(
                iconsX - 1,
                baseY - 1,
                iconDimension + 2,
                iconDimension + 2,
                6,
                6);
        g.setStroke(oldStroke);
        g.setComposite(oldComposite);
    }

    private Color[] getHighlightPalette(int itemId) {
        return isTradeableItem(itemId) ? TRADEABLE_GLOW : UNTRADEABLE_GLOW;
    }

    private boolean isTradeableItem(int itemId) {
        if (itemId <= 0) {
            return true;
        }
        ItemComposition composition = itemManager.getItemComposition(itemId);
        return composition == null || composition.isTradeable();
    }
}
