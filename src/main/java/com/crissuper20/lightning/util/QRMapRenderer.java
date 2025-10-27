package com.crissuper20.lightning.util;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import com.crissuper20.lightning.LightningPlugin;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

public class QRMapRenderer extends MapRenderer {
    private final BufferedImage qrImage;
    private boolean rendered = false;

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

    public static boolean createMapForPlayer(LightningPlugin plugin, Player player, String content) {
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
            
            // Set map view and give to player
            mapMeta.setMapView(view);
            map.setItemMeta(mapMeta);
            
            // Add map to player's inventory
            player.getInventory().addItem(map);
            plugin.getDebugLogger().debug("QR map created and given to " + player.getName());
            
            return true;
        } catch (WriterException e) {
            plugin.getDebugLogger().error("Failed to generate QR code", e);
            return false;
        } catch (Exception e) {
            plugin.getDebugLogger().error("Unexpected error creating QR map", e);
            return false;
        }
    }
}
