package com.choicer.managers;

import com.choicer.ChoicerConfig;
import com.choicer.ChoicerOverlay;
import com.choicer.ChoicerPanel;
import com.choicer.RollOverlay;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.audio.AudioPlayer;
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
 * Manages the roll animation for rolling/unlocking items.
 * Obtained items trigger rolls; selected choice items become unlocked.
 */
@Singleton
@Slf4j
public class RollAnimationManager {
    @Inject
    private ItemManager itemManager;
    @Inject
    private Client client;
    @Inject
    private ClientThread clientThread;
    @Inject
    private RolledItemsManager rolledManager;
    @Inject
    private ObtainedItemsManager obtainedManager;
    @Inject
    private ChoicerOverlay choicerOverlay;
    @Inject
    private ChoicerConfig config;
    @Inject
    private AudioPlayer audioPlayer;
    @Inject
    private MouseManager mouseManager;
    @Setter
    private ChoicerPanel choicerPanel;

    private Set<Integer> allTradeableItems = Collections.emptySet();
    private volatile Set<Integer> strictlyTradeableItems = Collections.emptySet();
    private final Queue<Integer> rollQueue = new ConcurrentLinkedQueue<>();
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean isRolling = false;
    private volatile boolean tradeablesReady = false;
    private static final int SNAP_WINDOW_MS = 350;
    private static final String CONFIRM_SOUND_WAV = "/com/choicer/confirmation_002.wav";
    private static final String CONFIRM_SOUND_OGG = "/com/choicer/confirmation_002.ogg";
    private final Random random = new Random();
    private volatile RollOverlay activeOverlayRef;
    private volatile boolean confirmationSoundUnavailable = false;

    @Getter
    @Setter
    private volatile boolean manualRoll = false;

    public synchronized void setAllTradeableItems(Set<Integer> allTradeableItems) {
        if (allTradeableItems == null || allTradeableItems.isEmpty()) {
            this.allTradeableItems = Collections.emptySet();
            strictlyTradeableItems = Collections.emptySet();
            tradeablesReady = false;
            return;
        }
        this.allTradeableItems = allTradeableItems;

        HashSet<Integer> tradeableOnly = new HashSet<>();
        for (int id : allTradeableItems) {
            ItemComposition comp = itemManager.getItemComposition(id);
            if (comp != null && comp.isTradeable()) {
                tradeableOnly.add(id);
            }
        }
        strictlyTradeableItems = tradeableOnly;
        tradeablesReady = !this.allTradeableItems.isEmpty();
    }

    /**
     * Enqueues an item ID for the roll animation.
     *
     * @param itemId The item ID to be rolled.
     */
    public void enqueueRoll(int itemId) {
        rollQueue.offer(itemId);
    }

    public boolean hasTradeablesReady() {
        return tradeablesReady && allTradeableItems != null && !allTradeableItems.isEmpty();
    }

    /**
     * Processes the roll queue by initiating a roll animation if not already
     * rolling.
     */
    public void process() {
        if (!hasTradeablesReady()) {
            return;
        }
        if (!isRolling && !rollQueue.isEmpty()) {
            int queuedItemId = rollQueue.poll();
            isRolling = true;
            executor.submit(() -> performRoll(queuedItemId));
        }
    }

