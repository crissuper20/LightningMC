package com.crissuper20.lightning.util;

import com.crissuper20.lightning.LightningPlugin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/**
 * Small util wrapper for embedded QRMap generator code.
 * - Primary API: giveMap(player, content) -> tries to hand the generated map to the player.
 * - Secondary API: try to return an ItemStack if an embedded generator exposes one.
 *
 * Placed in the util package next to DebugLogger for easy reuse.
 */
public final class QRMapGenerator {
    private QRMapGenerator() {}

    /**
     * Try to generate a map ItemStack for the given player + content.
     * If the embedded generator returns an ItemStack this is returned.
     * If no ItemStack-producing method is available this will attempt to hand the map
     * to the player (side-effect) and return null.
     */
    public static ItemStack generate(Player player, String content) {
        LightningPlugin plugin = LightningPlugin.getInstance();
        if (plugin != null) plugin.getDebugLogger().debug("QRMapGenerator.generate called for " + player.getName());

        // Try direct embedded generator classes first
        String[] candidates = new String[] {
            "com.crissuper20.lightning.qrmap.QRMapGenerator",
            "com.crissuper20.lightning.qrmap.QRMapAPI",
            "com.crissuper20.lightning.qrmap.QRMap",
            "com.crissuper20.lightning.qrmap.QRUtils"
        };

        for (String clsName : candidates) {
            try {
                Class<?> cls = Class.forName(clsName);

                // common static signatures that return an ItemStack
                Method m = findMethod(cls, "generate", Player.class, String.class);
                if (m != null && ItemStack.class.isAssignableFrom(m.getReturnType())) {
                    Object res = m.invoke(null, player, content);
                    if (res instanceof ItemStack) return (ItemStack) res;
                }

                m = findMethod(cls, "generate", String.class);
                if (m != null && ItemStack.class.isAssignableFrom(m.getReturnType())) {
                    Object res = m.invoke(null, content);
                    if (res instanceof ItemStack) return (ItemStack) res;
                }

                m = findMethod(cls, "createMap", String.class);
                if (m != null && ItemStack.class.isAssignableFrom(m.getReturnType())) {
                    Object res = m.invoke(null, content);
                    if (res instanceof ItemStack) return (ItemStack) res;
                }
            } catch (ClassNotFoundException ignored) {
                // try next candidate
            } catch (Throwable t) {
                if (plugin != null) plugin.getDebugLogger().error("QRMapGenerator.generate reflection error for " + clsName, t);
                return null;
            }
        }

        // Fallback: try side-effect giving (this will add map to player's inventory if supported)
        giveMap(player, content);
        return null;
    }

    /**
     * Attempts to hand a generated map directly to the player.
     * Returns true on success, false otherwise.
     */
    public static boolean giveMap(Player player, String content) {
        LightningPlugin plugin = LightningPlugin.getInstance();
        if (plugin != null) plugin.getDebugLogger().debug("QRMapGenerator.giveMap called for " + player.getName());

        // Prefer the QRMap shim / renderer if present
        try {
            if (QRMapRenderer.createMapForPlayer(plugin, player, content)) return true;
        } catch (Throwable ignored) { /* continue to QRMap shim */ }

        try {
            return QRMap.giveMap(player, content);
        } catch (Throwable t) {
            if (plugin != null) plugin.getDebugLogger().error("QRMapGenerator.giveMap failed", t);
            return false;
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}