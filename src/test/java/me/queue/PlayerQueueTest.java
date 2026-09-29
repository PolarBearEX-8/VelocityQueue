package me.queue;

import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerQueueTest {
    private Player player(UUID id) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        return p;
    }

    @Test
    void removalCompactsFifoAndRejoinGoesToTail() {
        PlayerQueue q = new PlayerQueue();
        Player first = player(UUID.randomUUID()), second = player(UUID.randomUUID()), third = player(UUID.randomUUID());
        q.addToQueue(first); q.addToQueue(second); q.addToQueue(third);
        var old = q.getEntry(first);
        q.removeFromQueue(first);
        assertEquals(1, q.getQueuePosition(second));
        assertEquals(2, q.getQueuePosition(third));
        q.addToQueue(first);
        q.removeEntry(old);
        assertEquals(3, q.getQueuePosition(first));
    }

    @Test
    void concurrentJoinCannotCreateDuplicates() throws Exception {
        PlayerQueue q = new PlayerQueue();
        Player p = player(UUID.randomUUID());
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 100; i++) executor.submit(() -> q.addToQueue(p));
        }
        assertEquals(1, q.queueLength());
    }

    @Test
    void oldSessionCannotRemoveReconnectedPlayer() {
        PlayerQueue q = new PlayerQueue();
        UUID id = UUID.randomUUID();
        Player old = player(id), replacement = player(id);
        q.addToQueue(old);
        q.addToQueue(replacement);
        q.removeFromQueue(old);
        assertFalse(q.isInQueue(old));
        assertEquals(1, q.getQueuePosition(replacement));
    }
}