    /**
     * Performs the roll animation.
     * Now announces/unlocks as soon as the item is selected (after the snap),
     * while still letting the highlight finish visually before accepting another
     * roll.
     */
    private void performRoll(int queuedItemId) {
        try {
            int rollDuration = 3600;
            List<Integer> generated = buildChoicerOptions(queuedItemId);
            List<Integer> choicerOptions = Collections.unmodifiableList(generated);
            boolean choicerSelectionActive = !choicerOptions.isEmpty();
            choicerOverlay.setChoicerOptions(choicerOptions);

            activeOverlayRef = choicerOverlay;
            choicerOverlay.startRollAnimation(0, rollDuration, this::getRandomLockedItem);

            try {
                Thread.sleep(rollDuration + SNAP_WINDOW_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            int finalRolledItem = choicerOverlay.getFinalItem();
            int itemToUnlock;
            if (choicerSelectionActive) {
                choicerOverlay.setSelectionPending(true);
                int selected;
                try {
                    selected = waitForChoicerSelection(choicerOptions, finalRolledItem);
                } finally {
                    choicerOverlay.setSelectionPending(false);
                }
                itemToUnlock = selected;
                long resolveDelay = choicerOverlay.startSelectionResolveAnimation(itemToUnlock);
                if (resolveDelay > 0) {
                    try {
                        Thread.sleep(resolveDelay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    choicerOverlay.stopAnimation();
                }
            } else {
                itemToUnlock = finalRolledItem;
            }

            if (!choicerSelectionActive) {
                int remainingHighlight = Math.max(0, choicerOverlay.getHighlightDurationMs() - SNAP_WINDOW_MS);
                if (remainingHighlight > 0) {
                    try {
                        Thread.sleep(remainingHighlight);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            if (itemToUnlock != 0) {
                rolledManager.markRolled(itemToUnlock);
            }

            final boolean wasManualRoll = isManualRoll();
            final int finalItemToAnnounce = itemToUnlock != 0 ? itemToUnlock : finalRolledItem;
            final boolean announceChoicer = choicerSelectionActive;
            final int choiceCount = choicerOptions.size();
            final int queuedId = queuedItemId;
            clientThread.invoke(() -> {
                String unlockedTag = ColorUtil.wrapWithColorTag(getItemName(finalItemToAnnounce),
                        config.unlockedItemColor());
                String message;
                if (wasManualRoll) {
                    String pressTag = ColorUtil.wrapWithColorTag("pressing a button", config.rolledItemColor());
                    message = announceChoicer
                            ? "Choicer rolled " + unlockedTag + " after " + pressTag + " presented "
                                    + choiceCount + " choices."
                            : "Rolled " + unlockedTag + " by " + pressTag;
                } else if (queuedId > 0) {
                    String obtainedTag = ColorUtil.wrapWithColorTag(getItemName(queuedId), config.rolledItemColor());
                    message = announceChoicer
                            ? "Choicer rolled " + unlockedTag + " after obtaining " + obtainedTag + " presented "
                                    + choiceCount + " choices."
                            : "Rolled " + unlockedTag + " by obtaining " + obtainedTag;
                } else {
                    message = "Rolled " + unlockedTag;
                }
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
                if (choicerPanel != null) {
                    SwingUtilities.invokeLater(() -> choicerPanel.updatePanel());
                }
            });
        } catch (Exception e) {
            log.error("Choicer roll failed", e);
        } finally {
            setManualRoll(false);
            isRolling = false;
            activeOverlayRef = null;
        }
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
    public int getRandomLockedItem() {
        if (allTradeableItems == null || allTradeableItems.isEmpty()) {
            return 0;
        }
        List<Integer> locked = new ArrayList<>();
        for (int id : allTradeableItems) {
            if (!rolledManager.isRolled(id)) {
                locked.add(id);
            }
        }
        if (locked.isEmpty()) {
            // Fallback: keep showing the current center item
            RollOverlay overlayRef = activeOverlayRef != null ? activeOverlayRef : choicerOverlay;
            return overlayRef.getFinalItem();
        }
        return locked.get(random.nextInt(locked.size()));
    }

    public String getItemName(int itemId) {
        ItemComposition comp = itemManager.getItemComposition(itemId);
        if (comp == null) {
            return "";
        }
        String name = comp.getName();
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

    public void startUp() {
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            executor = Executors.newSingleThreadExecutor();
        }
    }

    /**
     * Shuts down the roll animation executor service.
     */
    public void shutdown() {
        executor.shutdownNow();
    }

    private List<Integer> buildChoicerOptions(int obtainedItemId) {
        int target = Math.max(2, Math.min(5, config.choicerOptionCount()));
        LinkedHashSet<Integer> options = new LinkedHashSet<>();
        boolean hasTradeableOption = isTradeableItem(obtainedItemId);
        if (obtainedItemId != 0 && !rolledManager.isRolled(obtainedItemId)) {
            options.add(obtainedItemId);
        }

        int attemptsLeft = Math.max(target * 3, allTradeableItems != null ? allTradeableItems.size() : target * 3);
        while (options.size() < target && attemptsLeft-- > 0) {
            int candidate = getRandomLockedItem();
            if (candidate == 0) {
                break;
            }
            options.add(candidate);
            if (isTradeableItem(candidate)) {
                hasTradeableOption = true;
            }
        }

        if (!hasTradeableOption) {
            int failsafe = getRandomTradeableItem();
            if (failsafe != 0 && !options.contains(failsafe)) {
                if (options.size() >= target) {
                    Iterator<Integer> iterator = options.iterator();
                    boolean removed = false;
                    while (iterator.hasNext()) {
                        int itemId = iterator.next();
                        if (!isTradeableItem(itemId)) {
                            iterator.remove();
                            removed = true;
                            break;
                        }
                    }
                    if (!removed) {
                        Iterator<Integer> fallbackIterator = options.iterator();
                        if (fallbackIterator.hasNext()) {
                            fallbackIterator.next();
                            fallbackIterator.remove();
                        }
                    }
                }
                if (options.size() < target) {
                    options.add(failsafe);
                    hasTradeableOption = true;
                }
            }
        }

        return new ArrayList<>(options);
    }

    private boolean isTradeableItem(int itemId) {
        return itemId != 0 && strictlyTradeableItems.contains(itemId);
    }

    private int getRandomTradeableItem() {
        if (strictlyTradeableItems == null || strictlyTradeableItems.isEmpty()) {
            return 0;
        }
        int index = random.nextInt(strictlyTradeableItems.size());
        int i = 0;
        for (int id : strictlyTradeableItems) {
            if (i++ == index) {
                return id;
            }
        }
        return strictlyTradeableItems.iterator().next();
    }

    private int waitForChoicerSelection(List<Integer> options, int fallbackItemId) {
        if (options.isEmpty()) {
            return fallbackItemId;
        }
        if (client.getCanvas() == null) {
            return options.get(0);
        }
        CompletableFuture<Integer> future = new CompletableFuture<>();
        MouseAdapter listener = new MouseAdapter() {
            private Integer blockIfOverButton(MouseEvent e) {
                Integer hit = choicerOverlay.getOptionAt(e.getX(), e.getY());
                if (hit != null) {
                    e.consume();
                }
                return hit;
            }

            @Override
            public MouseEvent mousePressed(MouseEvent e) {
                blockIfOverButton(e);
                return e;
            }

            @Override
            public MouseEvent mouseClicked(MouseEvent e) {
                blockIfOverButton(e);
                return e;
            }

            @Override
            public MouseEvent mouseReleased(MouseEvent e) {
                Integer hit = blockIfOverButton(e);
                if (hit != null) {
                    playConfirmationSound();
                    future.complete(hit);
                }
                return e;
            }
        };
        // Register after any coordinate translators (eg. Stretched Mode) so click
        // coords line up with overlays.
        mouseManager.registerMouseListener(listener);
        try {
            Integer result = future.get();
            return result != null ? result : fallbackItemId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallbackItemId;
        } catch (ExecutionException e) {
            return fallbackItemId;
        } finally {
            mouseManager.unregisterMouseListener(listener);
        }
    }

    private void playConfirmationSound() {
        if (!config.enableRollSounds()) {
            return;
        }
        if (confirmationSoundUnavailable) {
            return;
        }
        float volumeDb = toDb(config.rollSoundVolume());
        if (!playSoundResource(CONFIRM_SOUND_WAV, volumeDb) && !playSoundResource(CONFIRM_SOUND_OGG, volumeDb)) {
            confirmationSoundUnavailable = true;
        }
    }

    private boolean playSoundResource(String path, float volumeDb) {
        if (RollAnimationManager.class.getResource(path) == null) {
            return false;
        }
        try {
            audioPlayer.play(RollAnimationManager.class, path, volumeDb);
            return true;
        } catch (Exception ex) {
            log.warn("Choicer: failed to play confirmation sound resource {}", path, ex);
            return false;
        }
    }

    private static float toDb(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        if (p == 0) {
            return -80.0f;
        }
        double lin = p / 100.0;
        return (float) (20.0 * Math.log10(lin));
    }
}
