package com.crissuper20.lightning.scheduler;

import org.bukkit.entity.Player;

public interface CommonScheduler {
    void runTask(Runnable task);
    void runTask(Player player, Runnable task);
    void runTaskLater(Runnable task, long delayTicks);
    void runTaskLater(Player player, Runnable task, long delayTicks);
    void runTaskAsync(Runnable task);
    void runTaskTimer(Runnable task, long delayTicks, long periodTicks);
    void runTaskTimerAsync(Runnable task, long delayTicks, long periodTicks);
    
    /**
     * Schedules a task to run for a specific player if they are online.
     * Handles the difference between global sync (Paper) and region sync (Folia).
     */
    void runTaskForPlayer(java.util.UUID playerId, java.util.function.Consumer<Player> task);
}
