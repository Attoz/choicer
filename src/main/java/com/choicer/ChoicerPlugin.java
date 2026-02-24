package com.choicer;

import com.choicer.account.AccountChanged;
import com.choicer.account.AccountManager;
import com.choicer.drops.DropFetcher;
import com.choicer.drops.DropCache;
import com.choicer.filters.EnsouledHeadMapping;
import com.choicer.menus.ActionHandler;
import com.choicer.filters.ItemsFilter;
import com.choicer.filters.ItemAttributes;
import com.choicer.filters.ItemEligibility;
import com.choicer.ui.DropsTabUI;
import com.choicer.ui.DropsTooltipOverlay;
import com.choicer.ui.MusicWidgetController;
import com.choicer.ui.NpcSearchService;
import com.choicer.ui.MusicSearchButton;
import com.choicer.ui.ItemDimmerController;
import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import com.choicer.managers.RollAnimationManager;
import com.choicer.managers.RolledItemsManager;
import com.choicer.managers.ObtainedItemsManager;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@PluginDescriptor(name = "Choicer", description = "Unlock items by rolling multiple choices", tags = { "chance", "roll",
        "lock", "unlock", "luck", "game of chance", "goc", "choices" })
public class ChoicerPlugin extends Plugin {
    @Inject
    private Client client;
    @Inject
    private ClientThread clientThread;
    @Inject
    private ClientToolbar clientToolbar;
    @Inject
    private OverlayManager overlayManager;
    @Getter
    @Inject
    private ItemManager itemManager;
    @Inject
    private ChoicerOverlay choicerOverlay;
    @Inject
    private DropsTooltipOverlay dropsTooltipOverlay;
    @Inject
    private Gson gson;
    @Inject
    private ChoicerConfig config;
    @Inject
    private ConfigManager configManager;
    @Inject
    private AccountManager accountManager;
    @Inject
    private ObtainedItemsManager obtainedItemsManager;
    @Inject
    private RolledItemsManager rolledItemsManager;
    @Inject
    private RollAnimationManager rollAnimationManager;
    @Inject
    private EventBus eventBus;
    @Inject
    private ItemsFilter itemsFilter;
    @Inject
    private DropsTabUI dropsTabUI;
    @Inject
    private DropFetcher dropFetcher;
    @Inject
    private DropCache dropCache;
    @Inject
    private MusicWidgetController musicWidgetController;
    @Inject
    private NpcSearchService npcSearchService;
    @Inject
    private MusicSearchButton musicSearchButton;
    @Inject
    private ItemDimmerController itemDimmerController;

    private ChoicerPanel choicerPanel;
    private NavigationButton navButton;
    private ExecutorService fileExecutor;
    @Getter
    private final HashSet<Integer> allTradeableItems = new LinkedHashSet<>();
    private static final int GE_SEARCH_BUILD_SCRIPT = 751;
    private volatile boolean tradeableItemsInitialized = false;
    private boolean featuresActive = false;

