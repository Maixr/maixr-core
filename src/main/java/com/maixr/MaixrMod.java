package com.maixr;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.slf4j.Logger;
import net.minecraft.core.registries.BuiltInRegistries;

/** 弧光核心（Forge 1.20.1）：maixr:overworld_broken 原生破碎世界 + Maixr 皮肤玩偶 + 真理玩偶（不死图腾式）。 */
@Mod(MaixrMod.MODID)
public class MaixrMod {
    public static final String MODID = "maixr";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, MODID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, MODID);
    /** 世界生成 Feature（陨石碎块） */
    public static final DeferredRegister<net.minecraft.world.level.levelgen.feature.Feature<?>> FEATURES =
            DeferredRegister.create(BuiltInRegistries.FEATURE, MODID);

    /** 空域里的陨石碎块：外壳 AE2 陨石方块、内核平滑陨石方块 */
    public static final DeferredHolder<net.minecraft.world.level.levelgen.feature.Feature<?>,
            net.minecraft.world.level.levelgen.feature.Feature<net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration>> SKY_STONE_CLUSTER =
            FEATURES.register("sky_stone_cluster", () -> new SkyStoneClusterFeature(
                    net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.CODEC));

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    /** 九个弧光核心物品。 */
    public static final String[] CORE_IDS = {
            "core_cosmos", "core_mercy", "core_awakening", "core_wisdom", "core_rule",
            "core_system", "core_matter", "core_magic", "core_eternal"
    };
    public static final java.util.List<DeferredHolder<Item, Item>> CORES = new java.util.ArrayList<>();

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

    /**
     * 陨石凝屑：虚空凝结出的小碎屑，打碎掉落陨石粉。
     * 由 {@link SkyDustCondenser} 在「朝上的 AE2 破坏面板」上方生成，玩家不用再搭到 Y=319。
     */
    public static final DeferredHolder<Block, Block> SKY_CONDENSATE = BLOCKS.register("sky_condensate",
            () -> new SkyCondensateBlock(BlockBehaviour.Properties.of()
                    .strength(0.2F)
                    .sound(SoundType.GLASS)
                    .lightLevel(state -> 7)
                    .noCollission()
                    .noOcclusion()));
    public static final DeferredHolder<Item, Item> SKY_CONDENSATE_ITEM = ITEMS.register("sky_condensate",
            () -> new net.minecraft.world.item.BlockItem(SKY_CONDENSATE.get(), new Item.Properties()));

    static {
        for (String id : CORE_IDS) {
            CORES.add(ITEMS.register(id, () -> new Item(new Item.Properties())));
        }
    }

    /**
     * 「人造鸟巢端口」—— 多方块机器的交互/成型端口。
     *
     * <p>鸟巢本身由 Multiblocked2 定义（artificial_sewage_nest.mb / artificial_pink_nest.mb），
     * 本方块作为图纸里的端口格：负责<b>结构成型判定</b>、<b>FE 电力输入</b>与<b>流体输出</b>。
     */
    // 【1.21.1 移植·暂缓】两个人造鸟巢端口（见 MaixrBlockEntities 顶部注释）。
    //
    // public static final DeferredHolder<Block, Block> EGG_SEWER = BLOCKS.register("egg_sewer",
    //         () -> new EggSewerBlock());
    // public static final DeferredHolder<Item, Item> EGG_SEWER_ITEM = ITEMS.register("egg_sewer",
    //         () -> new BlockItem(EGG_SEWER.get(), new Item.Properties()));

    /**
     * 人造粉红鸟巢端口：3×3×3 多方块（钢机壳 + 粉红羊毛 + 屠宰场）的交互端口。
     * 输入蛋产出粉红粘液，蛋不消耗；100000 FE = 1 mB。
     */
    // 【1.21.1 移植·暂缓】粉红鸟巢端口（同上）。
    //
    // public static final DeferredHolder<Block, Block> PINK_EGG_SEWER = BLOCKS.register("pink_egg_sewer",
    //         () -> new PinkEggSewerBlock());
    // public static final DeferredHolder<Item, Item> PINK_EGG_SEWER_ITEM = ITEMS.register("pink_egg_sewer",
    //         () -> new BlockItem(PINK_EGG_SEWER.get(), new Item.Properties()));

    public static final DeferredHolder<Item, Item> WORLD_REBIRTH_CORE = ITEMS.register("world_rebirth_core",
            () -> new MaixrWorldRebirthCoreItem(new Item.Properties()));

    public static final DeferredHolder<Item, Item> REVIVED_GRASS = ITEMS.register("revived_grass",
            () -> new MaixrRevivedGrassItem(new Item.Properties()));

    // ================== 三件「有故事的东西」 ==================

    /** 灵魂宝石：玩家亲手杀死自己的、好感度一级以上的女仆时，从尸体上掉落。 */
    public static final DeferredHolder<Item, Item> SOUL_GEM = ITEMS.register("soul_gem",
            () -> new MaixrLoreItem(new Item.Properties().stacksTo(1).fireResistant().rarity(Rarity.RARE),
                    "soul_gem"));

    /** 封印的Ta：双手各持一把寰宇支配之剑击败其他玩家时获得。 */
    public static final DeferredHolder<Item, Item> SEALED_TA = ITEMS.register("sealed_ta",
            () -> new MaixrLoreItem(new Item.Properties().stacksTo(1).fireResistant().rarity(Rarity.EPIC),
                    "sealed_ta"));

    /** 原初背叛：被上述方式击败的玩家，护甲栏被清空，复活后这件东西戴在头盔栏。 */
    public static final DeferredHolder<Item, Item> PRIMORDIAL_BETRAYAL = ITEMS.register("primordial_betrayal",
            () -> new MaixrLoreItem(new Item.Properties().stacksTo(1).fireResistant().rarity(Rarity.EPIC),
                    "primordial_betrayal"));

    // ================== 伊甸工程 ==================

    /** 伊甸核心机：多方块控制器，提交物品换来修复额度。 */
    public static final DeferredHolder<Block, Block> EDEN_CORE = BLOCKS.register("eden_core",
            () -> new EdenCoreBlock(BlockBehaviour.Properties.of()
                    .strength(6.0F)
                    .lightLevel(state -> 13)
                    .noOcclusion()));
    public static final DeferredHolder<Item, Item> EDEN_CORE_ITEM = ITEMS.register("eden_core",
            () -> new BlockItem(EDEN_CORE.get(), new Item.Properties()));

    /** 伊甸框架：围着核心摆 3x3 外圈。 */
    public static final DeferredHolder<Block, Block> EDEN_FRAME = BLOCKS.register("eden_frame",
            () -> new EdenFrameBlock(BlockBehaviour.Properties.of()
                    .strength(4.0F)
                    .lightLevel(state -> 6)
                    .noOcclusion()));      // 只画边框，中间透明，方便看下面的世界
    public static final DeferredHolder<Item, Item> EDEN_FRAME_ITEM = ITEMS.register("eden_frame",
            () -> new BlockItem(EDEN_FRAME.get(), new Item.Properties()));

    /** 弧光·裂隙意志 —— 弧光纪元（90~100%）入侵里的最终 Boss。 */
    public static final DeferredHolder<EntityType<?>, EntityType<ArcLightBoss>> ARC_LIGHT_BOSS = ENTITIES.register("arc_light_boss",
            () -> EntityType.Builder.of(ArcLightBoss::new, MobCategory.MONSTER)
                    .sized(1.6F, 2.6F)
                    .fireImmune()
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, "arc_light_boss").toString()));

    /** 四个团簇（动态贴图：美食·蓝 / 作物·金 / 自然·绿 / 血肉·红）。 */
    public static final DeferredHolder<Item, Item> FOOD_CLUSTER = ITEMS.register("food_cluster",
            () -> new Item(new Item.Properties()));
    public static final DeferredHolder<Item, Item> CROP_CLUSTER = ITEMS.register("crop_cluster",
            () -> new Item(new Item.Properties()));
    public static final DeferredHolder<Item, Item> NATURE_CLUSTER = ITEMS.register("nature_cluster",
            () -> new Item(new Item.Properties()));
    public static final DeferredHolder<Item, Item> FLESH_CLUSTER = ITEMS.register("flesh_cluster",
            () -> new Item(new Item.Properties()));

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<?>> PIGMEE_DUPLICATION_SERIALIZER =
            RECIPE_SERIALIZERS.register("creative_pigmee_duplication",
                    () -> MaixrPigmeeDuplicationRecipe.SERIALIZER);

    // ==================== 奥术转化余质 + 三张带耐久的转化桌 ====================
    //
    // 三个转化桌右键打开 ProjectE 的转化桌界面（反射调用，缺 ProjectE 也不崩），
    // 界面**关闭时**扣 1 点耐久；耐久耗尽则物品消失并留下「奥术转化余质」。

    /** 奥术转化余质：转化桌用尽后的残留物。 */
    public static final DeferredHolder<Item, Item> ARCANE_TRANSMUTATION_RESIDUE =
            ITEMS.register("arcane_transmutation_residue",
                    () -> new MaixrLoreItem(new Item.Properties().rarity(Rarity.RARE),
                            "arcane_transmutation_residue"));

    /** 裂隙所遗弃的转化桌：4 次。 */
    public static final DeferredHolder<Item, Item> RIFT_ABANDONED_TRANSMUTATION_TABLET =
            ITEMS.register("rift_abandoned_transmutation_tablet",
                    () -> new MaixrTransmutationTabletItem(
                            new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON),
                            4, ARCANE_TRANSMUTATION_RESIDUE, "rift_abandoned_transmutation_tablet",
                            0.4));   // 卖出 4 折

    /** 艰难重生的转化桌：10 次。 */
    public static final DeferredHolder<Item, Item> HARD_REBIRTH_TRANSMUTATION_TABLET =
            ITEMS.register("hard_rebirth_transmutation_tablet",
                    () -> new MaixrTransmutationTabletItem(
                            new Item.Properties().stacksTo(1).rarity(Rarity.RARE),
                            10, ARCANE_TRANSMUTATION_RESIDUE, "hard_rebirth_transmutation_tablet",
                            0.6));   // 卖出 6 折

    /** 弧光孕育的转化桌：32 次。 */
    public static final DeferredHolder<Item, Item> ARC_LIGHT_TRANSMUTATION_TABLET =
            ITEMS.register("arc_light_transmutation_tablet",
                    () -> new MaixrTransmutationTabletItem(
                            new Item.Properties().stacksTo(1).rarity(Rarity.EPIC),
                            32, ARCANE_TRANSMUTATION_RESIDUE, "arc_light_transmutation_tablet",
                            0.8));   // 卖出 8 折

    /** 是否任一玩偶物品（Maixr 玩偶 / 真理玩偶）。 */
    public static final DeferredHolder<Block, Block> RIFT_REVERSER_DOLL = BLOCKS.register("rift_reverser_doll",
            () -> new RiftReverserDollBlock(BlockBehaviour.Properties.of()
                    .strength(3.0F).noOcclusion()));

    public static final DeferredHolder<Item, Item> RIFT_REVERSER_DOLL_ITEM = ITEMS.register("rift_reverser_doll",
            () -> new MaixrDollItem(RIFT_REVERSER_DOLL.get(), new Item.Properties()));

    public static boolean isDollItem(Item item) {
        return item == SKIN_DOLL_ITEM.get() || item == TRUTH_DOLL_ITEM.get() || item == RIFT_REVERSER_DOLL_ITEM.get();
    }

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB_MAIXR = TABS.register("maixr",
            () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(SKIN_DOLL_ITEM.get()))
                    .title(Component.translatable("itemGroup.maixr"))
                    .displayItems((params, output) -> {
                        output.accept(SKIN_DOLL_ITEM.get());
                        output.accept(TRUTH_DOLL_ITEM.get());
            output.accept(RIFT_REVERSER_DOLL_ITEM.get());
                        for (DeferredHolder<Item, Item> core : CORES) {
                            output.accept(core.get());
                        }
                        output.accept(WORLD_REBIRTH_CORE.get());
                        output.accept(REVIVED_GRASS.get());
                        output.accept(FOOD_CLUSTER.get());
                        output.accept(CROP_CLUSTER.get());
                        output.accept(NATURE_CLUSTER.get());
                        output.accept(FLESH_CLUSTER.get());
                        output.accept(SOUL_GEM.get());
                        output.accept(SEALED_TA.get());
                        output.accept(PRIMORDIAL_BETRAYAL.get());
                        output.accept(EDEN_CORE_ITEM.get());
                        output.accept(EDEN_FRAME_ITEM.get());
                        // 【暂缓】两套鸟巢端口
                        // output.accept(EGG_SEWER_ITEM.get());
                        // output.accept(PINK_EGG_SEWER_ITEM.get());
                        output.accept(RIFT_ABANDONED_TRANSMUTATION_TABLET.get());
                        output.accept(HARD_REBIRTH_TRANSMUTATION_TABLET.get());
                        output.accept(ARC_LIGHT_TRANSMUTATION_TABLET.get());
                        output.accept(ARCANE_TRANSMUTATION_RESIDUE.get());
                    })
                    .build());

    /**
     * 构造：NeoForge 1.21 会把 {@code IEventBus}（mod 总线）与 {@code ModContainer} 直接注入进来，
     * 不再需要 {@code FMLJavaModLoadingContext.get().getModEventBus()}。
     */
    public MaixrMod(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        // 【弧光重生·伊甸枢纽总站】维度逻辑（放置 / 出生点 / 极光标题 / 电影运镜）
        NeoForge.EVENT_BUS.register(EdenHubHandler.class);
        // 转化桌的耐久结算（界面关闭时才扣）
        NeoForge.EVENT_BUS.register(MaixrTabletDurabilityHandler.class);
        // 「贬值」由 MaixrDiscountedTransmutationContainer 在容器内实现，无需额外事件注册。

        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
        RECIPE_SERIALIZERS.register(modBus);
        ENTITIES.register(modBus);
        FEATURES.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent event) ->
                event.put(ARC_LIGHT_BOSS.get(), ArcLightBoss.createAttributes().build()));
        MaixrBlockEntities.register(modBus);
        // AE2：两个玩偶方块实体必须显式注册「网络节点宿主」能力，否则线缆连不上
        modBus.addListener(MaixrAe2Integration::registerCapabilities);
        MaixrMenus.register(modBus);
        MaixrChunkGenerators.register(modBus);
        NeoForge.EVENT_BUS.addListener(MaixrCommands::registerCommands);
        // 注：曾经这里还注册了 MaixrServerHooks::onServerAboutToStart（把破碎维度生成器
        //     强行换成纯虚空版）。那个功能有害且已删除 —— 见 MaixrServerHooks 类注释。
        NeoForge.EVENT_BUS.addListener(MaixrServerHooks::onServerStarting);
        // 数据包重载（/reload）时重新读提交池配置
        NeoForge.EVENT_BUS.addListener(MaixrServerHooks::onDatapackSync);
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
