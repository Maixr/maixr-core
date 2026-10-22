package com.maixr;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /maixr 配方脚本生成命令（全部输出<b>可点击复制</b>的 KubeJS 代码）。
 *
 * <p>统一约定：<b>快捷栏 0~8 是材料，副手是产物</b>。</p>
 *
 * <table border="1">
 *   <tr><th>命令</th><th>生成</th><th>取材</th></tr>
 *   <tr><td>{@code list-recipe}</td><td>event.shaped</td><td>0~8 → 3×3 格子，副手 → 产物</td></tr>
 *   <tr><td>{@code list-recipeless}</td><td>event.shapeless</td><td>0~8 全部材料，副手 → 产物</td></tr>
 *   <tr><td>{@code list-smelting}</td><td>event.smelting</td><td>0 号材料，副手 → 产物</td></tr>
 *   <tr><td>{@code list-blasting}</td><td>event.blasting</td><td>0 号材料，副手 → 产物</td></tr>
 *   <tr><td>{@code list-smoking}</td><td>event.smoking</td><td>0 号材料，副手 → 产物</td></tr>
 *   <tr><td>{@code list-campfire}</td><td>event.campfireCooking</td><td>0 号材料，副手 → 产物</td></tr>
 *   <tr><td>{@code list-stonecutting}</td><td>event.stonecutting</td><td>0 号材料，副手 → 产物</td></tr>
 *   <tr><td>{@code list-smithing}</td><td>event.smithing</td><td>0 模板 / 1 底 / 2 添加，副手 → 产物</td></tr>
 *   <tr><td>{@code list-remove}</td><td>event.remove</td><td>副手 → 要移除的产物</td></tr>
 * </table>
 *
 * <p>下划线命名（{@code list_recipe} 等）保留为别名。</p>
 */
public final class MaixrCommands {

