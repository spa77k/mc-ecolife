package dev.spa.ecolife;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** AdminShop が生成した、効果データ付きの商品だけを報酬として受け取る。 */
final class AdminShopReward {
    private static final NamespacedKey PERK_KEY = new NamespacedKey("adminshop", "perk");

    private AdminShopReward() {}

    static ItemStack create(JavaPlugin caller, String productId) {
        Plugin shop = Bukkit.getPluginManager().getPlugin("AdminShop");
        if (shop == null || !shop.isEnabled()) {
            throw new RewardTable.UnavailableException("AdminShop が利用できません");
        }
        try {
            Object result = shop.getClass().getMethod("createRewardItem", String.class).invoke(shop, productId);
            if (!(result instanceof ItemStack item) || item.getType().isAir() || !item.hasItemMeta()) {
                throw new RewardTable.UnavailableException("AdminShop の商品 " + productId + " がありません");
            }
            String perk = item.getItemMeta().getPersistentDataContainer().get(PERK_KEY, PersistentDataType.STRING);
            if (perk == null || perk.isBlank() || (productId.equals("return_charm") && !perk.equals("return_charm"))) {
                throw new RewardTable.UnavailableException("AdminShop の商品 " + productId + " に正しい効果データがありません");
            }
            return item;
        } catch (ReflectiveOperationException | LinkageError e) {
            caller.getLogger().log(java.util.logging.Level.WARNING, "AdminShop の報酬を作れません", e);
            throw new RewardTable.UnavailableException("AdminShop 連携を利用できません");
        }
    }
}
