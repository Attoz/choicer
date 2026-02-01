package com.choicer.managers;

import com.choicer.account.AccountManager;
import com.choicer.persist.ConfigPersistence;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;

import static net.runelite.client.RuneLite.RUNELITE_DIR;

@Slf4j
@Singleton
public class ObtainedItemsManager
{
    private static final int MAX_BACKUPS = 10;
    private static final String CFG_KEY_SOLO = "obtained";
    private static final String CFG_KEY_GROUP = "group_obtained";
    private static final String FILE_NAME_SOLO = "choicer_obtained.json";
    private static final String FILE_NAME_GROUP = "choicer_group_obtained.json";
    private static final String BACKUP_TS_PATTERN = "yyyyMMddHHmmss";
    private static final long CONFIG_DEBOUNCE_MS = 3000L;
    private static final long SELF_WRITE_GRACE_MS = 1500L;
    private static final long FS_DEBOUNCE_MS = 200L;
    private static final Type SET_TYPE = new TypeToken<Set<Integer>>(){}.getType();
    private final Set<Integer> obtainedItems = Collections.synchronizedSet(new LinkedHashSet<>());

    @Inject private AccountManager accountManager;
    @Inject private Gson gson;
    @Inject private ConfigPersistence configPersistence;

    @Setter private ExecutorService executor; // file writes & cloud mirror
    @Setter private Runnable onChange;

    private volatile long lastConfigWriteMs = 0L;
    private volatile boolean configWriteWarned = false;
    private volatile boolean dirty = false;

    private WatchService watchService;
    private volatile boolean watcherRunning = false;
    private volatile long lastSelfWriteMs = 0L;
    private Thread watcherThread;
    private volatile boolean groupMode = false;

    public boolean isObtained(int itemId) { return obtainedItems.contains(itemId); }

    /** Return an immutable snapshot to avoid leaking the synchronizedSet. */
    public Set<Integer> getObtainedItems()
    {
        synchronized (obtainedItems)
        {
            return Collections.unmodifiableSet(new LinkedHashSet<>(obtainedItems));
        }
    }

    public void markObtained(int itemId)
    {
        if (obtainedItems.add(itemId))
        {
            dirty = true;
            saveObtainedItems();
            safeNotifyChange();
        }
    }

    /** Replace local obtained set with the provided snapshot and persist immediately. */
    public void overwriteObtainedItems(Set<Integer> snapshot, long stampMillis)
    {
        if (snapshot == null) snapshot = Collections.emptySet();
        synchronized (obtainedItems)
        {
            obtainedItems.clear();
            obtainedItems.addAll(snapshot);
        }
        dirty = true;
        safeNotifyChange();
        saveInternal(stampMillis, false);
    }

    public void loadObtainedItems()
    {
        reconcileWithCloud(false);
        safeNotifyChange();
    }

    public void setGroupMode(boolean enabled)
    {
        this.groupMode = enabled;
    }

    public boolean isGroupMode()
    {
        return groupMode;
    }

    /** Normal save: disk + debounced cloud with current time. */
    public void saveObtainedItems()
    {
        saveInternal(System.currentTimeMillis(), true);
    }

    /** Clear local + cloud data for the current player (for testing/reset). */
    public void clearAllForCurrentPlayer()
    {
        String player = accountManager.getPlayerName();
        if (player == null || player.isEmpty())
        {
            return;
        }

        synchronized (obtainedItems)
        {
            obtainedItems.clear();
        }
        dirty = false;

        log.info("Choicer obtained clear: player={}, localFile={}", player, currentFileName());
        deleteLocalIfExists(currentFileName());

        long now = System.currentTimeMillis();
        try
        {
            log.info("Choicer obtained clear: writing empty cloud set at ts={}", now);
            configPersistence.writeStampedSet(player, currentCfgKey(), Collections.emptySet(), now);
        }
        catch (Exception e)
        {
            log.error("Choicer: failed to clear obtained cloud state", e);
        }

        safeNotifyChange();
    }

    /** Clear local obtained set and file only (no cloud changes). */
    public void clearLocalForCurrentPlayer()
    {
        String player = accountManager.getPlayerName();
        if (player == null || player.isEmpty())
        {
            return;
        }

        synchronized (obtainedItems)
        {
            obtainedItems.clear();
        }
        dirty = false;

        log.info("Choicer obtained clear local: player={}, localFile={}", player, currentFileName());
        deleteLocalIfExists(currentFileName());

        safeNotifyChange();
    }

