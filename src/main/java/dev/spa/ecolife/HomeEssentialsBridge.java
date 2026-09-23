package dev.spa.ecolife;

import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** EssentialsX が有効なときだけロードする連携部分。 */
final class HomeEssentialsBridge {
    private HomeEssentialsBridge() {}

    static boolean hasHome(Plugin plugin, Player player, String name) throws ReflectiveOperationException {
        Object user = plugin.getClass().getMethod("getUser", Player.class).invoke(plugin, player);
        if (user == null) throw new ReflectiveOperationException("EssentialsX user unavailable");
        Object homes = user.getClass().getMethod("getHomes").invoke(user);
        if (!(homes instanceof List<?> list)) throw new ReflectiveOperationException("Invalid EssentialsX homes");
        return list.stream().anyMatch(home -> home instanceof String value && name.equalsIgnoreCase(value));
    }
}
