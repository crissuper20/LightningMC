package com.crissuper20.lightning.util;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import com.crissuper20.lightning.LightningPlugin;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Secure QR Map Renderer
 * 
 */
public class QRMapRenderer extends MapRenderer {
    private final BufferedImage qrImage;
    private boolean rendered = false;

    // NBT keys for storing data
    private static final String NBT_KEY_INVOICE = "lightning_invoice";
    private static final String NBT_KEY_TYPE = "lightning_qr_type";
    private static final String NBT_KEY_TIMESTAMP = "lightning_timestamp";
    private static final String NBT_KEY_PLAYER_UUID = "lightning_player_uuid";

    private QRMapRenderer(String content) throws WriterException {
        this.qrImage = generateQRImage(content);
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (rendered) return;
        
        // Draw QR code onto map
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                if (x < qrImage.getWidth() && y < qrImage.getHeight()) {
                    Color color = new Color(qrImage.getRGB(x, y));
                    byte mapColor = MapPalette.matchColor(color);
                    canvas.setPixel(x, y, mapColor);
                }
            }
        }
        
        rendered = true;
    }

    private static BufferedImage generateQRImage(String content) throws WriterException {
        MultiFormatWriter writer = new MultiFormatWriter();
        BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, 128, 128);
        
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        
        // White background
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, 128, 128);
        
        // Black QR code
        g2d.setColor(Color.BLACK);
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                if (matrix.get(x, y)) {
                    g2d.fillRect(x, y, 1, 1);
                }
            }
        }
        
        g2d.dispose();
        return image;
    }

    /**
     * Create a QR map with invoice stored in NBT
     */
    public static boolean createMapForPlayer(LightningPlugin plugin, Player player, String content) {
        return createMapForPlayer(plugin, player, content, "payment");
    }

    /**
     * Create a QR map with custom type (payment, lndhub, etc.)
     * 
     */
    public static boolean createMapForPlayer(LightningPlugin plugin, Player player, String content, String type) {
        try {
            // Create map item
            ItemStack map = new ItemStack(Material.FILLED_MAP);
            MapMeta mapMeta = (MapMeta) map.getItemMeta();
            
            if (mapMeta == null) {
                plugin.getDebugLogger().error("Failed to create map meta");
                return false;
            }

            // Create new map view
            MapView view = plugin.getServer().createMap(player.getWorld());
            view.getRenderers().clear();
            view.addRenderer(new QRMapRenderer(content));
            
            // Set map view
            mapMeta.setMapView(view);
            
            // Prepare NBT keys
            NamespacedKey invoiceKey = new NamespacedKey(plugin, NBT_KEY_INVOICE);
            NamespacedKey typeKey = new NamespacedKey(plugin, NBT_KEY_TYPE);
            NamespacedKey timestampKey = new NamespacedKey(plugin, NBT_KEY_TIMESTAMP);
            NamespacedKey playerUuidKey = new NamespacedKey(plugin, NBT_KEY_PLAYER_UUID);
            
            if ("lndhub".equals(type)) {
                plugin.getDebugLogger().info("Creating secure lndhub map - credentials only in QR image");
            } else {
                // For payment invoices, store them (they're meant to be shared)
                mapMeta.getPersistentDataContainer().set(invoiceKey, PersistentDataType.STRING, content);
            }
            
            // Store metadata for all types
            mapMeta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
            mapMeta.getPersistentDataContainer().set(timestampKey, PersistentDataType.LONG, System.currentTimeMillis());
            mapMeta.getPersistentDataContainer().set(playerUuidKey, PersistentDataType.STRING, player.getUniqueId().toString());
            
            // Set display name based on type
            String displayName = "lndhub".equals(type) 
                ? "§6§l Wallet Connection" 
                : "§a§l Lightning Payment QR";
            mapMeta.setDisplayName(displayName);
            
            // Add lore to warn about security for lndhub maps
            if ("lndhub".equals(type)) {
                java.util.List<String> lore = new java.util.ArrayList<>();
                lore.add("§c§lKEEP THIS PRIVATE!");
                lore.add("§7Contains your wallet credentials");
                lore.add("§7Do not share with anyone!");
                lore.add("");
                lore.add("§8Created: " + new java.text.SimpleDateFormat("MMM dd, HH:mm").format(new java.util.Date()));
                mapMeta.setLore(lore);
            } else {
                java.util.List<String> lore = new java.util.ArrayList<>();
                lore.add("§7Scan to pay Lightning invoice");
                lore.add("§8Created: " + new java.text.SimpleDateFormat("MMM dd, HH:mm").format(new java.util.Date()));
                mapMeta.setLore(lore);
            }
            
            map.setItemMeta(mapMeta);
            
            // Add map to player's inventory
            player.getInventory().addItem(map);
            plugin.getDebugLogger().debug("Secure QR map created for " + player.getName() + " (type: " + type + ")");
            
            return true;
        } catch (WriterException e) {
            plugin.getDebugLogger().error("Failed to generate QR code", e);
            return false;
        } catch (Exception e) {
            plugin.getDebugLogger().error("Unexpected error creating QR map", e);
            return false;
        }
    }

    /**
     * Read invoice/content from a map's NBT data
     * Returns null if the map doesn't have stored data
     */
    public static String readInvoiceFromMap(LightningPlugin plugin, ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) {
            return null;
        }

        MapMeta mapMeta = (MapMeta) item.getItemMeta();
        if (mapMeta == null) {
            return null;
        }

        try {
            NamespacedKey typeKey = new NamespacedKey(plugin, NBT_KEY_TYPE);
            if (mapMeta.getPersistentDataContainer().has(typeKey, PersistentDataType.STRING)) {
                String type = mapMeta.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
                if ("lndhub".equals(type)) {
                    plugin.getDebugLogger().debug("Attempted to read lndhub map xd");
                    return null; 
                }
            }
            NamespacedKey invoiceKey = new NamespacedKey(plugin, NBT_KEY_INVOICE);
            if (mapMeta.getPersistentDataContainer().has(invoiceKey, PersistentDataType.STRING)) {
                String invoice = mapMeta.getPersistentDataContainer().get(invoiceKey, PersistentDataType.STRING);
                plugin.getDebugLogger().debug("Read invoice from map NBT: " + 
                    (invoice != null ? invoice.substring(0, Math.min(50, invoice.length())) + "..." : "null"));
                return invoice;
            }
        } catch (Exception e) {
            plugin.getDebugLogger().error("Error reading invoice from map NBT", e);
        }

        return null;
    }

    /**
     * Check if a map contains Lightning data
     */
    public static boolean isLightningMap(LightningPlugin plugin, ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) {
            return false;
        }

        MapMeta mapMeta = (MapMeta) item.getItemMeta();
        if (mapMeta == null) {
            return false;
        }

        NamespacedKey typeKey = new NamespacedKey(plugin, NBT_KEY_TYPE);
        return mapMeta.getPersistentDataContainer().has(typeKey, PersistentDataType.STRING);
    }

    public static String getMapType(LightningPlugin plugin, ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) {
            return null;
        }

        MapMeta mapMeta = (MapMeta) item.getItemMeta();
        if (mapMeta == null) {
            return null;
        }

        try {
            NamespacedKey typeKey = new NamespacedKey(plugin, NBT_KEY_TYPE);
            
            if (mapMeta.getPersistentDataContainer().has(typeKey, PersistentDataType.STRING)) {
                return mapMeta.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
            }
        } catch (Exception e) {
            plugin.getDebugLogger().error("Error reading map type from NBT", e);
        }

        return "payment"; // default
    }

    /**
     * Get timestamp when map was created
     */
    public static long getMapTimestamp(LightningPlugin plugin, ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) {
            return 0;
        }

        MapMeta mapMeta = (MapMeta) item.getItemMeta();
        if (mapMeta == null) {
            return 0;
        }

        try {
            NamespacedKey timestampKey = new NamespacedKey(plugin, NBT_KEY_TIMESTAMP);
            
            if (mapMeta.getPersistentDataContainer().has(timestampKey, PersistentDataType.LONG)) {
                Long timestamp = mapMeta.getPersistentDataContainer().get(timestampKey, PersistentDataType.LONG);
                return timestamp != null ? timestamp : 0;
            }
        } catch (Exception e) {
            plugin.getDebugLogger().error("Error reading timestamp from map NBT", e);
        }

        return 0;
    }

    /**
     * Get the player UUID who created this map
     */
    public static String getMapOwnerUuid(LightningPlugin plugin, ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) {
            return null;
        }

        MapMeta mapMeta = (MapMeta) item.getItemMeta();
        if (mapMeta == null) {
            return null;
        }

        try {
            NamespacedKey playerUuidKey = new NamespacedKey(plugin, NBT_KEY_PLAYER_UUID);
            
            if (mapMeta.getPersistentDataContainer().has(playerUuidKey, PersistentDataType.STRING)) {
                return mapMeta.getPersistentDataContainer().get(playerUuidKey, PersistentDataType.STRING);
            }
        } catch (Exception e) {
            plugin.getDebugLogger().error("Error reading player UUID from map NBT", e);
        }

        return null;
    }

    public static boolean isSecureLNDHubMap(LightningPlugin plugin, ItemStack item) {
        return "lndhub".equals(getMapType(plugin, item));
    }
}