    /** Live-reload: start watching the JSON for CREATE/MODIFY/DELETE. */
    public void startWatching()
    {
        if (watcherRunning) return;
        Path file = safeGetFilePathOrNull(currentFileName());
        if (file == null) return;

        try
        {
            watchService = FileSystems.getDefault().newWatchService();
            file.getParent().register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
        }
        catch (IOException e)
        {
            closeWatchServiceQuietly();
            log.error("Obtained watcher: could not register", e);
            return;
        }

        watcherRunning = true;
        final String target = file.getFileName().toString();
        watcherThread = new Thread(() -> runWatcherLoop(target), "Choicer-Obtained-Watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    public void stopWatching()
    {
        watcherRunning = false;
        if (watcherThread != null) watcherThread.interrupt();
        closeWatchServiceQuietly();
        watcherThread = null;
    }

    /** Flush synchronously on shutdown if dirty. */
    public void flushIfDirtyOnExit()
    {
        if (!dirty) return;
        Path file = safeGetFilePathOrNull(currentFileName());
        if (file == null) return;

        try
        {
            rotateBackupIfExists(file);
            Set<Integer> snap = snapshotObtained();
            writeJsonAtomic(file, snap, true);
            mirrorToCloud(System.currentTimeMillis(), false, snap);
            dirty = false;
        }
        catch (IOException e)
        {
            log.error("Shutdown flush failed for obtained items (local saves may be stale).", e);
        }
        catch (Exception e)
        {
            log.error("Shutdown flush: failed to mirror obtained set to ConfigManager.", e);
        }
    }

    private void reconcileWithCloud(boolean runtime)
    {
        String player = accountManager.getPlayerName();
        if (player == null) return;

        Path newFile = safeGetFilePathOrNull(currentFileName());
        if (newFile == null) return;

        boolean newFileExisted = Files.exists(newFile);

        Set<Integer> localNew = readLocalJson(newFile);
        Set<Integer> local;
        if (!localNew.isEmpty() || newFileExisted)
        {
            local = localNew;
        }
        else
        {
            local = new LinkedHashSet<>();
        }

        // Cloud: new only
        ConfigPersistence.StampedSet cloudStampedNew = readCloud(player, currentCfgKey());
        Set<Integer> cloudNew = new LinkedHashSet<>(cloudStampedNew.data);
        long cloudTs = cloudStampedNew.ts;

        long localMtime = newFileExisted ? safeLastModified(newFile) : 0L;

        // LWW between local and (merged) cloud
        Set<Integer> winner;
        Long winnerStamp = null;
        boolean needPersist;

        String winnerSource;
        if (localMtime > cloudTs) { winner = local; winnerStamp = localMtime; needPersist = true; winnerSource = "local"; }
        else if (cloudTs > localMtime) { winner = cloudNew; winnerStamp = cloudTs; needPersist = true; winnerSource = "cloud"; }
        else { winner = local; needPersist = !newFileExisted; winnerSource = "local"; }

        log.info("Choicer obtained reconcile (runtime={}): player={}, localPath={}, localExists={}, localCount={}, localMtime={}, cloudCount={}, cloudTs={}, winner={}, needPersist={}",
                runtime,
                player,
                newFile,
                newFileExisted,
                local.size(),
                localMtime,
                cloudNew.size(),
                cloudTs,
                winnerSource,
                needPersist);

        synchronized (obtainedItems)
        {
            obtainedItems.clear();
            obtainedItems.addAll(winner);
        }
        if (needPersist)
        {
            long stamp = (winnerStamp != null) ? winnerStamp : System.currentTimeMillis();
            saveInternal(stamp, false); // bypass debounce during reconcile
        }

        syncInactiveModeWithCloud(runtime);
        dirty = false;
    }

    /** Disk write + cloud mirror (debounced or immediate). */
    private void saveInternal(long stampMillis, boolean debounced)
    {
        if (!isExecutorAvailable())
        {
            log.error("ObtainedItemsManager: executor unavailable; skipping save");
            return;
        }

        executor.submit(() ->
        {
            Path file = safeGetFilePathOrNull(currentFileName());
            if (file == null)
            {
                log.error("ObtainedItemsManager: file path unavailable; skipping save");
                return;
            }
            try
            {
                rotateBackupIfExists(file);
                Set<Integer> snap = snapshotObtained();
                writeJsonAtomic(file, snap, true);
                mirrorToCloud(stampMillis, debounced, snap);
                dirty = false;
            }
            catch (IOException e)
            {
                log.error("Error saving obtained items", e);
            }
        });
    }

    /** Mirror to cloud, optionally debounced; uses provided snapshot to avoid re-locking. */
    private void mirrorToCloud(long stampMillis, boolean debounced, Set<Integer> snapshot)
    {
        long now = System.currentTimeMillis();
        if (debounced && (now - lastConfigWriteMs < CONFIG_DEBOUNCE_MS)) return;
        lastConfigWriteMs = now;

        String player = accountManager.getPlayerName();
        if (player == null || player.isEmpty() || executor == null) return;

        final Set<Integer> snap = (snapshot != null) ? snapshot : snapshotObtained();

        executor.submit(() ->
        {
            try
            {
                configPersistence.writeStampedSetIfNewer(player, currentCfgKey(), snap, stampMillis);
            }
            catch (Exception e)
            {
                if (!configWriteWarned)
                {
                    configWriteWarned = true;
                    log.error("Choicer: failed to mirror obtained set to ConfigManager (local saves intact).", e);
                }
            }
        });
    }

    private boolean isExecutorAvailable()
    {
        if (executor == null) return false;
        if (executor instanceof java.util.concurrent.ThreadPoolExecutor)
        {
            java.util.concurrent.ThreadPoolExecutor tpe = (java.util.concurrent.ThreadPoolExecutor) executor;
            return !tpe.isShutdown() && !tpe.isTerminated();
        }
        return true;
    }

    private void safeNotifyChange()
    {
        Runnable cb = onChange;
        if (cb != null)
        {
            try { cb.run(); }
            catch (Throwable t) { log.error("onChange threw", t); }
        }
    }

    private String currentFileName()
    {
        return groupMode ? FILE_NAME_GROUP : FILE_NAME_SOLO;
    }

    private String currentCfgKey()
    {
        return groupMode ? CFG_KEY_GROUP : CFG_KEY_SOLO;
    }

    private Path getFilePath(String fileName) throws IOException
    {
        String name = accountManager.getPlayerName();
        if (name == null) throw new IOException("Player name is null");
        Path dir = RUNELITE_DIR.toPath().resolve("choicer").resolve(name);
        Files.createDirectories(dir);
        return dir.resolve(fileName);
    }

    private Path safeGetFilePathOrNull(String fileName)
    {
        try { return getFilePath(fileName); }
        catch (IOException ioe) { return null; }
    }

    private void deleteLocalIfExists(String fileName)
    {
        try
        {
            Path path = safeGetFilePathOrNull(fileName);
            if (path != null)
            {
                boolean deleted = Files.deleteIfExists(path);
                log.info("Choicer obtained clear: deleteLocal path={}, deleted={}", path, deleted);
            }
        }
        catch (IOException ioe)
        {
            log.warn("Choicer: failed to delete {}", fileName, ioe);
        }
    }

    /** Windows-safe: COPY current file to a timestamped backup with small retries; then prune. */
    private void rotateBackupIfExists(Path file) throws IOException
    {
        if (!Files.exists(file)) return;

        Path backups = file.getParent().resolve("backups");
        Files.createDirectories(backups);

        String ts = new SimpleDateFormat(BACKUP_TS_PATTERN).format(new Date());
        Path bak = backups.resolve(file.getFileName() + "." + ts + ".bak");

        final int maxAttempts = 5;
        for (int attempt = 1; ; attempt++)
        {
            try
            {
                Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
                break;
            }
            catch (FileSystemException fse)
            {
                if (attempt >= maxAttempts)
                {
                    log.error("Backup copy failed after {} attempts for {}", attempt, file, fse);
                    break;
                }
                try { Thread.sleep(50L * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        try (java.util.stream.Stream<Path> stream = Files.list(backups))
        {
            stream
                    .filter(p -> p.getFileName().toString().startsWith(file.getFileName() + "."))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .skip(MAX_BACKUPS)
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }

    /** Write JSON to .tmp and atomically replace the main file; mark self-write for watcher echo suppression. */
    private void writeJsonAtomic(Path file, Set<Integer> data, boolean markSelfWrite) throws IOException
    {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp)) { gson.toJson(data, w); }
        safeMove(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        if (markSelfWrite)
        {
            lastSelfWriteMs = System.currentTimeMillis();
        }
    }

    /** Move with fallback when ATOMIC_MOVE not supported. */
    private void safeMove(Path source, Path target, CopyOption... opts) throws IOException
    {
        try
        {
            Files.move(source, target, opts);
        }
        catch (AtomicMoveNotSupportedException | AccessDeniedException ex)
        {
            Set<CopyOption> fallback = new HashSet<>(Arrays.asList(opts));
            fallback.remove(StandardCopyOption.ATOMIC_MOVE);
            fallback.add(StandardCopyOption.REPLACE_EXISTING);
            Files.move(source, target, fallback.toArray(new CopyOption[0]));
        }
    }

    private long safeLastModified(Path file)
    {
        try { return Files.getLastModifiedTime(file).toMillis(); }
        catch (IOException e) { return 0L; }
    }

    private Set<Integer> readLocalJson(Path file)
    {
        Set<Integer> local = new LinkedHashSet<>();
        if (file == null || !Files.exists(file)) return local;
        try (Reader r = Files.newBufferedReader(file))
        {
            Set<Integer> loaded = gson.fromJson(r, SET_TYPE);
            if (loaded != null) local.addAll(loaded);
        }
        catch (IOException e)
        {
            log.error("Error reading obtained items JSON", e);
        }
        log.info("Choicer obtained readLocalJson: path={}, count={}", file, local.size());
        return local;
    }

    private ConfigPersistence.StampedSet readCloud(String player, String key)
    {
        try
        {
            ConfigPersistence.StampedSet stamped = configPersistence.readStampedSet(player, key);
            log.info("Choicer obtained readCloud: player={}, key={}, count={}, ts={}",
                    player, key, stamped.data.size(), stamped.ts);
            return stamped;
        }
        catch (Exception e) { return new ConfigPersistence.StampedSet(new LinkedHashSet<>(), 0L); }
    }

    private void closeWatchServiceQuietly()
    {
        try { if (watchService != null) watchService.close(); }
        catch (IOException ignored) {}
        watchService = null;
    }

    private void runWatcherLoop(String target)
    {
        long lastHandled = 0L;
        try
        {
            while (watcherRunning)
            {
                WatchKey key;
                try { key = watchService.take(); }
                catch (InterruptedException | ClosedWatchServiceException ie) { break; }

                boolean relevant = false;
                for (WatchEvent<?> ev : key.pollEvents())
                {
                    Object ctx = ev.context();
                    if (ctx instanceof Path && ((Path) ctx).getFileName().toString().equals(target))
                    {
                        relevant = true;
                    }
                }
                if (!key.reset()) break;
                if (!relevant) continue;

                long now = System.currentTimeMillis();
                if (now - lastSelfWriteMs <= SELF_WRITE_GRACE_MS) continue;
                if (now - lastHandled < FS_DEBOUNCE_MS) continue;
                lastHandled = now;

                try
                {
                    reconcileWithCloud(true);
                    safeNotifyChange();
                }
                catch (Throwable t)
                {
                    log.error("Obtained watcher reconcile failed", t);
                }
            }
        }
        finally
        {
            closeWatchServiceQuietly();
            watcherRunning = false;
        }
    }

    /**
     * Keep the inactive save file (solo vs group) mirrored via RuneLite ConfigManager,
     * without touching the active in-memory set.
     */
    private void syncInactiveModeWithCloud(boolean runtime)
    {
        String player = accountManager.getPlayerName();
        if (player == null) return;

        boolean inactiveIsGroup = !groupMode;
        String fileName = inactiveIsGroup ? FILE_NAME_GROUP : FILE_NAME_SOLO;
        String key = inactiveIsGroup ? CFG_KEY_GROUP : CFG_KEY_SOLO;

        Path file = safeGetFilePathOrNull(fileName);
        if (file == null) return;

        boolean fileExists = Files.exists(file);
        Set<Integer> localNew = readLocalJson(file);
        Set<Integer> local = (!localNew.isEmpty() || fileExists) ? localNew : new LinkedHashSet<>();

        ConfigPersistence.StampedSet cloudStamped = readCloud(player, key);
        Set<Integer> cloud = new LinkedHashSet<>(cloudStamped.data);
        long cloudTs = cloudStamped.ts;

        long localMtime = fileExists ? safeLastModified(file) : 0L;

        if (localMtime == 0L && cloudTs == 0L && local.isEmpty() && cloud.isEmpty())
        {
            return;
        }

        if (localMtime > cloudTs)
        {
            try
            {
                configPersistence.writeStampedSetIfNewer(player, key, local, localMtime);
            }
            catch (Exception e)
            {
                log.debug("Choicer obtained inactive sync: failed to mirror local -> cloud (runtime={})", runtime, e);
            }
            return;
        }

        if (cloudTs > localMtime || (!fileExists && !cloud.isEmpty()))
        {
            try
            {
                rotateBackupIfExists(file);
                writeJsonAtomic(file, cloud, false);
            }
            catch (IOException e)
            {
                log.debug("Choicer obtained inactive sync: failed to mirror cloud -> local (runtime={})", runtime, e);
            }
        }
    }

    /** Take a consistent snapshot under the set's monitor. */
    private Set<Integer> snapshotObtained()
    {
        synchronized (obtainedItems)
        {
            return new LinkedHashSet<>(obtainedItems);
        }
    }
}
