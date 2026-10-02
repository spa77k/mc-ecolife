package dev.spa.ecolife;

import java.util.HashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 実Paperに溶岩と水を置いて石を生ませ、Jobs の支払い前イベントを本物のクラスで発行して、
 * EcoLifeAssist が取り消すかを確かめる。1回目で生成・ピストン移動、2回目で再起動後の記録と爆発後の消去を見る。
 */
public final class PaperGeneratorStoneProbe extends JavaPlugin {

    private World world;

    @Override public void onEnable() {
        world = Bukkit.getWorlds().get(0);
        world.addPluginChunkTicket(0, 0, this);
        world.addPluginChunkTicket(1, 0, this);
        if (Boolean.getBoolean("probe.second")) {
            Bukkit.getScheduler().runTaskLater(this, this::second, 40);
        } else {
            Bukkit.getScheduler().runTaskLater(this, this::buildGenerators, 40);
            Bukkit.getScheduler().runTaskLater(this, this::firstAfterForm, 200);
            Bukkit.getScheduler().runTaskLater(this, this::firstAfterPiston, 240);
        }
    }

    /** 石製造機（横から水が戻る）と、1回だけ石を生む場所と、自然の石を置く。 */
    private void buildGenerators() {
        // 製造機：(2,100,2) に水が流れ込み、真上の溶岩が落ちて石になる。掘っても水が戻る
        enclose(2, 100, 2, 3, 2);
        set(3, 101, 2, Material.GLASS);
        set(2, 101, 2, Material.LAVA);
        set(3, 100, 2, Material.WATER);
        // 1回きり：(20,100,2) の水源に溶岩が落ちて石になる。あとでピストンで (21,100,2) へ押す
        enclose(20, 100, 2, 20, 2);
        set(20, 100, 2, Material.WATER);
        set(20, 101, 2, Material.LAVA);
        // 自然の石（BlockFormEvent を通らない）
        set(10, 100, 10, Material.STONE);
        getLogger().info("GENERATOR_PROBE_BUILT");
    }

    private void firstAfterForm() {
        Block generator = world.getBlockAt(2, 100, 2);
        Block once = world.getBlockAt(20, 100, 2);
        getLogger().info("GENERATOR_PROBE_FORMED " + generator.getType() + " " + once.getType());
        result("generator_break", generator, "BREAK");
        result("generator_tnt", generator, "TNTBREAK");
        result("generator_place", generator, "PLACE");
        result("natural_break", world.getBlockAt(10, 100, 10), "BREAK");

        // 掘ったあと水と溶岩で生まれ直しても、記録は残る
        generator.setType(Material.AIR);

        // ピストンで (20,100,2) の石を東へ押す
        set(21, 100, 2, Material.AIR);
        Block piston = world.getBlockAt(19, 100, 2);
        piston.setType(Material.PISTON, false);
        Directional data = (Directional) piston.getBlockData();
        data.setFacing(BlockFace.EAST);
        piston.setBlockData(data, false);
        world.getBlockAt(19, 99, 2).setType(Material.REDSTONE_BLOCK);
    }

    private void firstAfterPiston() {
        getLogger().info("GENERATOR_PROBE_REFORMED " + world.getBlockAt(2, 100, 2).getType());
        result("generator_reformed_break", world.getBlockAt(2, 100, 2), "BREAK");
        getLogger().info("GENERATOR_PROBE_PUSHED " + world.getBlockAt(21, 100, 2).getType());
        result("pushed_break", world.getBlockAt(21, 100, 2), "BREAK");
        result("pushed_from_break", world.getBlockAt(20, 100, 2), "BREAK");
        done();
    }

    /** 再起動後も記録が残り、爆発で壊れたら消える。 */
    private void second() {
        Block pushed = world.getBlockAt(21, 100, 2);
        result("restart_pushed_break", pushed, "BREAK");
        world.createExplosion(pushed.getLocation().add(0.5, 0.5, 0.5), 4f, false, true);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            getLogger().info("GENERATOR_PROBE_EXPLODED " + pushed.getType());
            pushed.setType(Material.STONE);
            result("exploded_then_placed_break", pushed, "BREAK");
            done();
        }, 5);
    }

    private void done() {
        getLogger().info("GENERATOR_PROBE_DONE");
        Bukkit.shutdown();
    }

    /** Jobs の支払い前イベントをそのブロックで発行し、取り消されたかを出力する。 */
    private void result(String label, Block block, String action) {
        try {
            ClassLoader loader = Bukkit.getPluginManager().getPlugin("Jobs").getClass().getClassLoader();
            Class<?> eventClass = Class.forName("com.gamingmesh.jobs.api.JobsPrePaymentEvent", true, loader);
            Class<?> jobClass = Class.forName("com.gamingmesh.jobs.container.Job", true, loader);
            Class<?> actionInfoClass = Class.forName("com.gamingmesh.jobs.container.ActionInfo", true, loader);
            Class<?> typeClass = Class.forName("com.gamingmesh.jobs.container.ActionType", true, loader);
            Object type = typeClass.getMethod("valueOf", String.class).invoke(null, action);
            Object info = Class.forName("com.gamingmesh.jobs.actions.BlockActionInfo", true, loader)
                    .getConstructor(Block.class, typeClass).newInstance(block, type);
            Object event = eventClass.getConstructor(org.bukkit.OfflinePlayer.class, jobClass, java.util.Map.class,
                            Block.class, org.bukkit.entity.Entity.class, org.bukkit.entity.LivingEntity.class, actionInfoClass)
                    .newInstance(Bukkit.getOfflinePlayer(java.util.UUID.randomUUID()), null, new HashMap<>(),
                            block, null, null, info);
            Bukkit.getPluginManager().callEvent((Event) event);
            getLogger().info("GENERATOR_PROBE_RESULT " + label + " " + ((Cancellable) event).isCancelled());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** (x1..x2, y, z) の列をガラスで囲み、上下も塞ぐ。中は空気にする。 */
    private void enclose(int x1, int y, int z, int x2, int height) {
        for (int x = x1 - 1; x <= x2 + 1; x++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= height; dy++) {
                    boolean inside = x >= x1 && x <= x2 && dz == 0 && dy >= 0 && dy < height;
                    set(x, y + dy, z + dz, inside ? Material.AIR : Material.GLASS);
                }
            }
        }
    }

    private void set(int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material);
    }
}