    private MaixrCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("maixr")
                .then(Commands.literal("list-recipe").executes(c -> shaped(c.getSource())))
                .then(Commands.literal("list_recipe").executes(c -> shaped(c.getSource())))
                .then(Commands.literal("list-recipeless").executes(c -> shapeless(c.getSource())))
                .then(Commands.literal("list_recipeless").executes(c -> shapeless(c.getSource())))
                .then(Commands.literal("list-smelting").executes(c -> cooking(c.getSource(), "smelting")))
                .then(Commands.literal("list-blasting").executes(c -> cooking(c.getSource(), "blasting")))
                .then(Commands.literal("list-smoking").executes(c -> cooking(c.getSource(), "smoking")))
                .then(Commands.literal("list-campfire").executes(c -> cooking(c.getSource(), "campfireCooking")))
                .then(Commands.literal("list-stonecutting").executes(c -> cooking(c.getSource(), "stonecutting")))
                .then(Commands.literal("list-smithing").executes(c -> smithing(c.getSource())))
                .then(Commands.literal("list-remove").executes(c -> removeOnly(c.getSource())))
                // ---- 其他模组 ----
                .then(Commands.literal("list-create")
                        .then(Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(c -> modRecipe(c.getSource(), "create",
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "type")))))
                .then(Commands.literal("list-mekanism")
                        .then(Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(c -> modRecipe(c.getSource(), "mekanism",
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "type")))))
                .then(Commands.literal("list-ae2")
                        .then(Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(c -> modRecipe(c.getSource(), "ae2",
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "type")))))
                // ---- 通用：列 id / 看内容 ----
                .then(Commands.literal("recipe-ids")
                        .then(Commands.argument("mod", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(c -> listRecipeIds(c.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "mod")))))
                .then(Commands.literal("recipe-dump")
                        .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(c -> dumpRecipe(c.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(c, "id"))))));
    }

    // ------------------------------------------------------------ 通用

    private static ServerPlayer playerWithResult(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (player.getOffhandItem().isEmpty()) {
            source.sendFailure(Component.literal("§c请把合成产物放到副手"));
            return null;
        }
        return player;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String recipeId(ItemStack result, String suffix) {
        String id = itemId(result);
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return "maixr:" + path + (suffix.isEmpty() ? "" : "_" + suffix);
    }

    private static String removeLine(ItemStack result) {
        return "event.remove({output:'" + itemId(result) + "'});\n";
    }

    private static Component clickable(String text) {
        return Component.literal(text).withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("§e点击复制"))));
    }

    private static int send(ServerPlayer player, String title, String script) {
        player.sendSystemMessage(Component.literal("§e===== " + title + "（点击复制）====="));
        player.sendSystemMessage(clickable(script));
        return 1;
    }

    // ------------------------------------------------------------ 各类配方

    private static int shaped(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = playerWithResult(source);
        if (player == null) {
            return 0;
        }
        ItemStack result = player.getOffhandItem();
        var inv = player.getInventory().items;
        String[] ids = new String[9];
        int n = 0;
        for (int i = 0; i < 9; i++) {
            if (!inv.get(i).isEmpty()) {
                ids[i] = itemId(inv.get(i));
                n++;
            }
        }
        if (n == 0) {
            source.sendFailure(Component.literal("§c快捷栏 0~8 没有材料"));
            return 0;
        }
        String[] letters = {"A", "B", "C", "D", "E", "F", "G", "H", "I"};
        StringBuilder sb = new StringBuilder(removeLine(result));
        sb.append("event.shaped(Item.of('").append(itemId(result)).append("'),\n            [\n");
        for (int r = 0; r < 3; r++) {
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < 3; c++) {
                int idx = r * 3 + c;
                line.append(ids[idx] != null ? letters[idx] : " ");
            }
            sb.append("                '").append(line).append("'").append(r < 2 ? "," : "").append("\n");
        }
        sb.append("            ],\n            {\n");
        boolean first = true;
        for (int i = 0; i < 9; i++) {
            if (ids[i] == null) {
                continue;
            }
            if (!first) {
                sb.append(",\n");
            }
            sb.append("                ").append(letters[i]).append(": '").append(ids[i]).append("'");
            first = false;
        }
        sb.append("\n            }\n    ).id('").append(recipeId(result, "")).append("');");
        return send(player, "KubeJS shaped 脚本", sb.toString());
    }

    private static int shapeless(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = playerWithResult(source);
        if (player == null) {
            return 0;
        }
        ItemStack result = player.getOffhandItem();
        var inv = player.getInventory().items;
        StringBuilder sb = new StringBuilder(removeLine(result));
        sb.append("event.shapeless(Item.of('").append(itemId(result)).append("'),\n            [");
        boolean first = true;
        for (int i = 0; i < 9; i++) {
            if (inv.get(i).isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(",\n            ");
            }
            sb.append("'").append(itemId(inv.get(i))).append("'");
            first = false;
        }
        sb.append("]\n    ).id('").append(recipeId(result, "")).append("');");
        return send(player, "KubeJS shapeless 脚本", sb.toString());
    }

    /** 单材料 + 单产物的一类配方（熔炉/高炉/烟熏/营火/切石）。 */
    private static int cooking(CommandSourceStack source, String type) throws CommandSyntaxException {
        ServerPlayer player = playerWithResult(source);
        if (player == null) {
            return 0;
        }
        ItemStack input = player.getInventory().items.get(0);
        if (input.isEmpty()) {
            source.sendFailure(Component.literal("§c快捷栏 0 号位没有材料"));
            return 0;
        }
        ItemStack result = player.getOffhandItem();
        StringBuilder sb = new StringBuilder(removeLine(result));
        sb.append("event.").append(type)
                .append("(Item.of('").append(itemId(result)).append("'), '").append(itemId(input)).append("')")
                .append(".id('").append(recipeId(result, type)).append("');");
        return send(player, "KubeJS " + type + " 脚本", sb.toString());
    }

    /** 锻造台：0 模板 / 1 底 / 2 添加，副手产物。 */
    private static int smithing(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = playerWithResult(source);
        if (player == null) {
            return 0;
        }
        var inv = player.getInventory().items;
        ItemStack template = inv.get(0);
        ItemStack base = inv.get(1);
        ItemStack addition = inv.get(2);
        if (template.isEmpty() || base.isEmpty() || addition.isEmpty()) {
            source.sendFailure(Component.literal("§c快捷栏 0=模板、1=底、2=添加 三者都要有"));
            return 0;
        }
        ItemStack result = player.getOffhandItem();
        StringBuilder sb = new StringBuilder(removeLine(result));
        sb.append("event.smithing(Item.of('").append(itemId(result)).append("'), '")
                .append(itemId(template)).append("', '")
                .append(itemId(base)).append("', '")
                .append(itemId(addition)).append("')")
                .append(".id('").append(recipeId(result, "smithing")).append("');");
        return send(player, "KubeJS smithing 脚本", sb.toString());
    }

    // ============================================================
    //  其他模组
    // ============================================================

    /** 其他模组的 KJS 生成：create / mekanism / ae2。 */
    private static int modRecipe(CommandSourceStack source, String mod, String type) throws CommandSyntaxException {
        ServerPlayer player = playerWithResult(source);
        if (player == null) {
            return 0;
        }
        ItemStack result = player.getOffhandItem();
        var inv = player.getInventory().items;
        String out = itemId(result);
        String in0 = inv.get(0).isEmpty() ? null : itemId(inv.get(0));
        java.util.List<String> ins = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            if (!inv.get(i).isEmpty()) {
                ins.add(itemId(inv.get(i)));
            }
        }
        if (ins.isEmpty()) {
            source.sendFailure(Component.literal("§c快捷栏里没有材料"));
            return 0;
        }
        String list = "[" + String.join(", ", ins.stream().map(s -> "'" + s + "'").toList()) + "]";
        String body;
        String key = mod + ":" + type.toLowerCase(java.util.Locale.ROOT);
        switch (key) {
            // ---- Create ----
            case "create:crushing" -> body = "event.recipes.create.crushing([Item.of('" + out + "')], " + list + ")";
            case "create:milling" -> body = "event.recipes.create.milling([Item.of('" + out + "')], '" + in0 + "')";
            case "create:mixing" -> body = "event.recipes.create.mixing([Item.of('" + out + "')], " + list + ")";
            case "create:compacting" -> body = "event.recipes.create.compacting([Item.of('" + out + "')], " + list + ")";
            case "create:pressing" -> body = "event.recipes.create.pressing([Item.of('" + out + "')], '" + in0 + "')";
            case "create:cutting" -> body = "event.recipes.create.cutting(Item.of('" + out + "'), " + list + ")";
            case "create:splashing" -> body = "event.recipes.create.splashing([Item.of('" + out + "')], " + list + ")";
            // ---- Mekanism ----
            case "mekanism:enriching" -> body = "event.recipes.mekanism.enriching(Item.of('" + out + "'), '" + in0 + "')";
            case "mekanism:crushing" -> body = "event.recipes.mekanism.crushing(Item.of('" + out + "'), '" + in0 + "')";
            case "mekanism:smelting" -> body = "event.recipes.mekanism.smelting(Item.of('" + out + "'), '" + in0 + "')";
            case "mekanism:injecting" -> body = "event.recipes.mekanism.injecting(Item.of('" + out + "'), '" + in0 + "', 'mekanism:oxygen')";
            case "mekanism:purifying" -> body = "event.recipes.mekanism.purifying(Item.of('" + out + "'), '" + in0 + "', 'mekanism:oxygen')";
            case "mekanism:metallurgic" -> body = "event.recipes.mekanism.metallurgic_infusing(Item.of('" + out + "'), '" + in0 + "', 'mekanism:redstone', 10)";
            // ---- AE2 ----
            case "ae2:charger" -> body = "event.recipes.ae2.charger(Item.of('" + out + "'), '" + in0 + "')";
            case "ae2:inscriber" -> body = "// 压印器：三份输入请按实际修改\nevent.recipes.ae2.inscriber(Item.of('" + out + "'), {top: '" + in0 + "', middle: '" + in0 + "', bottom: '" + in0 + "'})";
            default -> {
                source.sendFailure(Component.literal("§c不支持：" + mod + " / " + type));
                source.sendSuccess(() -> Component.literal("§7可用："
                        + "§ecreate§7: crushing/milling/mixing/compacting/pressing/cutting/splashing  "
                        + "§emekanism§7: enriching/crushing/smelting/injecting/purifying/metallurgic  "
                        + "§eae2§7: charger/inscriber"), false);
                return 0;
            }
        }
        StringBuilder sb = new StringBuilder(removeLine(result));
        sb.append(body).append(".id('").append(recipeId(result, mod + "_" + type.toLowerCase(java.util.Locale.ROOT))).append("');");
        return send(player, "KubeJS " + mod + " / " + type + " 脚本", sb.toString());
    }

    /** 列出某个命名空间下的全部配方 id（点击复制）。 */
    private static int listRecipeIds(CommandSourceStack source, String mod) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RecipeManager manager = source.getServer().getRecipeManager();
        int total = 0;
        int shown = 0;
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (!holder.id().getNamespace().equals(mod)) {
                continue;
            }
            total++;
            if (shown >= 80) {
                continue;
            }
            shown++;
            player.sendSystemMessage(clickable(holder.id().toString()));
        }
        final int t = total;
        final int s = shown;
        source.sendSuccess(() -> Component.literal("§a" + mod + " 共 " + t + " 条配方"
                + (s < t ? "（先列出前 " + s + " 条，点击复制）" : "（点击复制）")), false);
        return total;
    }

    /** 打印一条配方的结构：序列化器 / 类型 / 产物 / 材料。 */
    private static int dumpRecipe(CommandSourceStack source, String id) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            source.sendFailure(Component.literal("§c配方 id 格式不对：" + id));
            return 0;
        }
        var opt = source.getServer().getRecipeManager().byKey(rl);
        if (opt.isEmpty()) {
            source.sendFailure(Component.literal("§c找不到配方：" + id));
            return 0;
        }
        Recipe<?> recipe = opt.get().value();
        String serializer = String.valueOf(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()));
        String type = String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()));
        ItemStack result = recipe.getResultItem(source.getServer().registryAccess());
        player.sendSystemMessage(Component.literal("§eid: §f" + rl));
        player.sendSystemMessage(Component.literal("§e序列化器: §f" + serializer + "   §e类型: §f" + type));
        player.sendSystemMessage(Component.literal("§e产物: §f" + itemId(result) + " ×" + result.getCount()));
        int i = 0;
        for (Ingredient ing : recipe.getIngredients()) {
            i++;
            StringBuilder sb = new StringBuilder();
            for (ItemStack st : ing.getItems()) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(itemId(st));
            }
            player.sendSystemMessage(clickable("输入" + i + ": " + sb));
        }
        return 1;
    }

    /** 只生成移除该产物的那一行。 */
    private static int removeOnly(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = playerWithResult(source);
        if (player == null) {
            return 0;
        }
        ItemStack result = player.getOffhandItem();
        return send(player, "KubeJS remove 脚本", removeLine(result));
    }
}
