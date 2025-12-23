package com.chanceman.managers;

import com.chanceman.ChanceManConfig;
import com.chanceman.ChanceManOverlay;
import com.chanceman.ChanceManPanel;
import com.chanceman.ChoicemanOverlay;
import com.chanceman.RollOverlay;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.util.ColorUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages the roll animation for unlocking items.
 * It processes roll requests asynchronously and handles the roll animation through the overlay.
 */
@Singleton
public class RollAnimationManager
{
    @Inject private ItemManager itemManager;
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private UnlockedItemsManager unlockedManager;
    @Inject private ChanceManOverlay chanceManOverlay;
    @Inject private ChoicemanOverlay choicemanOverlay;
    @Inject private ChanceManConfig config;
    @Inject private MouseManager mouseManager;
    @Setter private ChanceManPanel chanceManPanel;

    private HashSet<Integer> allTradeableItems;
    private volatile Set<Integer> strictlyTradeableItems = Collections.emptySet();
    private final Queue<Integer> rollQueue = new ConcurrentLinkedQueue<>();
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean isRolling = false;
    private static final int SNAP_WINDOW_MS = 350;
    private final Random random = new Random();
    private volatile RollOverlay activeOverlayRef;

    @Getter
    @Setter
    private volatile boolean manualRoll = false;

    public synchronized void setAllTradeableItems(HashSet<Integer> allTradeableItems)
    {
        this.allTradeableItems = allTradeableItems;
        if (allTradeableItems == null || allTradeableItems.isEmpty())
        {
            strictlyTradeableItems = Collections.emptySet();
            return;
        }

        HashSet<Integer> tradeableOnly = new HashSet<>();
        for (int id : allTradeableItems)
        {
            ItemComposition comp = itemManager.getItemComposition(id);
            if (comp != null && comp.isTradeable())
            {
                tradeableOnly.add(id);
            }
        }
        strictlyTradeableItems = tradeableOnly;
    }

    /**
     * Enqueues an item ID for the roll animation.
     *
     * @param itemId The item ID to be rolled.
     */
    public void enqueueRoll(int itemId)
    {
        rollQueue.offer(itemId);
    }

    /**
     * Processes the roll queue by initiating a roll animation if not already rolling.
     */
    public void process()
    {
        if (!isRolling && !rollQueue.isEmpty())
        {
            int queuedItemId = rollQueue.poll();
            isRolling = true;
            executor.submit(() -> performRoll(queuedItemId));
        }
    }

