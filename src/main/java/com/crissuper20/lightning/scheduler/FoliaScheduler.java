package com.crissuper20.lightning.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class FoliaScheduler implements CommonScheduler {

    private final Plugin plugin;

    public FoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runTask(Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, (t) -> task.run());
    }

    @Override
    public void runTask(Player player, Runnable task) {
        player.getScheduler().run(plugin, (t) -> task.run(), null);
    }

    @Override
    public void runTaskLater(Runnable task, long delayTicks) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, (t) -> task.run(), delayTicks);
    }

    @Override
    public void runTaskLater(Player player, Runnable task, long delayTicks) {
        player.getScheduler().runDelayed(plugin, (t) -> task.run(), null, delayTicks);
    }

    @Override
    public void runTaskAsync(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, (t) -> task.run());
    }

    @Override
    public void runTaskTimer(Runnable task, long delayTicks, long periodTicks) {
        // Global region scheduler for timer
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, (t) -> task.run(), delayTicks, periodTicks);
    }

    @Override
    public void runTaskTimerAsync(Runnable task, long delayTicks, long periodTicks) {
        // Folia async scheduler uses time units, not ticks (usually 50ms per tick)
        long delayMs = delayTicks * 50;
        long periodMs = periodTicks * 50;
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, (t) -> task.run(), delayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void runTaskForPlayer(UUID playerId, Consumer<Player> task) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.getScheduler().run(plugin, (t) -> task.accept(player), null);
        }
    }
}
