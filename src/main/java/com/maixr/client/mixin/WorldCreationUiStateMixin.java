package com.maixr.client.mixin;

import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 「破碎」设为默认世界类型：新建世界时把创建界面的<b>初始选中项</b>换成
 * {@code maixr:broken} preset（用户决定）。
 *
 * <p>★ <b>不覆盖 vanilla 的 {@code minecraft:normal} preset</b> —— 有些模组/逻辑
 * 期望"普通"就是原版地形，覆盖它会出现期望 normal 却拿到碎裂场的兼容 bug。
 * 这里只动创建界面的初始选中项：用户仍可在循环按钮里切回真正的原版普通。</p>
 *
 * <p>替换条件：初始选中项为空或 {@code WorldPresets.NORMAL}（即"新建默认世界"）；
 * 调试世界/其它显式传入的 preset 不动。走 {@code setWorldType} 保证
 * dimensions 设置与选中项同步重建。</p>
 */
@Mixin(WorldCreationUiState.class)
public abstract class WorldCreationUiStateMixin {

    @Shadow
    private WorldCreationUiState.WorldTypeEntry worldType;

    @Shadow
    public abstract WorldCreationContext getSettings();

    @Shadow
    public abstract void setWorldType(WorldCreationUiState.WorldTypeEntry entry);

    @Inject(method = "<init>", at = @At("TAIL"))
    private void maixr$defaultToWorldType(Path gameDir, WorldCreationContext context,
                                          Optional<ResourceKey<WorldPreset>> selected, OptionalLong seed,
                                          CallbackInfo ci) {
        Holder<WorldPreset> current = this.worldType == null ? null : this.worldType.preset();
        boolean vanillaDefault = current == null || current.is(WorldPresets.NORMAL);
        if (!vanillaDefault) {
            return;
        }
        Holder<WorldPreset> broken = this.getSettings().worldgenLoadContext()
                .registryOrThrow(Registries.WORLD_PRESET)
                .getHolder(ResourceKey.create(Registries.WORLD_PRESET,
                        ResourceLocation.fromNamespaceAndPath("maixr", "broken")))
                .orElse(null);
        if (broken != null) {
            this.setWorldType(new WorldCreationUiState.WorldTypeEntry(broken));
        }
    }
}
