package com.copperpocket.actionitems;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ActionItems extends JavaPlugin implements Listener, CommandExecutor {

    private NamespacedKey itemKey;
    // FIX: Changed map definition from Map<UUID, Long> to the nested map structure
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        // Create a unique key to identify valid items
        this.itemKey = new NamespacedKey(this, "action_item_id");

        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("actionitem").setExecutor(this);
    }

    // Command to give the item: /actionitem give <player> <configKey>
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(ChatColor.RED + "Usage: /actionitem give <player> <itemId>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found.");
            return true;
        }

        String itemId = args[2].toLowerCase();
        if (!getConfig().contains("items." + itemId)) {
            sender.sendMessage(ChatColor.RED + "Item ID '" + itemId + "' not found in config.");
            return true;
        }

        target.getInventory().addItem(createActionItem(itemId));
        sender.sendMessage(ChatColor.GREEN + "Gave " + itemId + " to " + target.getName());
        return true;
    }

    // Helper to build the item with the secret NBT tag
    private ItemStack createActionItem(String configId) {
        ConfigurationSection section = getConfig().getConfigurationSection("items." + configId);
        Material mat = Material.getMaterial(section.getString("material", "STONE"));
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        // Visuals
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', section.getString("display-name")));
        List<String> lore = new ArrayList<>();
        for (String line : section.getStringList("lore")) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }
        meta.setLore(lore);

        // Get the CMD value from the config, defaulting to 0 if not found.
        int customModelData = section.getInt("model-data", 0);

        // Apply the CMD if it is greater than 0
        if (customModelData > 0) {
            meta.setCustomModelData(customModelData);
        }

        // THE IMPORTANT PART: Store the config ID inside the item permanently
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, configId);

        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (item == null || item.getType() == Material.AIR || item.getItemMeta() == null) return;
        if (!item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.STRING)) return;

        // Get the ID (e.g., "homestone")
        String itemId = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        ConfigurationSection section = getConfig().getConfigurationSection("items." + itemId);

        if (section == null) return;

        // Get cooldown time in seconds from config, convert to milliseconds
        long cooldownTime = section.getInt("cooldown", 0) * 1000L;
        long now = System.currentTimeMillis();

        // --- COOLDOWN CHECK START: NOW ITEM-SPECIFIC ---
        if (cooldownTime > 0) {

            // 1. Get the player's specific item cooldown map, or null if they haven't used anything yet
            Map<String, Long> playerCooldowns = cooldowns.get(player.getUniqueId());

            if (playerCooldowns != null && playerCooldowns.containsKey(itemId) && playerCooldowns.get(itemId) > now) {

                // Calculate and send cooldown message
                long timeLeftMillis = playerCooldowns.get(itemId) - now;
                double timeLeftSeconds = (double) timeLeftMillis / 1000.0;
                String formattedTime = String.format("%.1f", timeLeftSeconds);

                player.sendMessage(ChatColor.RED + "You must wait " + ChatColor.YELLOW + formattedTime + ChatColor.RED + " seconds before using this item again.");

                // Player is still on cooldown for THIS item, ignore the event.
                return;
            }
        }
        // --- COOLDOWN CHECK END ---

        event.setCancelled(true); // Prevent placing the block if it's a block

        // 1. Run Commands
        List<String> commands = section.getStringList("commands");
        for (int i = 0; i < commands.size(); i++) {
            String cmd = commands.get(i);
            String finalCmd = cmd.replace("%player%", player.getName());

            // Check if this is the command that requires the permission (the second command, index 1)
            if (i == 1 && finalCmd.startsWith("sudo")) {
                // If it is the SUDO command, schedule it to run in 1 tick (50ms)
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                }, 3L); // 1L is 1 tick
            } else {
                // For all other commands (like LP commands), run them immediately
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
            }
        }

        // 2. Consume Item
        if (section.getBoolean("consume-on-use")) {
            item.setAmount(item.getAmount() - 1);
        }

        // --- COOLDOWN SET: NOW ITEM-SPECIFIC ---
        if (cooldownTime > 0) {
            // Ensure the outer map (player map) exists
            cooldowns.putIfAbsent(player.getUniqueId(), new HashMap<>());

            // Put the new expiration time into the player's inner item map
            cooldowns.get(player.getUniqueId()).put(itemId, now + cooldownTime);
        }
        // --- END COOLDOWN SET ---
    }
}