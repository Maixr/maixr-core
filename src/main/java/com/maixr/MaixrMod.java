package com.maixr;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * Maixr Core（NeoForge 1.21.1）：
 * <ul>
 *   <li>「破碎」世界类型（{@code maixr:broken}）—— 渐变虚空主世界 + 天空结构 + 空域浮岩 + 开局空岛</li>
 *   <li>玩偶（fumo）：Maixr 皮肤玩偶 / 真理玩偶，可接入 AE2 ME 网络（占频道、每分钟产出）</li>
 *   <li>创造猪咪复制配方（消耗版）</li>
 * </ul>
 * 其余系统（伊甸工程 / 裂隙抉择 / 弧光 Boss / 转化桌 / 故事物品 / 虚空凝尘等）已整体移除。
 */
@Mod(MaixrMod.MODID)
public class MaixrMod {
    public static final String MODID = "maixr";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, MODID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, MODID);
    /** 世界生成 Feature（空域浮岩） */
    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(BuiltInRegistries.FEATURE, MODID);

    /** 空域里的陨石碎块：外壳 AE2 陨石方块、内核平滑陨石方块（其余为粗矿块） */
    public static final DeferredHolder<Feature<?>, Feature<NoneFeatureConfiguration>> SKY_STONE_CLUSTER =
            FEATURES.register("sky_stone_cluster", () -> new SkyStoneClusterFeature(
                    NoneFeatureConfiguration.CODEC));

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<Block, Block> SKIN_DOLL = BLOCKS.register("maixr_skin_doll",
            () -> new SkinDollBlock(BlockBehaviour.Properties.of()
                    .strength(0.5F)
                    .sound(SoundType.WOOL)
                    .lightLevel(state -> 8)
                    .noOcclusion()));
    public static final DeferredHolder<Item, Item> SKIN_DOLL_ITEM = ITEMS.register("maixr_skin_doll",
            () -> new MaixrDollItem(SKIN_DOLL.get(), new Item.Properties()));

    /** 真理玩偶：样式与 Maixr 玩偶一致；佩戴/手持时玩家死亡触发不死图腾保护。 */
    public static final DeferredHolder<Block, Block> TRUTH_DOLL = BLOCKS.register("truth_doll",
            () -> new TruthSkinDollBlock(BlockBehaviour.Properties.of()
                    .strength(0.5F)
                    .sound(SoundType.WOOL)
                    .lightLevel(state -> 8)
                    .noOcclusion()));
    public static final DeferredHolder<Item, Item> TRUTH_DOLL_ITEM = ITEMS.register("truth_doll",
            () -> new MaixrDollItem(TRUTH_DOLL.get(), new Item.Properties()));

    /** 致回忆中的明日：流萤/萨姆双形态玩偶，手持右键/佩戴快捷键变身（10s 创造飞行 + 速度 II），接入 AE2。 */
    public static final DeferredHolder<Block, Block> MEMORY_TOMORROW_DOLL = BLOCKS.register("memory_tomorrow_doll",
            () -> new MemoryTomorrowDollBlock(BlockBehaviour.Properties.of()
                    .strength(0.5F)
                    .sound(SoundType.WOOL)
                    .lightLevel(state -> 8)
                    .noOcclusion()));
    public static final DeferredHolder<Item, Item> MEMORY_TOMORROW_DOLL_ITEM = ITEMS.register("memory_tomorrow_doll",
            () -> new MemoryTomorrowDollItem(MEMORY_TOMORROW_DOLL.get(), new Item.Properties()));

    /** 变身语音："我将点燃星海"（单声道 ogg，随距离衰减）。 */
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, MODID);
    public static final DeferredHolder<SoundEvent, SoundEvent> MEMORY_DOLL_TRANSFORM_SOUND =
            SOUND_EVENTS.register("memory_doll_transform",
                    () -> SoundEvent.createVariableRangeEvent(
                            ResourceLocation.fromNamespaceAndPath(MODID, "memory_doll_transform")));

    /** 是否任一玩偶物品（Maixr 玩偶 / 真理玩偶 / 致回忆中的明日）。 */
    public static boolean isDollItem(Item item) {
        return item == SKIN_DOLL_ITEM.get() || item == TRUTH_DOLL_ITEM.get() || item == MEMORY_TOMORROW_DOLL_ITEM.get();
    }

    /** 消耗版「创造猪咪」复制配方：1 只创造猪咪 + 1 件任意物品 → 复制为一整组。 */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<?>> PIGMEE_DUPLICATION_SERIALIZER =
            RECIPE_SERIALIZERS.register("creative_pigmee_duplication",
                    () -> MaixrPigmeeDuplicationRecipe.SERIALIZER);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB_MAIXR = TABS.register("maixr",
            () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(SKIN_DOLL_ITEM.get()))
                    .title(Component.translatable("itemGroup.maixr"))
                    .displayItems((params, output) -> {
                        output.accept(SKIN_DOLL_ITEM.get());
                        output.accept(TRUTH_DOLL_ITEM.get());
                        output.accept(MEMORY_TOMORROW_DOLL_ITEM.get());
                    })
                    .build());

    /**
     * 构造：NeoForge 1.21 会把 {@code IEventBus}（mod 总线）与 {@code ModContainer} 直接注入进来，
     * 不再需要 {@code FMLJavaModLoadingContext.get().getModEventBus()}。
     */
    public MaixrMod(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
        RECIPE_SERIALIZERS.register(modBus);
        FEATURES.register(modBus);
        SOUND_EVENTS.register(modBus);
        MaixrBlockEntities.register(modBus);
        // 致回忆中的明日：变身状态附件（跨重登持久，死亡清除）
        MemoryDollTransforms.register(modBus);
        // AE2：两个玩偶方块实体必须显式注册「网络节点宿主」能力，否则线缆连不上
        modBus.addListener(MaixrAe2Integration::registerCapabilities);
        MaixrChunkGenerators.register(modBus);
        // 「破碎」世界类型：在主世界 (0,160,0) 放开局空岛，并把世界出生点设到岛上
        //（出生/无床复活都落在岛上。旧版那套"把玩家传送去独立维度"已删除 —— 见 BrokenIslandHandler 类注释）
        NeoForge.EVENT_BUS.addListener(BrokenIslandHandler::onServerStarted);
        // 复活保障：把被原版落点修正扔下岛的玩家拉回来
        NeoForge.EVENT_BUS.addListener(BrokenIslandHandler::onPlayerRespawn);
        // 网络包注册：1.21 要求走 RegisterPayloadHandlersEvent（不能用 SimpleChannel 手动注册）
        modBus.addListener(MaixrNetwork::register);
        LOGGER.info("Maixr Core (NeoForge 1.21.1) loaded.");
    }

}
