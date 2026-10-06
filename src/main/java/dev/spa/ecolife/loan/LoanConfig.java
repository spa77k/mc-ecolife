package dev.spa.ecolife.loan;

import java.io.File;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public record LoanConfig(boolean enabled, BigDecimal interestRate, long periodMillis, long dueMillis,
                         BigDecimal garnishRate, Map<String, BigDecimal> limits,
                         List<String> blockedCommands, Set<String> blockedHolders, boolean blockQuickShop) {

    public static LoanConfig load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "loan.yml");
        if (!file.isFile()) plugin.saveResource("loan.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        BigDecimal interest = new BigDecimal(yaml.getString("interest-rate", "0.10"));
        BigDecimal garnish = new BigDecimal(yaml.getString("garnish-rate", "0.5"));
        if (interest.signum() < 0) throw new IllegalArgumentException("loan.yml: interest-rate は0以上にしてください");
        if (garnish.signum() < 0 || garnish.compareTo(BigDecimal.ONE) > 0)
            throw new IllegalArgumentException("loan.yml: garnish-rate は0～1にしてください");
        Map<String, BigDecimal> limits = new HashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("limits");
        if (section != null) {
            for (String key : section.getKeys(false))
                limits.put(key.toLowerCase(Locale.ROOT), new BigDecimal(section.getString(key, "0")));
        }
        limits.putIfAbsent("default", BigDecimal.ZERO);
        List<String> commands = yaml.getStringList("blocked-commands").stream()
                .map(command -> command.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ")).toList();
        return new LoanConfig(yaml.getBoolean("enabled", true), interest,
                Math.max(1, yaml.getLong("interest-period-hours", 168)) * 3_600_000L,
                Math.max(1, yaml.getLong("due-days", 14)) * 86_400_000L,
                garnish, Map.copyOf(limits), commands,
                Set.copyOf(yaml.getStringList("blocked-inventory-holders")),
                yaml.getBoolean("block-quickshop-purchase", true));
    }

    /** 先頭の1語か2語が blocked-commands にあるか。message は先頭の / を含んでよい。 */
    public boolean blocks(String message) {
        String[] words = message.replaceFirst("^/", "").toLowerCase(Locale.ROOT).trim().split("\\s+");
        if (blockedCommands.contains(words[0])) return true;
        return words.length > 1 && blockedCommands.contains(words[0] + " " + words[1]);
    }
}
