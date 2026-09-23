package dev.spa.ecolife.rtp;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public record RtpConfig(boolean enabled, int centerX, int centerZ, int minRadius, int maxRadius,
                        int minY, int maxY, int netherMaxY, int maxAttempts,
                        int cooldownSeconds, int delaySeconds, Set<String> disabledWorlds) {

    public static RtpConfig load(JavaPlugin plugin) {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "rtp.yml");
        if (!file.isFile()) plugin.saveResource("rtp.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int minRadius = Math.max(0, yaml.getInt("min-radius", 10));
        int maxRadius = yaml.getInt("max-radius", 3000);
        if (maxRadius <= minRadius) throw new IllegalArgumentException("rtp.yml: max-radius は min-radius より大きくしてください");
        Set<String> disabled = new HashSet<>();
        for (String world : yaml.getStringList("disabled-worlds")) disabled.add(world.toLowerCase(Locale.ROOT));
        return new RtpConfig(yaml.getBoolean("enabled", true), yaml.getInt("center-x", 0),
                yaml.getInt("center-z", 0), minRadius, maxRadius,
                yaml.getInt("min-y", 0), yaml.getInt("max-y", 320), yaml.getInt("nether-max-y", 120),
                Math.max(1, Math.min(100, yaml.getInt("max-attempts", 32))),
                Math.max(0, yaml.getInt("cooldown-seconds", 600)),
                Math.max(0, yaml.getInt("delay-seconds", 5)), Set.copyOf(disabled));
    }

    public boolean allows(String world) {
        return enabled && !disabledWorlds.contains(world.toLowerCase(Locale.ROOT));
    }
}
