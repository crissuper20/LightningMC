package com.crissuper20.lightning.util;

import com.crissuper20.lightning.LightningPlugin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/**
 * QRMap utility shim adapted for embedding.
 *
 * - Removed JavaPlugin lifecycle responsibilities (do NOT keep this as a Bukkit plugin main).
 * - Exposes a small, stable API used by QRMapRenderer / InvoiceCommand:
 *     QRMap.giveMap(player, content) -> attempts to generate a map ItemStack or hand it to the player.
 *
 * This shim delegates to classes inside the embedded qrmap/ package (QRMapGenerator, QRMapAPI, QRUtils, ...)
 * using conservative reflection lookups so we don't need to rewrite all original sources at once.
 */
public final class QRMap {
    private QRMap() {}

    /**
     * Try to generate a QR map for the given player containing `content`.
     * Returns true on success (map given to player or generator returned true/ItemStack).
     */
    public static boolean giveMap(Player player, String content) {
        LightningPlugin plugin = LightningPlugin.getInstance();
        if (plugin != null) {
            plugin.getDebugLogger().debug("QRMap.giveMap invoked for player " + player.getName());
        }

        // Candidate generator classes (embedded sources). We try a few common method shapes.
        String[] candidates = new String[] {
            "com.crissuper20.lightning.qrmap.QRMapGenerator",
            "com.crissuper20.lightning.qrmap.QRMapAPI",
            "com.crissuper20.lightning.qrmap.QRMap",
            "com.crissuper20.lightning.qrmap.QRUtils",
            // older upstream packages (if any)
            "com.gomania6.qrmap.QRMapGenerator",
            "com.gomania6.qrmap.QRMapAPI",
            "qrmap.QRMapGenerator",
            "qrmap.QRMapAPI"
        };

        for (String clsName : candidates) {
            try {
                Class<?> cls = Class.forName(clsName);

                // 1) static ItemStack generate(String)
                Method m = findMethod(cls, "generate", String.class);
                if (m != null && ItemStack.class.isAssignableFrom(m.getReturnType())) {
                    Object res = m.invoke(null, content);
                    if (handleResult(player, res)) return true;
                }

                // 2) static ItemStack generate(Player, String)
                m = findMethod(cls, "generate", Player.class, String.class);
                if (m != null && ItemStack.class.isAssignableFrom(m.getReturnType())) {
                    Object res = m.invoke(null, player, content);
                    if (handleResult(player, res)) return true;
                }

                // 3) static void giveMap(Player, String)
                m = findMethod(cls, "giveMap", Player.class, String.class);
                if (m != null && m.getReturnType() == void.class) {
                    m.invoke(null, player, content);
                    if (plugin != null) plugin.getDebugLogger().debug("QRMap: invoked giveMap(Player,String) on " + clsName);
                    return true;
                }

                // 4) static boolean createMap(Player, String)
                m = findMethod(cls, "createMap", Player.class, String.class);
                if (m != null && m.getReturnType() == boolean.class) {
                    Object r = m.invoke(null, player, content);
                    if (r instanceof Boolean && (Boolean) r) return true;
                }

                // 5) instance methods (try constructing)
                try {
                    Object inst = cls.getDeclaredConstructor().newInstance();

                    m = findMethod(cls, "generate", Player.class, String.class);
                    if (m != null) {
                        Object res = m.invoke(inst, player, content);
                        if (handleResult(player, res)) return true;
                    }

                    m = findMethod(cls, "generate", String.class);
                    if (m != null) {
                        Object res = m.invoke(inst, content);
                        if (handleResult(player, res)) return true;
                    }
                } catch (NoSuchMethodException ns) {
                    // no no-arg ctor — skip instance attempts
                }

            } catch (ClassNotFoundException cnf) {
                // try next candidate
            } catch (Throwable t) {
                if (plugin != null) plugin.getDebugLogger().error("QRMap.giveMap failed for " + clsName, t);
                return false;
            }
        }

        if (plugin != null) plugin.getDebugLogger().debug("QRMap.giveMap: no suitable generator found in embedded qrmap/");
        return false;
    }

    private static boolean handleResult(Player player, Object res) {
        if (res == null) return false;
        if (res instanceof ItemStack) {
            player.getInventory().addItem((ItemStack) res);
            return true;
        }
        if (res instanceof Boolean) {
            return (Boolean) res;
        }
        return false;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
