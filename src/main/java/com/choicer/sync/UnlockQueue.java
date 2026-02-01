package com.choicer.sync;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static net.runelite.client.RuneLite.RUNELITE_DIR;

@Slf4j
@Singleton
public class UnlockQueue
{
    private static final String FILE_NAME = "choicer_group_queue.json";
    private static final int MAX_QUEUE = 1000;
    private static final Type LIST_TYPE = new TypeToken<List<UnlockEvent>>(){}.getType();

    private final Gson gson;
    private final Deque<UnlockEvent> queue = new ArrayDeque<>();
    private final Object lock = new Object();

    private ExecutorService executor;

    @Inject
    public UnlockQueue(Gson gson)
    {
        this.gson = gson;
    }

    public void setExecutor(ExecutorService executor)
    {
        this.executor = executor;
    }

    public void loadFromDisk()
    {
        Path file = getFilePath();
        if (file == null || !Files.exists(file))
        {
            return;
        }

        try (Reader reader = Files.newBufferedReader(file))
        {
            List<UnlockEvent> entries = gson.fromJson(reader, LIST_TYPE);
            if (entries == null) return;
            synchronized (lock)
            {
                queue.clear();
                for (UnlockEvent entry : entries)
                {
                    if (entry == null || entry.eventId == null || entry.groupId == null || entry.unlockKey == null)
                    {
                        continue;
                    }
                    queue.addLast(entry);
                }
            }
        }
        catch (Exception e)
        {
            log.warn("Choicer group queue: failed to load", e);
        }
    }

    public void enqueue(UUID groupId, String unlockKey, Instant clientTs)
    {
        UnlockEvent event = new UnlockEvent(UUID.randomUUID(), groupId, unlockKey, clientTs);
        synchronized (lock)
        {
            if (queue.size() >= MAX_QUEUE)
            {
                queue.removeFirst();
            }
            queue.addLast(event);
        }
        persistAsync();
    }

    public List<UnlockEvent> snapshot()
    {
        synchronized (lock)
        {
            return new ArrayList<>(queue);
        }
    }

    public void removeById(UUID eventId)
    {
        if (eventId == null) return;
        synchronized (lock)
        {
            queue.removeIf(e -> eventId.equals(e.eventId));
        }
        persistAsync();
    }

    public int size()
    {
        synchronized (lock)
        {
            return queue.size();
        }
    }

    public void clear()
    {
        synchronized (lock)
        {
            queue.clear();
        }
        persistAsync();
    }

    private void persistAsync()
    {
        if (executor == null) return;
        executor.submit(this::persistSync);
    }

    private void persistSync()
    {
        Path file = getFilePath();
        if (file == null) return;
        List<UnlockEvent> snap = snapshot();

        try
        {
            Files.createDirectories(file.getParent());
        }
        catch (IOException e)
        {
            log.warn("Choicer group queue: failed to create directory", e);
            return;
        }

        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp))
        {
            gson.toJson(snap, writer);
        }
        catch (IOException e)
        {
            log.warn("Choicer group queue: failed to write", e);
            return;
        }

        try
        {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e)
        {
            try
            {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException ex)
            {
                log.warn("Choicer group queue: failed to move", ex);
            }
        }
    }

    private Path getFilePath()
    {
        try
        {
            return RUNELITE_DIR.toPath().resolve(FILE_NAME);
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
