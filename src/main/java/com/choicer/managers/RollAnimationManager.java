package com.choicer.managers;

import com.choicer.ChoicerConfig;
import com.choicer.ChoicerOverlay;
import com.choicer.ChoicerPanel;
import com.choicer.RollOverlay;
import com.choicer.sync.GroupRollEvent;
import com.choicer.sync.GroupRollEventType;
import com.choicer.sync.GroupSyncService;
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
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manages the roll animation for rolling/unlocking items.
 * Obtained items trigger rolls; rolled items become usable when also obtained.
 */
@Singleton
@Slf4j
public class RollAnimationManager
{
    @Inject private ItemManager itemManager;
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private RolledItemsManager rolledManager;
    @Inject private ChoicerOverlay choicerOverlay;
    @Inject private ChoicerConfig config;
    @Inject private AudioPlayer audioPlayer;
    @Inject private MouseManager mouseManager;
    @Inject private GroupSyncService groupSyncService;
    @Setter private ChoicerPanel choicerPanel;

    private Set<Integer> allTradeableItems = Collections.emptySet();
    private volatile Set<Integer> strictlyTradeableItems = Collections.emptySet();
    private final Queue<Integer> rollQueue = new ConcurrentLinkedQueue<>();
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean isRolling = false;
    private volatile boolean tradeablesReady = false;
    private static final int ROLL_DURATION_MS = 3600;
    private static final int SNAP_WINDOW_MS = 350;
    private static final long REMOTE_SELECTION_TIMEOUT_MS = 120_000L;
    private static final String CONFIRM_SOUND_WAV = "/com/choicer/confirmation_002.wav";
    private static final String CONFIRM_SOUND_OGG = "/com/choicer/confirmation_002.ogg";
    private final Random random = new Random();
    private volatile RollOverlay activeOverlayRef;
    private volatile boolean confirmationSoundUnavailable = false;
    private final Map<String, CompletableFuture<Integer>> remoteSelectionFutures = new ConcurrentHashMap<>();
    private final Map<String, Integer> pendingRemoteSelections = new ConcurrentHashMap<>();
    private final Object remoteSessionLock = new Object();
    private final LinkedHashMap<String, RemoteRollSession> remoteSessions = new LinkedHashMap<>();
    private final ArrayDeque<String> remoteStartQueue = new ArrayDeque<>();
    private volatile String activeRemoteRollId = null;

    public enum RemoteRollState
    {
        QUEUED,
        ACTIVE,
        HIDDEN,
        RESOLVED
    }

    public static final class RemoteRollSummary
    {
        public final String rollId;
        public final String actorName;
        public final int triggerItemId;
        public final String triggerItemName;
        public final int optionCount;
        public final RemoteRollState state;
        public final boolean hasSelection;

        private RemoteRollSummary(String rollId, String actorName, int triggerItemId, String triggerItemName, int optionCount, RemoteRollState state, boolean hasSelection)
        {
            this.rollId = rollId;
            this.actorName = actorName;
            this.triggerItemId = triggerItemId;
            this.triggerItemName = triggerItemName;
            this.optionCount = optionCount;
            this.state = state;
            this.hasSelection = hasSelection;
        }
    }

    private static final class RemoteRollSession
    {
        private final GroupRollEvent startedEvent;
        private volatile RemoteRollState state;
        private volatile Integer selectedItemId;

        private RemoteRollSession(GroupRollEvent startedEvent, RemoteRollState state)
        {
            this.startedEvent = startedEvent;
            this.state = state;
        }
    }

    @Getter
    @Setter
    private volatile boolean manualRoll = false;

