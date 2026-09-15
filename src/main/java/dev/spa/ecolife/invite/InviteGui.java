package dev.spa.ecolife.invite;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

final class InviteGui implements Listener {
    private final InviteService service;

    InviteGui(InviteService service) {
        this.service = service;
    }

    private static final class View implements InventoryHolder {
        final UUID owner;
        final boolean ranking;
        final int page;
        Inventory inventory;

        View(UUID owner, boolean ranking, int page) {
            this.owner = owner;
            this.ranking = ranking;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    void open(Player player, boolean ranking, int page) throws Exception {
        View view = new View(player.getUniqueId(), ranking, Math.max(0, page));
        Inventory inventory =
                Bukkit.createInventory(
                        view,
                        54,
                        service.text(ranking ? "gui-top" : "gui-title", "page", view.page + 1));
        view.inventory = inventory;
        int slot = 0;
        if (ranking) {
            for (var row : service.store.top(view.page))
                inventory.setItem(
                        slot++,
                        item(
                                Material.GOLD_INGOT,
                                service.text(
                                        "rank",
                                        "rank",
                                        view.page * 45 + slot,
                                        "name",
                                        row.name(),
                                        "count",
                                        row.count())));
        } else {
            for (var row : service.store.invited(player.getUniqueId(), view.page))
                inventory.setItem(
                        slot++,
                        item(
                                row.state().equals("COMPLETE") ? Material.EMERALD : Material.PAPER,
                                Component.text(service.name(row.newcomer())),
                                service.state(row)));
        }
        if (view.page > 0) inventory.setItem(45, item(Material.ARROW, service.text("gui-prev")));
        inventory.setItem(
                47,
                item(
                        Material.NAME_TAG,
                        service.text("gui-code", "name", player.getName()),
                        service.text("gui-code-help", "name", player.getName())));
        inventory.setItem(
                49, item(Material.COMPASS, service.text(ranking ? "gui-list" : "gui-ranking")));
        var own = service.store.link(player.getUniqueId());
        inventory.setItem(
                51,
                item(
                        Material.BOOK,
                        service.text("gui-own"),
                        own == null
                                ? service.text("no-link")
                                : service.text("gui-inviter", "name", service.name(own.inviter())),
                        own == null ? service.text("gui-register-help") : service.state(own)));
        if (slot == 45) inventory.setItem(53, item(Material.ARROW, service.text("gui-next")));
        player.openInventory(inventory);
    }

    private ItemStack item(Material material, Component name, Component... lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(List.of(lore));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(view.owner)) return;
        if (!service.enabled() || !player.hasPermission("ecolife.invite")) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54 || event.getView().getTopInventory().getItem(slot) == null)
            return;
        Bukkit.getScheduler()
                .runTask(
                        Bukkit.getPluginManager().getPlugin("EcoLifeAssist"),
                        () -> {
                            if (!player.isOnline()) return;
                            try {
                                if (slot == 45) open(player, view.ranking, view.page - 1);
                                else if (slot == 53) open(player, view.ranking, view.page + 1);
                                else if (slot == 49) open(player, !view.ranking, 0);
                                else if (slot == 47)
                                    service.say(player, "gui-code-help", "name", player.getName());
                            } catch (Exception e) {
                                service.fail(e);
                                player.closeInventory();
                            }
                        });
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) event.setCancelled(true);
    }
}
