package com.maixr;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Maixr 命令：
 *  /maixr broken             传送到破碎世界
 *  /maixr list-uid           列出全部物品注册 ID（点击复制）
 *  /maixr hand [槽位]         复制指定槽位物品 ID
 *  /maixr list_recipe [shape|shapeless]
 *      快捷栏 0-8 摆合成材料、副手放产物：
 *      shapeless → 无序脚本；shape → 有序脚本（0-8 对应 3x3 工作台格）
 */
public final class MaixrCommands {
    /** 破碎世界维度（见 {@link BrokenWorld}）。 */
    public static final ResourceKey<Level> BROKEN_DIMENSION = BrokenWorld.BROKEN;
    public static final ResourceKey<LevelStem> BROKEN_STEM =
            ResourceKey.create(Registries.LEVEL_STEM, ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "overworld_broken"));

    private static final SimpleCommandExceptionType NO_SPOT =
            new SimpleCommandExceptionType(Component.literal("\u00a7c找不到可用的落脚区块"));
    private static final SimpleCommandExceptionType NO_DIM =
            new SimpleCommandExceptionType(Component.literal("\u00a7c破碎世界维度未加载（请确认数据包/模组文件正常）"));

    private MaixrCommands() {}

    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("maixr")
                .then(Commands.literal("broken")
                        .executes(context -> broken(context.getSource())))
                .then(Commands.literal("list-uid")
                        .executes(context -> listUid(context.getSource())))
                .then(Commands.literal("hand")
                        .executes(context -> hand(context.getSource(), EquipmentSlot.MAINHAND))
                        .then(Commands.literal("offhand").executes(context -> hand(context.getSource(), EquipmentSlot.OFFHAND)))
                        .then(Commands.literal("head").executes(context -> hand(context.getSource(), EquipmentSlot.HEAD)))
                        .then(Commands.literal("chest").executes(context -> hand(context.getSource(), EquipmentSlot.CHEST)))
                        .then(Commands.literal("legs").executes(context -> hand(context.getSource(), EquipmentSlot.LEGS)))
                        .then(Commands.literal("feet").executes(context -> hand(context.getSource(), EquipmentSlot.FEET))))
                .then(Commands.literal("list_recipe")
                        .executes(context -> listRecipe(context.getSource(), false, false))
                        .then(Commands.literal("shape").executes(context -> listRecipe(context.getSource(), true, false)))
                        .then(Commands.literal("shapeless").executes(context -> listRecipe(context.getSource(), false, false))))
                // ★ 带 NBT 版本：产物/材料都会写成 Item.of('id', '{...}')，并自动附带 .id('maixr:xxx')
                .then(Commands.literal("list_recipe_nbt")
                        .executes(context -> listRecipe(context.getSource(), false, true))
                        .then(Commands.literal("shape").executes(context -> listRecipe(context.getSource(), true, true)))
                        .then(Commands.literal("shapeless").executes(context -> listRecipe(context.getSource(), false, true))))
                .then(Commands.literal("eden")
                        .executes(context -> MaixrEdenCommands.status(context))
                        .then(Commands.literal("top").executes(context -> MaixrEdenCommands.top(context)))
                        .then(Commands.literal("set")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("percent", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 100))
                                        .executes(context -> MaixrEdenCommands.setPercent(context,
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "percent")))))
                        .then(Commands.literal("add")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("percent", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 100))
                                        .executes(context -> MaixrEdenCommands.addPercent(context,
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "percent")))))
                        .then(Commands.literal("boss")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> MaixrEdenCommands.spawnBoss(context)))
                        .then(Commands.literal("pool")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> MaixrEdenCommands.pool(context)))
                        .then(Commands.literal("kit")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> MaixrEdenCommands.kit(context)))
                        .then(Commands.literal("center")
                                .executes(context -> MaixrEdenCommands.center(context)))
                        .then(Commands.literal("reanchor")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> MaixrEdenCommands.reanchor(context)))
                        .then(Commands.literal("refill")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> MaixrEdenCommands.refill(context)))
                        .then(Commands.literal("cleanhub")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> MaixrEdenCommands.cleanhub(context))))
                // ★ 陨石机制诊断：报出周围所有线缆总线、每个面的部件类名、判定结果
                .then(Commands.literal("skydebug")
                        .executes(context -> skydebug(context.getSource()))));
    }

    /** 生成"点击复制"消息。 */
    private static Component clickable(String copyText) {
        return Component.literal(copyText).withStyle(s -> s
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, copyText))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("\u00a7e\u70b9\u51fb\u590d\u5236"))));
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** /maixr skydebug —— 排查"破坏面板不产陨石粉"：把周围看到的东西全报出来 */
    private static int skydebug(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!(player.level() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("§c只能在服务端维度执行"));
            return 0;
        }
        String report = SkyDustCondenser.debugReport(level, player);
        source.sendSuccess(() -> Component.literal("§b[陨石] 诊断：" + report), false);
        MaixrMod.LOGGER.info("[陨石] skydebug by {}: {}", player.getGameProfile().getName(),
                report.replace("§", "&"));
        return 1;
    }

    private static int broken(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        MinecraftServer server = source.getServer();
        ServerLevel brokenLevel = BrokenWorld.resolve(server);
        if (brokenLevel == null) {
            throw NO_DIM.create();
        }
        ChunkPos found = null;
        outer:
        for (int radius = 0; radius <= 200 && found == null; radius++) {
            for (int dx = -radius; dx <= radius && found == null; dx++) {
                for (int dz = -radius; dz <= radius && found == null; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    if (!BrokenOverworldChunkGenerator.isVoided(dx, dz)) {
                        found = new ChunkPos(dx, dz);
                        break outer;
                    }
                }
            }
        }
        if (found == null) {
            throw NO_SPOT.create();
        }
        // ★ 落点 = 伊甸核心机正上方（以前是随便找一块陆地丢在 y=280 的高空）
        EdenProgress eden = EdenProgress.get(brokenLevel);
        double x = (eden.centerChosen ? eden.centerX + 0.5D : found.getMinBlockX() + 8.0D);
        double z = (eden.centerChosen ? eden.centerZ + 0.5D : found.getMinBlockZ() + 8.0D);
        double y = eden.centerChosen
                ? Math.max(eden.centerY, brokenLevel.getMinBuildHeight() + 1) + 1.0D : 280.0D;
        player.teleportTo(brokenLevel, x, y, z, player.getYRot(), player.getXRot());
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 20, 1, false, false));
        source.sendSuccess(() -> Component.literal("\u00a7a你被抛入了一个支离破碎的世界……"), false);
        return 1;
    }

    /** /maixr hand [main/offhand/head/chest/legs/feet] */
    private static int hand(CommandSourceStack source, EquipmentSlot slot) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack stack = player.getItemBySlot(slot);
        if (stack.isEmpty()) {
            source.sendFailure(Component.literal("\u00a7c该槽位没有物品"));
            return 0;
        }
        String id = itemId(stack);
        player.sendSystemMessage(Component.literal("\u00a77[" + slot.getName() + "] ").append(clickable("\u00a7f" + id)));
        MaixrMod.LOGGER.info("hand by {}: {}", player.getName().getString(), id);
        return 1;
    }

    /** /maixr list-uid —— 背包 + 副手 + 盔甲 + Curios，逐项点击复制。 */
    private static int listUid(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<String[]> rows = new ArrayList<>();
        var inv = player.getInventory().items;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!s.isEmpty()) {
                rows.add(new String[]{ i <= 8 ? "\u00a76hotbar:" + i : "\u00a72inv:" + i, itemId(s) });
            }
        }
        if (!player.getOffhandItem().isEmpty()) {
            rows.add(new String[]{ "\u00a7doffhand", itemId(player.getOffhandItem()) });
        }
        String[] armorNames = { "head", "chest", "legs", "feet" };
        for (int i = 0; i < 4; i++) {
            ItemStack s = player.getInventory().armor.get(i);
            if (!s.isEmpty()) {
                rows.add(new String[]{ "\u00a7barmor:" + armorNames[i], itemId(s) });
            }
        }
        if (MaixrCurios.isLoaded()) {
            for (String[] c : MaixrCurios.scanCurios(player)) {
                rows.add(new String[]{ "\u00a75" + c[0], c[1] });
            }
        }
        if (rows.isEmpty()) {
            player.sendSystemMessage(Component.literal("\u00a77身上空空如也"));
            return 0;
        }
        for (String[] row : rows) {
            player.sendSystemMessage(Component.literal(row[0] + " ").append(clickable("\u00a7f" + row[1])));
        }
        source.sendSuccess(() -> Component.literal("\u00a7a共 " + rows.size() + " 个物品（点击条目可复制 ID）"), false);
        return rows.size();
    }

    /** /maixr list_recipe [shape|shapeless] —— 0-8 材料、副手产物。shape=true 生成有序脚本。 */
    private static int listRecipe(CommandSourceStack source, boolean shaped, boolean withNbt) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack result = player.getOffhandItem();
        if (result.isEmpty()) {
            source.sendFailure(Component.literal("\u00a7c请把合成产物放到副手"));
            return 0;
        }
        var inv = player.getInventory().items;
        String[] ids = new String[9];
        int matCount = 0;
        for (int i = 0; i < 9; i++) {
            if (!inv.get(i).isEmpty()) {
                ids[i] = itemId(inv.get(i));
                matCount++;
            }
        }
        if (matCount == 0) {
            source.sendFailure(Component.literal("\u00a7c快捷栏 0-8 没有材料"));
            return 0;
        }
        // 收集材料栈（带 NBT 版需要完整 ItemStack 才能读出 NBT）
        ItemStack[] stacks = new ItemStack[9];
        for (int i = 0; i < 9; i++) stacks[i] = inv.get(i).isEmpty() ? ItemStack.EMPTY : inv.get(i).copy();
        String script = withNbt
                ? (shaped ? buildShapedNbt(result, stacks) : buildShapelessNbt(result, stacks))
                : (shaped ? buildShaped(result, ids) : buildShapeless(result, ids));
        player.sendSystemMessage(Component.literal("\u00a7e===== KubeJS " + (shaped ? "shaped" : "shapeless")
                + (withNbt ? " + NBT" : "") + " 脚本（点击复制）====="));
        player.sendSystemMessage(clickable(script));
        MaixrMod.LOGGER.info("list_recipe({}) by {}: result={}", shaped ? "shape" : "shapeless",
                player.getName().getString(), itemId(result));
        return 1;
    }

    /**
     * 生成 remove 行。
     *
     * ★ 重要：如果产物是和别的等级共用同一 id 的物品（靠 NBT 区分，例如实体加速卡），
     *   event.remove({output:...}) 会忽略 NBT 从而一次删掉所有等级 —— 所以这里主动提示
     *   玩家改用按配方 id 移除。
     */
    private static String removeLine(ItemStack result, boolean withNbt) {
        String id = itemId(result);
        boolean hasNbt = NbtCompat.has(result) && !NbtCompat.get(result).isEmpty();
        StringBuilder sb = new StringBuilder();
        if (hasNbt || withNbt) {
            sb.append("// \u26a0 本物品疑似「同一 id + NBT 区分等级」：请把下面这行改成按配方 id 移除，\n");
            sb.append("//    例如 event.remove({id:'").append(id).append("_xxx'})（配方 id 见 mod 的 data/<ns>/recipes/ 文件名）\n");
        }
        sb.append("event.remove({output:").append(hasNbt ? expr(result, true) : "'" + id + "'").append("});\n");
        return sb.toString();
    }

    /** 物品表达式：withNbt 时输出 Item.of('id', '{...}')，否则输出纯 id 字符串。 */
    private static String expr(ItemStack stack, boolean withNbt) {
        String id = itemId(stack);
        if (withNbt && NbtCompat.has(stack) && !NbtCompat.get(stack).isEmpty()) {
            String snbt = NbtCompat.get(stack).toString().replace("\\", "\\\\").replace("'", "\\'");
            return "Item.of('" + id + "', '" + snbt + "')";
        }
        return "'" + id + "'";
    }

    /** 由产物推导配方 id：maixr:<物品名>（带 NBT 时追加其中的数字，避免同名配方冲突）。 */
    private static String recipeId(ItemStack result) {
        String path = itemId(result).split(":")[1];
        String suffix = "";
        if (NbtCompat.has(result) && !NbtCompat.get(result).isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(NbtCompat.get(result).toString());
            StringBuilder nums = new StringBuilder();
            while (m.find()) nums.append("_").append(m.group());
            suffix = nums.toString();
        }
        return "maixr:" + path.replace('/', '_') + suffix;
    }

    /** 带 NBT 的有序脚本。 */
    private static String buildShapedNbt(ItemStack result, ItemStack[] stacks) {
        String[] letters = { "A", "B", "C", "D", "E", "F", "G", "H", "I" };
        StringBuilder sb = new StringBuilder();
        sb.append(removeLine(result, true));
        sb.append("event.shaped(").append(expr(result, true)).append(",\n");
        sb.append("            [\n");
        for (int r = 0; r < 3; r++) {
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < 3; col++) {
                int idx = r * 3 + col;
                line.append(stacks[idx] != null && !stacks[idx].isEmpty() ? letters[idx] : " ");
            }
            sb.append("                '").append(line).append("'");
            if (r < 2) sb.append(",");
            sb.append("\n");
        }
        sb.append("            ],\n");
        sb.append("            {\n");
        boolean first = true;
        for (int i = 0; i < 9; i++) {
            if (stacks[i] == null || stacks[i].isEmpty()) continue;
            if (!first) sb.append(",\n");
            sb.append("                ").append(letters[i]).append(": ").append(expr(stacks[i], true));
            first = false;
        }
        sb.append("\n            }\n    ).id('").append(recipeId(result)).append("');");
        return sb.toString();
    }

    /** 带 NBT 的无序脚本。 */
    private static String buildShapelessNbt(ItemStack result, ItemStack[] stacks) {
        StringBuilder sb = new StringBuilder();
        sb.append(removeLine(result, true));
        sb.append("event.shapeless(").append(expr(result, true)).append(",\n            [");
        boolean first = true;
        for (ItemStack st : stacks) {
            if (st == null || st.isEmpty()) continue;
            if (!first) sb.append(",\n            ");
            sb.append(expr(st, true));
            first = false;
        }
        sb.append("]\n    ).id('").append(recipeId(result)).append("');");
        return sb.toString();
    }

    /** 生成 event.shapeless 脚本。 */
    private static String buildShapeless(ItemStack result, String[] ids) {
        StringBuilder sb = new StringBuilder();
        // ★ 自动附带 remove 行：复制粘贴即可替换原配方，不会再漏写（漏写会导致整个脚本加载失败）
        sb.append(removeLine(result, false));
        sb.append("event.shapeless(Item.of('").append(itemId(result)).append("'),\n");
        sb.append("            [");
        boolean first = true;
        for (String id : ids) {
            if (id == null) continue;
            if (!first) sb.append(",\n            ");
            sb.append("'").append(id).append("'");
            first = false;
        }
        sb.append("]\n    ).id('").append(recipeId(result)).append("');");
        return sb.toString();
    }

    /** 生成 event.shaped 脚本：0-8 → 3x3（0左上 … 8右下），空位为空格，字母 A-I 标记。 */
    private static String buildShaped(ItemStack result, String[] ids) {
        String[] letters = { "A", "B", "C", "D", "E", "F", "G", "H", "I" };
        String[] lines = new String[3];
        for (int r = 0; r < 3; r++) {
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < 3; col++) {
                int idx = r * 3 + col;
                line.append(ids[idx] != null ? letters[idx] : " ");
            }
            lines[r] = line.toString();
        }
        StringBuilder sb = new StringBuilder();
        // ★ 自动附带 remove 行（同上）
        sb.append(removeLine(result, false));
        sb.append("event.shaped(Item.of('").append(itemId(result)).append("'),\n");
        sb.append("            [\n");
        for (int r = 0; r < 3; r++) {
            sb.append("                '").append(lines[r]).append("'");
            if (r < 2) sb.append(",");
            sb.append("\n");
        }
        sb.append("            ],\n");
        sb.append("            {\n");
        boolean first = true;
        for (int i = 0; i < 9; i++) {
            if (ids[i] == null) continue;
            if (!first) sb.append(",\n");
            sb.append("                ").append(letters[i]).append(": '").append(ids[i]).append("'");
            first = false;
        }
        sb.append("\n            }\n    ).id('").append(recipeId(result)).append("');");
        return sb.toString();
    }
}
