package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * 「破碎」世界的<b>开局空岛</b> —— 把 {@code maixr:broken_skyblock} 结构放到主世界原点。
 *
 * <h2>做什么</h2>
 *
 * <p>「破碎」世界类型的主世界是一个<b>渐变虚空</b>世界（见
 * {@link BrokenOverworldChunkGenerator}）：出生点附近 10% 虚空、越远越碎。
 * 本类负责在 <b>(0, 160, 0)</b> 放一座空岛，并让玩家出生、复活都落在岛上的草方块上。</p>
 *
 * <h2>★ 出生点 = 岛上的草方块</h2>
 *
 * <p>关键是不用「把玩家传送过去」那套（之前那样做，玩家一死就会被原版复活逻辑
 * 扔回主世界出生点，因为破碎维度不是他记录的重生维度）。正确做法是
 * <b>直接改主世界的世界出生点</b>：</p>
 *
 * <pre>
 * level.setDefaultSpawnPos(岛上的草方块, 0F)
 * </pre>
 *
 * <p>这样一来：</p>
 * <ul>
 *   <li><b>首次进服</b>：原版 {@code PlayerList.placeNewPlayer} 用
 *       {@code getSharedSpawnPos()} 找落点 → 就是岛上</li>
 *   <li><b>没有床时死亡复活</b>：{@code ServerPlayer.findRespawnPositionAndUseSpawnBlock}
 *       走到的兜底分支是 {@code DimensionTransition.missingRespawnBlock(server.overworld(), ...)}
 *       → 也是 {@code getSharedSpawnPos()} → 同样落在岛上</li>
 * </ul>
 *
 * <p>两个症状一次解决，而且不碰玩家 NBT、不该被依赖的全局配置一个都不动。</p>
 *
 * <h2>只放一次</h2>
 *
 * <p>靠 {@link BrokenIslandData}（维度级 {@code SavedData}）记住，重启也不会重复放。</p>
 *
 * <h2>只在「破碎」世界类型下生效</h2>
 *
 * <p>判据 {@link BrokenWorld#isBrokenWorldType} —— 主世界的生成器是不是
 * {@link BrokenOverworldChunkGenerator}。
 * 「默认」和「空岛」世界类型完全不受影响。</p>
 */
public final class BrokenIslandHandler {

    /** 空岛结构在数据包里的 id（文件：data/maixr/structure/broken_skyblock.nbt）。 */
    public static final ResourceLocation ISLAND_ID =
            ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "broken_skyblock");

    /**
     * 岛的放置原点。结构 12x14x12，此点对齐结构的 (0,0,0)。
     *
     * <p>结构最高层是 local y=13，所以岛顶在 {@code 160+13 = 173}。
     * 出生点落在岛上的草方块上方（≈y167）。</p>
     *
     * <p>★ 为什么是 160：脚下留出 ~90 格空隙，避免和地面山体（沙漠沙丘能长到
     * y≈104）糊在一起 —— 否则视觉上会像"两个岛叠在一起"。</p>
     */
    public static final BlockPos ISLAND_ORIGIN = new BlockPos(0, 160, 0);

    /** 岛顶高度（结构最高层 local y=13）。 */
    public static final int ISLAND_TOP_Y = ISLAND_ORIGIN.getY() + 13;

    private BrokenIslandHandler() {}

    /**
     * 服务器<b>完全启动后</b>再动手。
     *
     * <p>用 {@code ServerStartedEvent} 而不是 {@code ServerStartingEvent}：后者阶段
     * 维度可能还没准备好，而且强制加载主世界出生点区块会拖慢启动。</p>
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (!BrokenWorld.isBrokenWorldType(server)) return;      // 默认 / 空岛 世界类型不管

        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        BrokenIslandData data = BrokenIslandData.get(overworld);
        if (data.islandPlaced) {
            ensureSpawnPoint(overworld);
            return;
        }

        BlockPos grass = placeIsland(overworld);
        if (grass == null) {
            MaixrMod.LOGGER.error("[破碎空岛] 结构 {} 放置失败 —— 请确认 jar 内有 data/maixr/structure/broken_skyblock.nbt",
                    ISLAND_ID);
            return;
        }
        data.islandPlaced = true;
        data.setDirty();
        ensureSpawnPoint(overworld);
    }

    /**
     * 放置空岛，返回岛上草方块的坐标（用作出生点）。
     *
     * <p>失败返回 {@code null}。</p>
     */
    private static BlockPos placeIsland(ServerLevel level) {
        StructureTemplateManager manager = level.getStructureManager();
        StructureTemplate template = manager.get(ISLAND_ID).orElse(null);
        if (template == null) {
            MaixrMod.LOGGER.error("[破碎空岛] 找不到结构模板 {}（应位于 data/maixr/structure/broken_skyblock.nbt）",
                    ISLAND_ID);
            return null;
        }

        // ★ 先强制加载相关区块：setBlock 落在未加载区块上会被静默丢弃。
        //   结构 12x14x12，横跨 1~2 个区块，多刷一圈保险。
        int minCX = (ISLAND_ORIGIN.getX() - 16) >> 4, maxCX = (ISLAND_ORIGIN.getX() + 16) >> 4;
        int minCZ = (ISLAND_ORIGIN.getZ() - 16) >> 4, maxCZ = (ISLAND_ORIGIN.getZ() + 16) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                level.getChunk(cx, cz);
            }
        }

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(false)
                .setRandom(RandomSource.create(level.getSeed()));

        boolean ok = template.placeInWorld(level, ISLAND_ORIGIN, ISLAND_ORIGIN, settings,
                level.getRandom(), 2 /* Block.UPDATE_CLIENTS */);
        if (!ok) return null;

        MaixrMod.LOGGER.info("[破碎空岛] 已在 {} 放置 {}（尺寸 {}）",
                ISLAND_ORIGIN, ISLAND_ID, template.getSize());

        return findGrassTop(level);
    }

    /** 岛的中心（XZ），用来挑"最靠中间的那块草方块"。 */
    private static int islandCenterX() { return ISLAND_ORIGIN.getX() + 6; }
    private static int islandCenterZ() { return ISLAND_ORIGIN.getZ() + 6; }

    /**
     * 找岛上的<b>草方块</b>，返回它<b>上方</b>那格（玩家该站的位置）。
     *
     * <h3>挑选规则</h3>
     * <ol>
     *   <li>只认真正的 {@code minecraft:grass_block}
     *       —— 岛上还有 podzol / mycelium / moss_block，它们也在
     *       {@code #minecraft:dirt} 标签里，但玩家要的是草方块</li>
     *   <li>要求上方两格是空气（站得下）</li>
     *   <li>在这些里挑离岛中心最近的 —— 避免挑到最边沿那块、被原版
     *       的落点修正一脚踢下岛</li>
     * </ol>
     *
     * <p>找不到草方块时退回「最高的可站立非空气方块上方」，
     * 再退回归放置点上方（保证不卡住流程）。</p>
     */
    private static BlockPos findGrassTop(ServerLevel level) {
        BlockPos bestGrass = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos bestAny = null;
        int bestAnyY = Integer.MIN_VALUE;

        for (int dx = -4; dx <= 20; dx++) {
            for (int dz = -4; dz <= 20; dz++) {
                for (int dy = 0; dy <= 20; dy++) {
                    BlockPos p = ISLAND_ORIGIN.offset(dx, dy, dz);
                    BlockState st = level.getBlockState(p);
                    if (st.isAir()) continue;

                    // 记一份"最高的可站立方块"作为兜底
                    if (p.getY() > bestAnyY
                            && level.getBlockState(p.above()).isAir()
                            && level.getBlockState(p.above(2)).isAir()) {
                        bestAnyY = p.getY();
                        bestAny = p;
                    }

                    if (st.getBlock() != net.minecraft.world.level.block.Blocks.GRASS_BLOCK) continue;
                    if (!level.getBlockState(p.above()).isAir()) continue;
                    if (!level.getBlockState(p.above(2)).isAir()) continue;

                    double d = Math.pow(p.getX() - islandCenterX(), 2)
                             + Math.pow(p.getZ() - islandCenterZ(), 2);
                    if (d < bestDist) {
                        bestDist = d;
                        bestGrass = p;
                    }
                }
            }
        }

        if (bestGrass != null) {
            MaixrMod.LOGGER.info("[破碎空岛] 出生草方块定位在 {}（离岛心 {:.1f} 格）",
                    bestGrass, Math.sqrt(bestDist));
            return bestGrass.above();
        }
        if (bestAny != null) {
            MaixrMod.LOGGER.warn("[破碎空岛] 岛上没找到草方块，退回最高可站立方块 {}", bestAny);
            return bestAny.above();
        }

        MaixrMod.LOGGER.warn("[破碎空岛] 岛范围内没找到地面，出生点退回放置点上方");
        return ISLAND_ORIGIN.above(14);
    }

    /**
     * 把主世界的世界出生点设到岛上 —— 首次进服与无床复活都用它。
     *
     * <h3>★ 必须同时把 spawnRadius 设为 0</h3>
     *
     * <p>原版 {@code ServerPlayer.adjustSpawnLocation} 会以出生点为中心、
     * 在 <b>±spawnRadius</b> 的方形范围内<b>随机乱找</b>一个能站立的位置
     * （{@code getOverworldRespawnPos} 会返回那个列上的真实地面高度）。</p>
     *
     * <p>{@code spawnRadius} 默认是 <b>10</b>，也就是搜 21×21 格。
     * 我们的岛只有 12×12，晶格外的候选点全是<b>岛下方 y≈70 的地形</b>
     * —— 玩家会被扔到岛下方，而不是岛上。<b>实测发现的坑。</b></p>
     *
     * <p>设为 0 之后搜索范围只剩出生点自身那一列，
     * 于是 {@code getOverworldRespawnPos} 正好落在岛中心列上 ✓。</p>
     */
    private static void ensureSpawnPoint(ServerLevel overworld) {
        // ★ 先关掉原版的"出生点随机散布"，否则玩家会被扔到岛下面去
        overworld.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_SPAWN_RADIUS)
                .set(0, overworld.getServer());

        BlockPos spawn = overworld.getSharedSpawnPos();
        // 已经指到岛上了就别反复写（setDefaultSpawnPos 会广播全服玩家）
        if (isOnIsland(spawn)) return;

        BlockPos grassTop = findGrassTop(overworld);
        overworld.setDefaultSpawnPos(grassTop, 0.0F);
        MaixrMod.LOGGER.info("[破碎空岛] 世界出生点已设为 {}（玩家出生/无床复活都落这里）", grassTop);
    }

    /**
     * 玩家复活时把他钉在岛上 —— <b>这是"死后复活到下面地形"的根治</b>。
     *
     * <h2>为什么还需要这个</h2>
     *
     * <p>光设 {@code setDefaultSpawnPos} 不够。原版
     * {@code ServerPlayer.adjustSpawnLocation} 拿到出生点后会做
     * "先向上找空位、再向下掉到有碰撞的地面"：</p>
     *
     * <pre>
     * while (!noCollision(...)) y++;              // 向上腾出空间
     * while (noCollision(below) && y > min) y--;  // ★ 向下掉！
     * </pre>
     *
     * <p>我们的岛只有 12×12 且<b>悬空</b>。只要出生点那一列因为任何原因
     * （岛边缘、草方块被挖、别的模组改地形）没有碰撞体，玩家就会
     * <b>一路掉到岛下方 90 格的真实地面上</b> —— 玩家现象："复活在了另一个岛上"。
     * （实测：岛在 y≈167，下方 y≈58~104 是沙漠/平原地形。）</p>
     *
     * <h2>做法</h2>
     *
     * <p>监听 {@code PlayerEvent.PlayerRespawnEvent}，在玩家<b>复活完成后</b>
     * 检查他是不是被扔到了岛外：是就强行传送回岛上。
     * 这样即使别的机制把他挪走，也会被拉回来。</p>
     *
     * <p>只在「破碎」世界类型 + 主世界生效，其它世界类型完全不受影响。</p>
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerRespawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (!BrokenWorld.isBrokenWorldType(player.getServer())) return;

        // 玩家有自己设定的床/重生锚 → 那是他的选择，别干预
        //   getRespawnPosition() 非 null 且与主世界无关时说明他有床；这里只判断"有没有自己的点"
        if (player.getRespawnPosition() != null) return;

        BlockPos cur = player.blockPosition();
        if (isOnIsland(cur)) return;                 // 已经在岛上，不动

        BlockPos target = findGrassTop(level);
        player.teleportTo(level, target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
                player.getYRot(), player.getXRot());
        MaixrMod.LOGGER.info("[破碎空岛] 玩家 {} 复活点不在岛上，已拉回 {}",
                player.getGameProfile().getName(), target);
    }

    /** 这个坐标是不是在岛的高度范围内（用来判断出生点是否已经设好）。 */
    private static boolean isOnIsland(BlockPos p) {
        return p.getY() >= ISLAND_ORIGIN.getY() && p.getY() <= ISLAND_ORIGIN.getY() + 20
                && Math.abs(p.getX()) <= 24 && Math.abs(p.getZ()) <= 24;
    }
}