    public synchronized void setAllTradeableItems(Set<Integer> allTradeableItems)
    {
        if (allTradeableItems == null || allTradeableItems.isEmpty())
        {
            this.allTradeableItems = Collections.emptySet();
            strictlyTradeableItems = Collections.emptySet();
            tradeablesReady = false;
            return;
        }
        this.allTradeableItems = allTradeableItems;

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
        tradeablesReady = !this.allTradeableItems.isEmpty();
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

    public boolean hasTradeablesReady()
    {
        return tradeablesReady && allTradeableItems != null && !allTradeableItems.isEmpty();
    }

    /**
     * Processes the roll queue by initiating a roll animation if not already rolling.
     */
    public void process()
    {
        if (!hasTradeablesReady())
        {
            return;
        }
        if (!isRolling && !rollQueue.isEmpty())
        {
            int queuedItemId = rollQueue.poll();
            isRolling = true;
            executor.submit(() -> performRoll(queuedItemId));
        }
    }

    public List<RemoteRollSummary> getRemoteRollSummaries()
    {
        synchronized (remoteSessionLock)
        {
            List<RemoteRollSummary> rows = new ArrayList<>();
            for (RemoteRollSession session : remoteSessions.values())
            {
                GroupRollEvent event = session.startedEvent;
                int triggerItemId = event.triggerItemId != null ? event.triggerItemId : 0;
                rows.add(new RemoteRollSummary(
                        event.rollId,
                        sanitizePlayerName(event.actorDisplayName, event.actorUserId),
                        triggerItemId,
                        getItemName(triggerItemId),
                        event.getOptionsOrEmpty().size(),
                        session.state,
                        session.selectedItemId != null && session.selectedItemId > 0
                ));
            }
            return rows;
        }
    }

    public void hideRemoteRoll(String rollId)
    {
        if (rollId == null || rollId.trim().isEmpty())
        {
            return;
        }
        synchronized (remoteSessionLock)
        {
            RemoteRollSession session = remoteSessions.get(rollId);
            if (session == null || session.state == RemoteRollState.RESOLVED)
            {
                return;
            }
            session.state = RemoteRollState.HIDDEN;
        }
        CompletableFuture<Integer> future = remoteSelectionFutures.get(rollId);
        if (future != null && !future.isDone())
        {
            future.complete(0);
        }
    }

    public void openRemoteRoll(String rollId)
    {
        if (rollId == null || rollId.trim().isEmpty())
        {
            return;
        }
        GroupRollEvent eventToStart = null;
        synchronized (remoteSessionLock)
        {
            RemoteRollSession session = remoteSessions.get(rollId);
            if (session == null || session.state == RemoteRollState.RESOLVED)
            {
                return;
            }
            if (session.state == RemoteRollState.ACTIVE)
            {
                return;
            }
            if (session.state == RemoteRollState.HIDDEN)
            {
                session.state = RemoteRollState.QUEUED;
                remoteStartQueue.remove(rollId);
                remoteStartQueue.addFirst(rollId);
            }
            if (!isRolling && rollQueue.isEmpty() && !remoteStartQueue.isEmpty() && rollId.equals(remoteStartQueue.peekFirst()))
            {
                eventToStart = remoteSessions.get(rollId).startedEvent;
                remoteStartQueue.pollFirst();
                session.state = RemoteRollState.ACTIVE;
                activeRemoteRollId = rollId;
                isRolling = true;
            }
        }
        if (eventToStart != null)
        {
            GroupRollEvent event = eventToStart;
            executor.submit(() -> performRemoteRoll(event));
        }
    }

    public void dismissRemoteRoll(String rollId)
    {
        if (rollId == null || rollId.trim().isEmpty())
        {
            return;
        }
        synchronized (remoteSessionLock)
        {
            remoteSessions.remove(rollId);
            remoteStartQueue.remove(rollId);
            if (rollId.equals(activeRemoteRollId))
            {
                activeRemoteRollId = null;
            }
        }
        CompletableFuture<Integer> future = remoteSelectionFutures.remove(rollId);
        if (future != null && !future.isDone())
        {
            future.complete(0);
        }
        pendingRemoteSelections.remove(rollId);
    }

    public void onGroupRollEvent(GroupRollEvent event)
    {
        if (event == null || event.type == null || event.rollId == null || event.rollId.trim().isEmpty())
        {
            return;
        }
        if (event.type == GroupRollEventType.STARTED)
        {
            announceRemoteRollStart(event);
            GroupRollEvent eventToStart = null;
            synchronized (remoteSessionLock)
            {
                if (!remoteSessions.containsKey(event.rollId))
                {
                    remoteSessions.put(event.rollId, new RemoteRollSession(event, RemoteRollState.QUEUED));
                }
                RemoteRollSession session = remoteSessions.get(event.rollId);
                if (session == null || session.state == RemoteRollState.RESOLVED)
                {
                    return;
                }
                if (isRolling || !rollQueue.isEmpty())
                {
                    session.state = RemoteRollState.QUEUED;
                    if (!remoteStartQueue.contains(event.rollId))
                    {
                        remoteStartQueue.addLast(event.rollId);
                    }
                    return;
                }
                session.state = RemoteRollState.ACTIVE;
                activeRemoteRollId = event.rollId;
                isRolling = true;
                eventToStart = session.startedEvent;
            }
            if (eventToStart != null)
            {
                GroupRollEvent active = eventToStart;
                executor.submit(() -> performRemoteRoll(active));
            }
            return;
        }
        if (event.type == GroupRollEventType.SELECTED && event.selectedItemId != null && event.selectedItemId > 0)
        {
            synchronized (remoteSessionLock)
            {
                RemoteRollSession session = remoteSessions.get(event.rollId);
                if (session != null)
                {
                    session.selectedItemId = event.selectedItemId;
                    if (session.state != RemoteRollState.RESOLVED)
                    {
                        session.state = session.state == RemoteRollState.ACTIVE ? RemoteRollState.ACTIVE : session.state;
                    }
                }
            }
            pendingRemoteSelections.put(event.rollId, event.selectedItemId);
            CompletableFuture<Integer> future = remoteSelectionFutures.get(event.rollId);
            if (future != null && !future.isDone())
            {
                future.complete(event.selectedItemId);
            }
        }
    }

    private void performRemoteRoll(GroupRollEvent event)
    {
        String rollId = event.rollId;
        int rollDuration = event.getRollDurationMsOrDefault(ROLL_DURATION_MS);
        List<Integer> options = new ArrayList<>(event.getOptionsOrEmpty());
        try
        {
            if (options.isEmpty())
            {
                options = buildChoicerOptions(event.triggerItemId != null ? event.triggerItemId : 0);
            }
            List<Integer> immutableOptions = Collections.unmodifiableList(options);
            choicerOverlay.setChoicerOptions(immutableOptions);
            activeOverlayRef = choicerOverlay;
            choicerOverlay.startRollAnimation(0, rollDuration, this::getRandomLockedItem);
            choicerOverlay.setSelectionOwnerLabel(sanitizePlayerName(event.actorDisplayName, event.actorUserId));

            long startedMs = event.startedAt != null ? event.startedAt.toEpochMilli() : System.currentTimeMillis();
            long waitingAtMs = startedMs + rollDuration + SNAP_WINDOW_MS;
            sleepQuietly(Math.max(0L, waitingAtMs - System.currentTimeMillis()));

            choicerOverlay.setSelectionPending(true);
            CompletableFuture<Integer> selectionFuture = new CompletableFuture<>();
            remoteSelectionFutures.put(rollId, selectionFuture);
            Integer pendingSelection = pendingRemoteSelections.remove(rollId);
            if (pendingSelection != null && pendingSelection > 0)
            {
                selectionFuture.complete(pendingSelection);
            }

            MouseAdapter viewerControlsListener = createSelectionUiMouseListener(null);
            mouseManager.registerMouseListener(viewerControlsListener);

            int selectedItemId;
            try
            {
                selectedItemId = selectionFuture.get(REMOTE_SELECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                selectedItemId = choicerOverlay.getFinalItem();
            }
            catch (ExecutionException | TimeoutException e)
            {
                selectedItemId = choicerOverlay.getFinalItem();
            }
            finally
            {
                mouseManager.unregisterMouseListener(viewerControlsListener);
                remoteSelectionFutures.remove(rollId);
                pendingRemoteSelections.remove(rollId);
                choicerOverlay.setSelectionPending(false);
            }

            if (selectedItemId > 0)
            {
                playConfirmationSound();
                long resolveDelay = choicerOverlay.startSelectionResolveAnimation(selectedItemId);
                sleepQuietly(resolveDelay);
                announceRemoteSelection(event, selectedItemId, immutableOptions);
                synchronized (remoteSessionLock)
                {
                    RemoteRollSession session = remoteSessions.get(rollId);
                    if (session != null)
                    {
                        session.selectedItemId = selectedItemId;
                        session.state = RemoteRollState.RESOLVED;
                    }
                }
            }
            else
            {
                choicerOverlay.stopAnimation();
                synchronized (remoteSessionLock)
                {
                    RemoteRollSession session = remoteSessions.get(rollId);
                    if (session != null && session.state != RemoteRollState.RESOLVED)
                    {
                        session.state = RemoteRollState.HIDDEN;
                    }
                }
            }
        }
        catch (Exception e)
        {
            log.debug("Choicer remote roll playback skipped", e);
        }
        finally
        {
            isRolling = false;
            activeOverlayRef = null;
            synchronized (remoteSessionLock)
            {
                if (rollId.equals(activeRemoteRollId))
                {
                    activeRemoteRollId = null;
                }
            }
            startNextRemoteRollIfIdle();
        }
    }

    /**
     * Performs the roll animation.
     * Now announces/unlocks as soon as the item is selected (after the snap),
     * while still letting the highlight finish visually before accepting another roll.
     */
    private void performRoll(int queuedItemId)
    {
        final boolean wasManualRoll = isManualRoll();
        final String rollId = UUID.randomUUID().toString();
        final Instant rollStartedAt = Instant.now();
        try
        {
            int rollDuration = ROLL_DURATION_MS;
            List<Integer> generated = buildChoicerOptions(queuedItemId);
            List<Integer> choicerOptions = Collections.unmodifiableList(generated);
            boolean choicerSelectionActive = !choicerOptions.isEmpty();
            choicerOverlay.setChoicerOptions(choicerOptions);

            if (groupSyncService != null)
            {
                groupSyncService.postRollStarted(
                        rollId,
                        rollStartedAt,
                        queuedItemId,
                        choicerOptions,
                        rollDuration,
                        wasManualRoll
                );
            }
            announceLocalRollStart(queuedItemId);

            activeOverlayRef = choicerOverlay;
            choicerOverlay.startRollAnimation(0, rollDuration, this::getRandomLockedItem);
            choicerOverlay.setSelectionOwnerLabel(getLocalPlayerName());

            try
            {
                Thread.sleep(rollDuration + SNAP_WINDOW_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }

            int finalRolledItem = choicerOverlay.getFinalItem();
            int itemToUnlock;
            if (choicerSelectionActive)
            {
                choicerOverlay.setSelectionPending(true);
                int selected;
                try
                {
                    selected = waitForChoicerSelection(choicerOptions, finalRolledItem);
                }
                finally
                {
                    choicerOverlay.setSelectionPending(false);
                }
                itemToUnlock = selected;
                if (groupSyncService != null && itemToUnlock > 0)
                {
                    groupSyncService.postRollSelected(
                            rollId,
                            rollStartedAt,
                            itemToUnlock,
                            rollDuration,
                            wasManualRoll
                    );
                }
                long resolveDelay = choicerOverlay.startSelectionResolveAnimation(itemToUnlock);
                if (resolveDelay > 0)
                {
                    try
                    {
                        Thread.sleep(resolveDelay);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
                else
                {
                    choicerOverlay.stopAnimation();
                }
            }
            else
            {
                itemToUnlock = finalRolledItem;
            }

            if (!choicerSelectionActive)
            {
                int remainingHighlight = Math.max(0, choicerOverlay.getHighlightDurationMs() - SNAP_WINDOW_MS);
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
                rolledManager.markRolled(itemToUnlock);
                if (groupSyncService != null)
                {
                    groupSyncService.postRolledUnlockItem(itemToUnlock);
                }
            }

            final int finalItemToAnnounce = itemToUnlock != 0 ? itemToUnlock : finalRolledItem;
            final boolean announceChoicer = choicerSelectionActive;
            final List<Integer> choiceItems = choicerOptions;
            final int queuedId = queuedItemId;
            clientThread.invoke(() -> {
                String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Player";
                String rolledTag = ColorUtil.wrapWithColorTag(getItemName(finalItemToAnnounce), config.unlockedItemColor());
                String playerTag = ColorUtil.wrapWithColorTag(playerName, config.rolledItemColor());
                String optionsTag = buildChoiceListTag(choiceItems);
                String message;
                if (queuedId > 0)
                {
                    String obtainedTag = ColorUtil.wrapWithColorTag(getItemName(queuedId), config.rolledItemColor());
                    message = announceChoicer
                            ? playerTag + " chose " + rolledTag + " from " + optionsTag + " after rolling for " + obtainedTag + "."
                            : playerTag + " chose " + rolledTag + " after obtaining " + obtainedTag + ".";
                }
                else
                {
                    message = announceChoicer
                            ? playerTag + " chose " + rolledTag + " from " + optionsTag + "."
                            : playerTag + " chose " + rolledTag + ".";
                }
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
                if (choicerPanel != null) {
                    SwingUtilities.invokeLater(() -> choicerPanel.updatePanel());
                }
            });
        }
        catch (Exception e)
        {
            log.error("Choicer roll failed", e);
        }
        finally
        {
            setManualRoll(false);
            isRolling = false;
            activeOverlayRef = null;
            startNextRemoteRollIfIdle();
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
    public int getRandomLockedItem()
    {
        if (allTradeableItems == null || allTradeableItems.isEmpty())
        {
            return 0;
        }
        List<Integer> locked = new ArrayList<>();
        for (int id : allTradeableItems)
        {
            if (!rolledManager.isRolled(id))
            {
                locked.add(id);
            }
        }
        if (locked.isEmpty())
        {
            // Fallback: keep showing the current center item
            RollOverlay overlayRef = activeOverlayRef != null ? activeOverlayRef : choicerOverlay;
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

    private List<Integer> buildChoicerOptions(int obtainedItemId)
    {
        int target = Math.max(2, Math.min(5, config.choicerOptionCount()));
        LinkedHashSet<Integer> options = new LinkedHashSet<>();
        boolean hasTradeableOption = isTradeableItem(obtainedItemId);
        if (obtainedItemId != 0 && !rolledManager.isRolled(obtainedItemId))
        {
            options.add(obtainedItemId);
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

    private int waitForChoicerSelection(List<Integer> options, int fallbackItemId)
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
        MouseAdapter listener = createSelectionUiMouseListener(future);
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

    private MouseAdapter createSelectionUiMouseListener(CompletableFuture<Integer> selectionFuture)
    {
        return new MouseAdapter()
        {
            private Integer handleEvent(MouseEvent e)
            {
                if (choicerOverlay.isSelectionToggleAt(e.getX(), e.getY()))
                {
                    choicerOverlay.toggleSelectionUiHidden();
                    e.consume();
                    return null;
                }
                Integer hit = choicerOverlay.getOptionAt(e.getX(), e.getY());
                if (hit != null)
                {
                    e.consume();
                }
                return hit;
            }

            @Override
            public MouseEvent mousePressed(MouseEvent e)
            {
                handleEvent(e);
                return e;
            }

            @Override
            public MouseEvent mouseClicked(MouseEvent e)
            {
                handleEvent(e);
                return e;
            }

            @Override
            public MouseEvent mouseReleased(MouseEvent e)
            {
                Integer hit = handleEvent(e);
                if (hit != null && selectionFuture != null && !selectionFuture.isDone())
                {
                    playConfirmationSound();
                    selectionFuture.complete(hit);
                }
                return e;
            }
        };
    }

    private void announceRemoteRollStart(GroupRollEvent event)
    {
        if (event == null)
        {
            return;
        }
        clientThread.invoke(() -> {
            String actor = sanitizePlayerName(event.actorDisplayName, event.actorUserId);
            String actorTag = ColorUtil.wrapWithColorTag(actor, config.rolledItemColor());
            String triggerName = getItemName(event.triggerItemId != null ? event.triggerItemId : 0);
            String message;
            if (triggerName != null && !triggerName.isEmpty())
            {
                String triggerTag = ColorUtil.wrapWithColorTag(triggerName, config.unlockedItemColor());
                message = actorTag + " is rolling because " + triggerTag + "...";
            }
            else
            {
                message = actorTag + " is rolling...";
            }
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
        });
    }

    private void announceLocalRollStart(int triggerItemId)
    {
        clientThread.invoke(() -> {
            String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Player";
            String actorTag = ColorUtil.wrapWithColorTag(playerName, config.rolledItemColor());
            String triggerName = getItemName(triggerItemId);
            String message;
            if (triggerName != null && !triggerName.isEmpty())
            {
                String triggerTag = ColorUtil.wrapWithColorTag(triggerName, config.unlockedItemColor());
                message = actorTag + " is rolling because " + triggerTag + "...";
            }
            else
            {
                message = actorTag + " is rolling...";
            }
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
        });
    }

    private void announceRemoteSelection(GroupRollEvent event, int selectedItemId, List<Integer> options)
    {
        if (selectedItemId <= 0)
        {
            return;
        }
        clientThread.invoke(() -> {
            String actor = sanitizePlayerName(event != null ? event.actorDisplayName : null, event != null ? event.actorUserId : null);
            String actorTag = ColorUtil.wrapWithColorTag(actor, config.rolledItemColor());
            String itemTag = ColorUtil.wrapWithColorTag(getItemName(selectedItemId), config.unlockedItemColor());
            String optionsTag = buildChoiceListTag(options);
            String triggerName = event != null ? getItemName(event.triggerItemId != null ? event.triggerItemId : 0) : "";
            String message;
            if (!triggerName.isEmpty())
            {
                String triggerTag = ColorUtil.wrapWithColorTag(triggerName, config.rolledItemColor());
                message = actorTag + " chose " + itemTag + " from " + optionsTag + " after rolling for " + triggerTag + ".";
            }
            else
            {
                message = actorTag + " chose " + itemTag + " from " + optionsTag + ".";
            }
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
            if (choicerPanel != null)
            {
                SwingUtilities.invokeLater(() -> choicerPanel.updatePanel());
            }
        });
    }

    private String buildChoiceListTag(List<Integer> options)
    {
        if (options == null || options.isEmpty())
        {
            return "(no options)";
        }
        List<String> names = new ArrayList<>();
        for (Integer optionId : options)
        {
            if (optionId == null || optionId <= 0)
            {
                continue;
            }
            String name = getItemName(optionId);
            if (name == null || name.isEmpty())
            {
                name = "Item " + optionId;
            }
            names.add(ColorUtil.wrapWithColorTag(name, config.unlockedItemColor()));
        }
        if (names.isEmpty())
        {
            return "(no options)";
        }
        return "(" + String.join(", ", names) + ")";
    }

    private String sanitizePlayerName(String displayName, String fallback)
    {
        if (displayName != null)
        {
            String trimmed = displayName.trim();
            if (!trimmed.isEmpty())
            {
                return trimmed;
            }
        }
        if (fallback != null)
        {
            String trimmed = fallback.trim();
            if (!trimmed.isEmpty())
            {
                return trimmed;
            }
        }
        return "Player";
    }

    private String getLocalPlayerName()
    {
        try
        {
            if (client.getLocalPlayer() == null)
            {
                return "You";
            }
            String raw = client.getLocalPlayer().getName();
            if (raw == null)
            {
                return "You";
            }
            String trimmed = raw.trim();
            return trimmed.isEmpty() ? "You" : trimmed;
        }
        catch (Exception e)
        {
            return "You";
        }
    }

    private void sleepQuietly(long delayMs)
    {
        if (delayMs <= 0)
        {
            return;
        }
        try
        {
            Thread.sleep(delayMs);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private void startNextRemoteRollIfIdle()
    {
        if (isRolling || !rollQueue.isEmpty())
        {
            return;
        }
        GroupRollEvent next = null;
        synchronized (remoteSessionLock)
        {
            while (!remoteStartQueue.isEmpty())
            {
                String nextId = remoteStartQueue.pollFirst();
                RemoteRollSession session = remoteSessions.get(nextId);
                if (session == null || session.state == RemoteRollState.RESOLVED || session.state == RemoteRollState.HIDDEN)
                {
                    continue;
                }
                session.state = RemoteRollState.ACTIVE;
                activeRemoteRollId = nextId;
                next = session.startedEvent;
                isRolling = true;
                break;
            }
        }
        if (next != null)
        {
            GroupRollEvent event = next;
            executor.submit(() -> performRemoteRoll(event));
        }
    }

    private void playConfirmationSound()
    {
        if (!config.enableRollSounds())
        {
            return;
        }
        if (confirmationSoundUnavailable)
        {
            return;
        }
        try
        {
            float volumeDb = toDb(config.rollSoundVolume());
            if (!playSoundResource(CONFIRM_SOUND_WAV, volumeDb) && !playSoundResource(CONFIRM_SOUND_OGG, volumeDb))
            {
                confirmationSoundUnavailable = true;
            }
        }
        catch (IOException | UnsupportedAudioFileException | LineUnavailableException ex)
        {
            log.warn("Choicer: failed to play confirmation sound", ex);
            confirmationSoundUnavailable = true;
        }
    }

    private boolean playSoundResource(String path, float volumeDb)
            throws IOException, UnsupportedAudioFileException, LineUnavailableException
    {
        if (RollAnimationManager.class.getResource(path) == null)
        {
            return false;
        }
        audioPlayer.play(RollAnimationManager.class, path, volumeDb);
        return true;
    }

    private static float toDb(int percent)
    {
        int p = Math.max(0, Math.min(100, percent));
        if (p == 0)
        {
            return -80.0f;
        }
        double lin = p / 100.0;
        return (float) (20.0 * Math.log10(lin));
    }
}
