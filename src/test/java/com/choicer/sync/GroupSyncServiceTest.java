package com.choicer.sync;

import com.choicer.managers.ObtainedItemsManager;
import com.choicer.managers.RolledItemsManager;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class GroupSyncServiceTest
{
    private GroupSyncService service;
    private SupabaseApiClient apiClient;
    private SupabaseAuthService authService;
    private UnlockQueue unlockQueue;
    private RolledItemsManager rolledItemsManager;
    private ObtainedItemsManager obtainedItemsManager;
    private ConfigManager configManager;
    private EventBus eventBus;
    private Client client;
    private final Map<String, String> configStore = new HashMap<>();

    @Before
    public void setUp() throws Exception
    {
        MockitoAnnotations.openMocks(this);
        service = new GroupSyncService();
        apiClient = mock(SupabaseApiClient.class);
        authService = mock(SupabaseAuthService.class);
        unlockQueue = mock(UnlockQueue.class);
        rolledItemsManager = mock(RolledItemsManager.class);
        obtainedItemsManager = mock(ObtainedItemsManager.class);
        configManager = mock(ConfigManager.class);
        eventBus = mock(EventBus.class);
        client = mock(Client.class);

        bindConfigManager(configManager, configStore);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(authService.ensureSessionSync()).thenReturn(new SupabaseAuthService.Session("token", "refresh", 0L, "user-1"));

        injectField(service, "apiClient", apiClient);
        injectField(service, "authService", authService);
        injectField(service, "unlockQueue", unlockQueue);
        injectField(service, "rolledItemsManager", rolledItemsManager);
        injectField(service, "obtainedItemsManager", obtainedItemsManager);
        injectField(service, "configManager", configManager);
        injectField(service, "eventBus", eventBus);
        injectField(service, "client", client);
        injectField(service, "ioExecutor", new DirectExecutorService());
        injectField(service, "scheduler", null);
    }

    @Test
    public void joinGroupEmptyCodeUpdatesStatus()
    {
        AtomicReference<GroupSyncStatus> statusRef = new AtomicReference<>();
        service.setStatusListener(statusRef::set);

        service.joinGroup("   ");

        GroupSyncStatus status = statusRef.get();
        assertNotNull(status);
        assertEquals("error", status.state);
        assertEquals("Join code is empty", status.message);
        verifyNoInteractions(apiClient);
    }

    @Test
    public void joinGroupWhenDisabledDoesNotCallApi()
    {
        configStore.put(key(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.ENABLED), "false");
        AtomicReference<GroupSyncStatus> statusRef = new AtomicReference<>();
        service.setStatusListener(statusRef::set);

        service.joinGroup("ABCD");

        GroupSyncStatus status = statusRef.get();
        assertNotNull(status);
        assertEquals("disabled", status.state);
        assertEquals("Enable sync to join", status.message);
        verifyNoInteractions(apiClient);
    }

    @Test
    public void joinGroupSuccessStoresGroupAndAppliesState()
    {
        configStore.put(key(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.ENABLED), "true");
        UUID groupId = UUID.randomUUID();
        JoinGroupResult joinResult = new JoinGroupResult();
        joinResult.group_id = groupId;
        joinResult.max_members = 7;
        when(apiClient.joinGroupSync("ABCD")).thenReturn(joinResult);

        GroupStateDto state = new GroupStateDto();
        state.version = 3L;
        state.updatedAt = Instant.parse("2024-01-01T00:00:00Z");
        state.unlocks = new HashMap<>();
        state.unlocks.put("rolled:item:101", true);
        state.unlocks.put("obtained:202", true);
        when(apiClient.getGroupStateSync(groupId)).thenReturn(state);
        when(apiClient.getGroupMembersSync(groupId)).thenReturn(Collections.emptyList());
        when(rolledItemsManager.getRolledItems()).thenReturn(Collections.singleton(999));

        service.joinGroup("  ABCD  ");

        verify(apiClient).joinGroupSync("ABCD");
        verify(configManager).setConfiguration(GroupSyncConfigKeys.GROUP, "group_sync.group_id", groupId.toString());
        verify(configManager).setConfiguration(GroupSyncConfigKeys.GROUP, "group_sync.max_members", "7");
        verify(configManager).setConfiguration(GroupSyncConfigKeys.GROUP, "group_sync.last_version", "0");

        ArgumentCaptor<Set<Integer>> rolledCaptor = ArgumentCaptor.forClass(Set.class);
        verify(rolledItemsManager).overwriteRolledItems(rolledCaptor.capture(), anyLong());
        assertEquals(Collections.singleton(101), rolledCaptor.getValue());

        ArgumentCaptor<Set<Integer>> obtainedCaptor = ArgumentCaptor.forClass(Set.class);
        verify(obtainedItemsManager).overwriteObtainedItems(obtainedCaptor.capture(), anyLong());
        assertEquals(Collections.singleton(202), obtainedCaptor.getValue());

        ArgumentCaptor<GroupStateUpdated> eventCaptor = ArgumentCaptor.forClass(GroupStateUpdated.class);
        verify(eventBus).post(eventCaptor.capture());
        assertEquals(groupId, eventCaptor.getValue().getGroupId());
        assertEquals(3L, eventCaptor.getValue().getVersion());
    }

    @Test
    public void createGroupSuccessStoresJoinCodeAndAppliesState()
    {
        configStore.put(key(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.ENABLED), "true");
        UUID groupId = UUID.randomUUID();
        CreateGroupResult createResult = new CreateGroupResult();
        createResult.group_id = groupId;
        createResult.join_code = "JOIN123";
        when(apiClient.createGroupSync("My Group", 5)).thenReturn(createResult);

        GroupStateDto state = new GroupStateDto();
        state.version = 1L;
        state.updatedAt = Instant.parse("2024-01-01T00:00:00Z");
        state.unlocks = new HashMap<>();
        state.unlocks.put("rolled:100", true);
        state.unlocks.put("obtained:200", true);
        when(apiClient.getGroupStateSync(groupId)).thenReturn(state);
        when(apiClient.getGroupMembersSync(groupId)).thenReturn(Collections.emptyList());
        when(rolledItemsManager.isRolled(100)).thenReturn(false);
        when(obtainedItemsManager.isObtained(200)).thenReturn(false);

        AtomicReference<CreateGroupResult> resultRef = new AtomicReference<>();
        service.createGroup("My Group", 5, resultRef::set);

        assertEquals(createResult, resultRef.get());
        verify(configManager).setConfiguration(GroupSyncConfigKeys.GROUP, "group_sync.group_id", groupId.toString());
        verify(configManager).setConfiguration(GroupSyncConfigKeys.GROUP, "group_sync.max_members", "5");
        verify(configManager).setConfiguration(GroupSyncConfigKeys.GROUP, "group_sync.last_version", "0");
        verify(configManager).setConfiguration(GroupSyncConfigKeys.GROUP, "group_sync.join_code", "JOIN123");

        verify(rolledItemsManager).clearLocalForCurrentPlayer();
        verify(obtainedItemsManager).clearLocalForCurrentPlayer();
        verify(rolledItemsManager).markRolled(100);
        verify(obtainedItemsManager).markObtained(200);
    }

    @Test
    public void createGroupMissingNameUpdatesStatus()
    {
        configStore.put(key(GroupSyncConfigKeys.GROUP, GroupSyncConfigKeys.ENABLED), "true");
        AtomicReference<GroupSyncStatus> statusRef = new AtomicReference<>();
        service.setStatusListener(statusRef::set);

        service.createGroup("   ", 5, null);

        GroupSyncStatus status = statusRef.get();
        assertNotNull(status);
        assertEquals("error", status.state);
        assertEquals("Create fields incomplete", status.message);
        verify(apiClient, never()).createGroupSync(anyString(), anyInt());
    }

    private static void injectField(Object target, String name, Object value) throws Exception
    {
        Field field = GroupSyncService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void bindConfigManager(ConfigManager manager, Map<String, String> store)
    {
        doAnswer(invocation -> store.get(key(invocation.getArgument(0), invocation.getArgument(1))))
                .when(manager).getConfiguration(anyString(), anyString());
        doAnswer(invocation -> {
            store.put(key(invocation.getArgument(0), invocation.getArgument(1)), invocation.getArgument(2));
            return null;
        }).when(manager).setConfiguration(anyString(), anyString(), anyString());
        doAnswer(invocation -> {
            store.remove(key(invocation.getArgument(0), invocation.getArgument(1)));
            return null;
        }).when(manager).unsetConfiguration(anyString(), anyString());
    }

    private static String key(String group, String key)
    {
        return group + "::" + key;
    }

    private static final class DirectExecutorService extends AbstractExecutorService implements ExecutorService
    {
        private volatile boolean shutdown = false;

        @Override
        public void shutdown()
        {
            shutdown = true;
        }

        @Override
        public java.util.List<Runnable> shutdownNow()
        {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown()
        {
            return shutdown;
        }

        @Override
        public boolean isTerminated()
        {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
        {
            return true;
        }

        @Override
        public void execute(Runnable command)
        {
            command.run();
        }
    }
}
