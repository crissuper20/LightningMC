package com.crissuper20.lightning.managers;

import com.crissuper20.lightning.LightningPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class MessageManager {

    private final LightningPlugin plugin;
    private final Map<String, String> messages = new HashMap<>();
    private final MiniMessage miniMessage;

    public MessageManager(LightningPlugin plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        load();
    }

    public void load() {
        messages.clear();
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        
        // Load defaults from jar to ensure new keys exist
        InputStream defStream = plugin.getResource("messages.yml");
        if (defStream != null) {
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defStream, StandardCharsets.UTF_8));
            config.setDefaults(defConfig);
        }

        // Flatten keys for easier access
        for (String key : config.getKeys(true)) {
            if (config.isString(key)) {
                messages.put(key, config.getString(key));
            } else if (config.isList(key)) {
                // Join lists with newlines
                messages.put(key, String.join("<newline>", config.getStringList(key)));
            }
        }
    }

    public Component get(String key, TagResolver... tags) {
        String raw = messages.getOrDefault(key, "<red>Missing message: " + key);
        String prefix = messages.getOrDefault("prefix", "");
        
        TagResolver prefixTag = Placeholder.parsed("prefix", prefix);
        
        return miniMessage.deserialize(raw, TagResolver.resolver(prefixTag, TagResolver.resolver(tags)));
    }

    public void send(CommandSender sender, String key, TagResolver... tags) {
        sender.sendMessage(get(key, tags));
    }
    
    public MiniMessage getMiniMessage() {
        return miniMessage;
    }
}