    @Provides
    ChoicerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ChoicerConfig.class);
    }

    @Override
    protected void startUp() throws Exception {
        eventBus.register(this);
        if (isNormalWorld())
            enableFeatures();
    }

    @Override
    protected void shutDown() throws Exception {
        if (featuresActive)
            disableFeatures();
        eventBus.unregister(this);
    }

    private void enableFeatures() {
        if (featuresActive)
            return;
        featuresActive = true;

        getInjector().getInstance(ActionHandler.class).startUp();
        accountManager.init();
        dropFetcher.startUp();
        dropCache.startUp();
        dropCache.getAllNpcData();
        eventBus.register(accountManager);
        overlayManager.add(choicerOverlay);
        overlayManager.add(dropsTooltipOverlay);

        fileExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Choicer-FileIO");
            t.setDaemon(true);
            return t;
        });
        obtainedItemsManager.setExecutor(fileExecutor);
        rolledItemsManager.setExecutor(fileExecutor);

        if (accountManager.ready()) {
            Runnable refreshPanel = () -> {
                if (choicerPanel != null) {
                    SwingUtilities.invokeLater(choicerPanel::updatePanel);
                }
                refreshDropsViewerIfOpen();
            };
            obtainedItemsManager.setOnChange(refreshPanel);
            rolledItemsManager.setOnChange(refreshPanel);

            obtainedItemsManager.loadObtainedItems();
            rolledItemsManager.loadRolledItems();
        }

        itemDimmerController.setEnabled(config.dimLockedItemsEnabled());
        itemDimmerController.setDimOpacity(config.dimLockedItemsOpacity());
        eventBus.register(itemDimmerController);
        rollAnimationManager.startUp();
        dropsTabUI.startUp();

        choicerPanel = new ChoicerPanel(
                obtainedItemsManager,
                rolledItemsManager,
                itemManager,
                allTradeableItems,
                clientThread,
                rollAnimationManager);
        rollAnimationManager.setChoicerPanel(choicerPanel);

        SwingUtilities.invokeLater(choicerPanel::updatePanel);

        if (accountManager.ready()) {
            obtainedItemsManager.startWatching();
            rolledItemsManager.startWatching();
        }

        BufferedImage icon = ImageUtil.loadImageResource(
                getClass(), "/com/choicer/icon.png");
        navButton = NavigationButton.builder()
                .tooltip("Choicer")
                .icon(icon)
                .priority(5)
                .panel(choicerPanel)
                .build();
        clientToolbar.addNavigation(navButton);

        eventBus.register(musicWidgetController);
        eventBus.register(musicSearchButton);
        musicSearchButton.onStart();
        tradeableItemsInitialized = false;
        rollAnimationManager.setAllTradeableItems(Collections.emptySet());
    }

    private void disableFeatures() {
        if (!featuresActive)
            return;
        featuresActive = false;

        try {
            if (obtainedItemsManager != null)
                obtainedItemsManager.stopWatching();
            if (rolledItemsManager != null)
                rolledItemsManager.stopWatching();
            if (obtainedItemsManager != null)
                obtainedItemsManager.flushIfDirtyOnExit();
            if (rolledItemsManager != null)
                rolledItemsManager.flushIfDirtyOnExit();
        } catch (Exception ignored) {
            /* Non-fatal */ }

        clientThread.invokeLater(musicWidgetController::restore);
        musicSearchButton.onStop();
        eventBus.unregister(musicSearchButton);
        eventBus.unregister(musicWidgetController);
        dropsTabUI.shutDown();
        eventBus.unregister(itemDimmerController);
        eventBus.unregister(accountManager);
        getInjector().getInstance(ActionHandler.class).shutDown();

        if (clientToolbar != null && navButton != null) {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }
        if (overlayManager != null) {
            overlayManager.remove(choicerOverlay);
            overlayManager.remove(dropsTooltipOverlay);
        }
        if (rollAnimationManager != null) {
            rollAnimationManager.shutdown();
        }
        if (fileExecutor != null) {
            fileExecutor.shutdownNow();
            fileExecutor = null;

            if (obtainedItemsManager != null) {
                obtainedItemsManager.setExecutor(null);
                obtainedItemsManager.setOnChange(null);
            }
            if (rolledItemsManager != null) {
                rolledItemsManager.setExecutor(null);
                rolledItemsManager.setOnChange(null);
            }
        }
        dropFetcher.shutdown();
        dropCache.shutdown();

        // reset panel/tradeable state
        choicerPanel = null;
        allTradeableItems.clear();
        tradeableItemsInitialized = false;
        rollAnimationManager.setAllTradeableItems(Collections.emptySet());
        accountManager.reset();
    }

    @Subscribe
    public void onWorldChanged(WorldChanged event) {
        if (isNormalWorld())
            enableFeatures();
        else
            disableFeatures();
    }

    /**
     * Refreshes the list of tradeable item IDs based on the current configuration.
     */
    public void refreshTradeableItems() {
        clientThread.invokeLater(() -> {
            tradeableItemsInitialized = false;

            allTradeableItems.clear();
            for (int i = 0; i < 40000; i++) {
                ItemComposition comp = itemManager.getItemComposition(i);
                if (comp != null && comp.isTradeable() && !isNotTracked(i)
                        && !ItemsFilter.isBlocked(i, config)) {
                    if (config.freeToPlay() && comp.isMembers()) {
                        continue;
                    }
                    if (!ItemsFilter.isPoisonEligible(i, config.requireWeaponPoison(),
                            rolledItemsManager.getRolledItems())) {
                        continue;
                    }
                    allTradeableItems.add(i);
                }
            }
            rollAnimationManager.setAllTradeableItems(allTradeableItems);

            // Only now mark initialized (prevents early rolls on login/inventory scan).
            tradeableItemsInitialized = true;

            if (choicerPanel != null) {
                SwingUtilities.invokeLater(choicerPanel::updatePanel);
            }
        });
    }

    @Subscribe
    public void onConfigChanged(net.runelite.client.events.ConfigChanged event) {
        if (!featuresActive)
            return;
        if (!event.getGroup().equals("choicer"))
            return;
        switch (event.getKey()) {
            case "freeToPlay":
            case "includeF2PTradeOnlyItems":
            case "enableFlatpacks":
            case "enableItemSets":
            case "requireWeaponPoison":
            case "includeUntradeable":
            case "includeQuestItems":
                refreshTradeableItems();
                break;
            case "showRareDropTable":
            case "showGemDropTable":
                dropCache.clearAllCaches();
                refreshDropsViewerIfOpen();
                break;
            case "sortDropsByRarity":
                refreshDropsViewerIfOpen();
                break;
            case "dimLockedItemsEnabled":
            case "dimLockedItemsOpacity":
                itemDimmerController.setEnabled(config.dimLockedItemsEnabled());
                itemDimmerController.setDimOpacity(config.dimLockedItemsOpacity());
                break;
        }
    }

    @Subscribe
    private void onAccountChanged(AccountChanged event) {
        if (!featuresActive)
            return;
        dropCache.pruneOldCaches();

        obtainedItemsManager.stopWatching();
        rolledItemsManager.stopWatching();

        obtainedItemsManager.loadObtainedItems();
        rolledItemsManager.loadRolledItems();

        refreshTradeableItems();
        if (choicerPanel != null) {
            SwingUtilities.invokeLater(choicerPanel::updatePanel);
        }

        obtainedItemsManager.startWatching();
        rolledItemsManager.startWatching();
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (!featuresActive)
            return;
        if (!tradeableItemsInitialized && client.getGameState() == GameState.LOGGED_IN) {
            refreshTradeableItems();
        }

        if (tradeableItemsInitialized) {
            rollAnimationManager.process();
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (!featuresActive)
            return;
        if (event.getScriptId() == GE_SEARCH_BUILD_SCRIPT) {
            killSearchResults();
        }
    }

    private void killSearchResults() {
        Widget geSearchResults = client.getWidget(162, 51);
        if (geSearchResults == null) {
            return;
        }
        Widget[] children = geSearchResults.getDynamicChildren();
        if (children == null || children.length < 2 || children.length % 3 != 0) {
            return;
        }
        Set<Integer> obtained = obtainedItemsManager.getObtainedItems();
        Set<Integer> rolled = rolledItemsManager.getRolledItems();
        boolean requireRolled = config.requireRolledUnlockedForGe();
        for (int i = 0; i < children.length; i += 3) {
            int offerItemId = children[i + 2].getItemId();
            boolean isObtained = obtained.contains(offerItemId);
            boolean isRolled = rolled.contains(offerItemId);
            boolean hide = requireRolled ? !(isObtained && isRolled) : !isRolled;
            if (hide) {
                children[i].setHidden(true);
                children[i + 1].setOpacity(70);
                children[i + 2].setOpacity(70);
            }
        }
    }

    private boolean canProcessItemEvents() {
        return featuresActive
                && accountManager.ready()
                && tradeableItemsInitialized
                && rollAnimationManager.hasTradeablesReady();
    }

    @Subscribe
    public void onItemSpawned(ItemSpawned event) {
        if (!canProcessItemEvents())
            return;

        TileItem tileItem = (TileItem) event.getItem();
        int itemId = EnsouledHeadMapping.toTradeableId(tileItem.getId());
        int canonicalItemId = itemManager.canonicalize(itemId);
        if (!isEligibleForLocking(canonicalItemId)) {
            return;
        }
        if (tileItem.getOwnership() != TileItem.OWNERSHIP_SELF) {
            return;
        }
        if (!obtainedItemsManager.isObtained(canonicalItemId)) {
            obtainedItemsManager.markObtained(canonicalItemId);
            rollAnimationManager.enqueueRoll(canonicalItemId);
            refreshDropsViewerIfOpen();
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (!canProcessItemEvents())
            return;

        if (event.getContainerId() == 93) {
            Set<Integer> processed = new HashSet<>();
            for (net.runelite.api.Item item : event.getItemContainer().getItems()) {
                int rawItemId = item.getId();
                int mapped = EnsouledHeadMapping.toTradeableId(rawItemId);
                int canonicalId = itemManager.canonicalize(mapped);
                if (!isEligibleForLocking(canonicalId)) {
                    continue;
                }

                if (!processed.contains(canonicalId) && !obtainedItemsManager.isObtained(canonicalId)) {
                    obtainedItemsManager.markObtained(canonicalId);
                    rollAnimationManager.enqueueRoll(canonicalId);
                    processed.add(canonicalId);
                }
            }
            if (!processed.isEmpty())
                refreshDropsViewerIfOpen();
        }
    }

    public boolean isNormalWorld() {
        EnumSet<WorldType> worldTypes = client.getWorldType();
        return !(worldTypes.contains(WorldType.DEADMAN)
                || worldTypes.contains(WorldType.SEASONAL)
                || worldTypes.contains(WorldType.BETA_WORLD)
                || worldTypes.contains(WorldType.PVP_ARENA)
                || worldTypes.contains(WorldType.QUEST_SPEEDRUNNING)
                || worldTypes.contains(WorldType.TOURNAMENT_WORLD));
    }

    private void refreshDropsViewerIfOpen() {
        if (musicWidgetController != null
                && musicWidgetController.hasData()
                && musicWidgetController.getCurrentData() != null) {
            musicWidgetController.override(musicWidgetController.getCurrentData());
        }
    }

    public boolean isTradeable(int itemId) {
        ItemComposition comp = itemManager.getItemComposition(itemId);
        return comp != null && comp.isTradeable();
    }

    private boolean isEligibleForLocking(int itemId) {
        ItemComposition comp = itemManager.getItemComposition(itemId);
        return isEligibleForLocking(itemId, comp);
    }

    private boolean isEligibleForLocking(int itemId, ItemComposition comp) {
        return isEligibleForLocking(itemId, comp, null);
    }

    private boolean isEligibleForLocking(int itemId, ItemComposition comp, Set<Integer> unlockedSnapshot) {
        if (comp == null) {
            return false;
        }
        int canonicalItemId = itemManager.canonicalize(itemId);
        Set<Integer> unlocked = unlockedSnapshot != null ? unlockedSnapshot : rolledItemsManager.getRolledItems();
        ItemAttributes attributes = ItemAttributes.from(comp);
        return ItemEligibility.shouldInclude(
                attributes,
                itemId,
                canonicalItemId,
                config,
                unlocked,
                this::isNotTracked);
    }

    public boolean isNotTracked(int itemId) {
        return itemId == 995 || itemId == 13191 || itemId == 13190 ||
                itemId == 7587 || itemId == 7588 || itemId == 7589 || itemId == 7590 || itemId == 7591;
    }

    public boolean isInPlay(int itemId) {
        return allTradeableItems.contains(itemId);
    }
}
