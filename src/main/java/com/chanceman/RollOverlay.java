package com.chanceman;

import java.util.function.Supplier;

/**
 * Common contract for roll overlays so the animation manager can swap them.
 */
public interface RollOverlay
{
    void startRollAnimation(int dummy, int rollDurationMs, Supplier<Integer> randomLockedItemSupplier);

    int getFinalItem();

    int getHighlightDurationMs();
}