    /**
     * Performs the roll animation.
     * Now announces/unlocks as soon as the item is selected (after the snap),
     * while still letting the highlight finish visually before accepting another roll.
     */
    private void performRoll(int queuedItemId)
    {
        int rollDuration = 3000;
        List<Integer> choicemanOptions = Collections.emptyList();
        boolean choicemanActive = false;
        if (config.enableChoiceman())
        {
            List<Integer> generated = buildChoicemanOptions(queuedItemId);
            if (generated.size() >= 2)
            {
                choicemanOptions = Collections.unmodifiableList(generated);
                choicemanOverlay.setChoicemanOptions(choicemanOptions);
                choicemanActive = true;
            }
            else
            {
                choicemanOverlay.setChoicemanOptions(Collections.emptyList());
            }
        }
        else
        {
            choicemanOverlay.setChoicemanOptions(Collections.emptyList());
        }

        RollOverlay activeOverlay = choicemanActive ? choicemanOverlay : chanceManOverlay;
        activeOverlayRef = activeOverlay;
        activeOverlay.startRollAnimation(0, rollDuration, this::getRandomLockedItem);

        try
        {
            Thread.sleep(rollDuration + SNAP_WINDOW_MS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        int finalRolledItem = activeOverlay.getFinalItem();
        int itemToUnlock;
        if (choicemanActive)
        {
            choicemanOverlay.setSelectionPending(true);
            try
            {
                itemToUnlock = waitForChoicemanSelection(choicemanOptions, finalRolledItem);
            }
            finally
            {
                choicemanOverlay.setSelectionPending(false);
            }
        }
        else
        {
            itemToUnlock = finalRolledItem;
        }

        if (!choicemanActive)
        {
            int remainingHighlight = Math.max(0, activeOverlay.getHighlightDurationMs() - SNAP_WINDOW_MS);
            if (remainingHighlight > 0)
            {
                try
                {
                    Thread.sleep(remainingHighlight);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            }
        }

        if (itemToUnlock != 0)
        {
            unlockedManager.unlockItem(itemToUnlock);
        }

        final boolean wasManualRoll = isManualRoll();
        final int finalItemToAnnounce = itemToUnlock != 0 ? itemToUnlock : finalRolledItem;
        final boolean announceChoiceman = choicemanActive;
        final int choiceCount = choicemanOptions.size();
        final int queuedId = queuedItemId;
        clientThread.invoke(() -> {
            String unlockedTag = ColorUtil.wrapWithColorTag(getItemName(finalItemToAnnounce), config.unlockedItemColor());
            String message;
            if (wasManualRoll)
            {
                String pressTag = ColorUtil.wrapWithColorTag("pressing a button", config.rolledItemColor());
                message = announceChoiceman
                        ? "Choiceman unlocked " + unlockedTag + " after " + pressTag + " presented "
                        + choiceCount + " choices."
                        : "Unlocked " + unlockedTag + " by " + pressTag;
            }
            else
            {
                String rolledTag = ColorUtil.wrapWithColorTag(getItemName(queuedId), config.rolledItemColor());
                message = announceChoiceman
                        ? "Choiceman unlocked " + unlockedTag + " after rolling " + choiceCount
                        + " choices (first was " + rolledTag + ")."
                        : "Unlocked " + unlockedTag + " by rolling " + rolledTag;
            }
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
            if (chanceManPanel != null) {
                SwingUtilities.invokeLater(() -> chanceManPanel.updatePanel());
            }
        });

        setManualRoll(false);
        isRolling = false;
        activeOverlayRef = null;
    }

    /**
     * Checks if a roll animation is currently in progress.
     *
     * @return true if a roll is in progress, false otherwise.
     */
    public boolean isRolling() {
        return isRolling;
    }

    /**
     * Retrieves a random locked item from the list of tradeable items.
     *
     * @return A random locked item ID, or a fallback if all items are unlocked.
     */
    public int getRandomLockedItem()
    {
        List<Integer> locked = new ArrayList<>();
        for (int id : allTradeableItems)
        {
            if (!unlockedManager.isUnlocked(id))
            {
                locked.add(id);
            }
        }
        if (locked.isEmpty())
        {
            // Fallback: keep showing the current center item
            RollOverlay overlayRef = activeOverlayRef != null ? activeOverlayRef : chanceManOverlay;
            return overlayRef.getFinalItem();
        }
        return locked.get(random.nextInt(locked.size()));
    }

    public String getItemName(int itemId)
    {
        ItemComposition comp = itemManager.getItemComposition(itemId);
        if (comp == null)
        {
            return "";
        }
        String name = comp.getName();
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

    public void startUp() {
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            executor = Executors.newSingleThreadExecutor();
        }
    }

    /**
     * Shuts down the roll animation executor service.
     */
    public void shutdown()
    {
        executor.shutdownNow();
    }

    private List<Integer> buildChoicemanOptions(int guaranteedItemId)
    {
        int target = Math.max(2, Math.min(5, config.choicemanOptionCount()));
        LinkedHashSet<Integer> options = new LinkedHashSet<>();
        boolean hasTradeableOption = isTradeableItem(guaranteedItemId);
        if (guaranteedItemId != 0)
        {
            options.add(guaranteedItemId);
        }

        int attemptsLeft = Math.max(target * 3, allTradeableItems != null ? allTradeableItems.size() : target * 3);
        while (options.size() < target && attemptsLeft-- > 0)
        {
            int candidate = getRandomLockedItem();
            if (candidate == 0)
            {
                break;
            }
            options.add(candidate);
            if (isTradeableItem(candidate))
            {
                hasTradeableOption = true;
            }
        }

        if (!hasTradeableOption)
        {
            int failsafe = getRandomTradeableItem();
            if (failsafe != 0 && !options.contains(failsafe))
            {
                if (options.size() >= target)
                {
                    Iterator<Integer> iterator = options.iterator();
                    boolean removed = false;
                    while (iterator.hasNext())
                    {
                        int itemId = iterator.next();
                        if (!isTradeableItem(itemId))
                        {
                            iterator.remove();
                            removed = true;
                            break;
                        }
                    }
                    if (!removed)
                    {
                        Iterator<Integer> fallbackIterator = options.iterator();
                        if (fallbackIterator.hasNext())
                        {
                            fallbackIterator.next();
                            fallbackIterator.remove();
                        }
                    }
                }
                if (options.size() < target)
                {
                    options.add(failsafe);
                    hasTradeableOption = true;
                }
            }
        }

        return new ArrayList<>(options);
    }

    private boolean isTradeableItem(int itemId)
    {
        return itemId != 0 && strictlyTradeableItems.contains(itemId);
    }

    private int getRandomTradeableItem()
    {
        if (strictlyTradeableItems == null || strictlyTradeableItems.isEmpty())
        {
            return 0;
        }
        int index = random.nextInt(strictlyTradeableItems.size());
        int i = 0;
        for (int id : strictlyTradeableItems)
        {
            if (i++ == index)
            {
                return id;
            }
        }
        return strictlyTradeableItems.iterator().next();
    }

    private int waitForChoicemanSelection(List<Integer> options, int fallbackItemId)
    {
        if (options.isEmpty())
        {
            return fallbackItemId;
        }
        if (client.getCanvas() == null)
        {
            return options.get(0);
        }
        CompletableFuture<Integer> future = new CompletableFuture<>();
        MouseAdapter listener = new MouseAdapter()
        {
            private Integer blockIfOverButton(MouseEvent e)
            {
                Integer hit = choicemanOverlay.getOptionAt(e.getX(), e.getY());
                if (hit != null)
                {
                    e.consume();
                }
                return hit;
            }

            @Override
            public MouseEvent mousePressed(MouseEvent e)
            {
                blockIfOverButton(e);
                return e;
            }

            @Override
            public MouseEvent mouseClicked(MouseEvent e)
            {
                blockIfOverButton(e);
                return e;
            }

            @Override
            public MouseEvent mouseReleased(MouseEvent e)
            {
                Integer hit = blockIfOverButton(e);
                if (hit != null)
                {
                    future.complete(hit);
                }
                return e;
            }
        };
        // Register after any coordinate translators (eg. Stretched Mode) so click coords line up with overlays.
        mouseManager.registerMouseListener(listener);
        try
        {
            Integer result = future.get();
            return result != null ? result : fallbackItemId;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return fallbackItemId;
        }
        catch (ExecutionException e)
        {
            return fallbackItemId;
        }
        finally
        {
            mouseManager.unregisterMouseListener(listener);
        }
    }
}
