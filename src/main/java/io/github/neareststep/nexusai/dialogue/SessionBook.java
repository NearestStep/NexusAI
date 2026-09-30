package io.github.neareststep.nexusai.dialogue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One open dialogue session per player. Ending conditions are timeout, distance, command, and quit.
 */
public final class SessionBook {

    private final ConcurrentHashMap<UUID, Session> open = new ConcurrentHashMap<>();

    public boolean has(UUID player) {
        return player != null && open.containsKey(player);
    }

    public Optional<Session> get(UUID player) {
        if (player == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(open.get(player));
    }

    public Session open(
            UUID player,
            String characterId,
            String world,
            double x,
            double y,
            double z,
            int maxReplies,
            int timeoutSeconds,
            int leaveRadius,
            long nowMillis
    ) {
        Session session = new Session(
                player,
                characterId,
                world,
                x,
                y,
                z,
                nowMillis,
                0,
                Math.max(1, maxReplies),
                Math.max(0, timeoutSeconds),
                Math.max(0, leaveRadius)
        );
        open.put(player, session);
        return session;
    }

    public void touch(UUID player, long nowMillis) {
        Session session = open.get(player);
        if (session != null) {
            session.lastActivity = nowMillis;
        }
    }

    public void addReply(UUID player) {
        Session session = open.get(player);
        if (session != null) {
            session.replies++;
        }
    }

    public Optional<Session> close(UUID player) {
        if (player == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(open.remove(player));
    }

    public List<Session> expired(long nowMillis) {
        List<Session> ended = new ArrayList<>();
        for (Session session : open.values()) {
            if (session.timedOut(nowMillis)) {
                ended.add(session);
            }
        }
        for (Session session : ended) {
            open.remove(session.playerId, session);
        }
        return ended;
    }

    /**
     * @return the closed session when the player left the radius or changed world
     */
    public Optional<Session> left(UUID player, String world, double x, double y, double z) {
        Session session = open.get(player);
        if (session == null || !session.tooFar(world, x, y, z)) {
            return Optional.empty();
        }
        open.remove(player, session);
        return Optional.of(session);
    }

    public static final class Session {
        private final UUID playerId;
        private final String characterId;
        private final String world;
        private final double x;
        private final double y;
        private final double z;
        private volatile long lastActivity;
        private int replies;
        private final int maxReplies;
        private final int timeoutSeconds;
        private final int leaveRadius;

        Session(
                UUID playerId,
                String characterId,
                String world,
                double x,
                double y,
                double z,
                long lastActivity,
                int replies,
                int maxReplies,
                int timeoutSeconds,
                int leaveRadius
        ) {
            this.playerId = playerId;
            this.characterId = characterId;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastActivity = lastActivity;
            this.replies = replies;
            this.maxReplies = maxReplies;
            this.timeoutSeconds = timeoutSeconds;
            this.leaveRadius = leaveRadius;
        }

        public UUID playerId() {
            return playerId;
        }

        public String characterId() {
            return characterId;
        }

        public int replies() {
            return replies;
        }

        public int maxReplies() {
            return maxReplies;
        }

        public boolean repliesExhausted() {
            return replies >= maxReplies;
        }

        public boolean timedOut(long nowMillis) {
            return timeoutSeconds > 0 && nowMillis - lastActivity >= timeoutSeconds * 1000L;
        }

        public boolean tooFar(String nowWorld, double nx, double ny, double nz) {
            if (leaveRadius <= 0) {
                return false;
            }
            if (world == null || nowWorld == null || !world.equals(nowWorld)) {
                return true;
            }
            double dx = nx - x;
            double dy = ny - y;
            double dz = nz - z;
            double radius = leaveRadius;
            return dx * dx + dy * dy + dz * dz > radius * radius;
        }
    }
}
