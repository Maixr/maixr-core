package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 【人造粉红鸟巢端口】—— 「人造粉红鸟巢」多方块机器的交互端口方块。
 *
 * <p>结构与交互完全参考 {@link EggSewerBlockEntity 人造排污鸟巢}，差别只有：
 * <ul>
 *   <li>图纸外壳用钢机壳 + 粉红羊毛 + 屠宰场</li>
 *   <li>产物是 {@code industrialforegoing:pink_slime}（粉红粘液），单一产物、无模式切换</li>
 *   <li>耗电更高：<b>100000 FE = 1 mB</b>（排污鸟巢是 10000）</li>
 *   <li>能量缓存 <b>10,000,000 FE</b>（= 10000 kFE，UI 显示 10000 kFE）</li>
 * </ul>
 *
 * <p><b>蛋不消耗</b>：蛋只是"催化剂"占用槽位，靠个数决定产量。
 */
public class PinkEggSewerBlockEntity extends BlockEntity {

    // ------------------------------------------------------------------ 图纸
    /**
     * 来自玩家提供的 pink.nbt（3×3×3）：
     * <pre>
     *   dy=+1 顶   W W W     dy=0 中   W P W     dy=-1 底   K K K
     *              W S W                P S P                 K S K
     *              W W W                W P W                 K K K
     *   W = 粉红羊毛；K = 钢机壳；S = 屠宰场（中心柱）；P = 粉红鸟巢端口（中层四条边中点，4 个）
     * </pre>
     */
    public static final String[][] LAYERS = {
            {   // dy = -1 底：钢机壳 8 + 中心屠宰场
                    "KKK",
                    "KSK",
                    "KKK"
            },
            {   // dy = 0 中：四个端口 P 位于四条边中点
                    "WPW",
                    "PSP",
                    "WPW"
            },
            {   // dy = +1 顶：粉红羊毛 8 + 中心屠宰场
                    "WWW",
                    "WSW",
                    "WWW"
            }
    };

    public static final Map<Character, String> KINDS = Map.of(
            'K', "mekanism:steel_casing",
            'W', "minecraft:pink_wool",
            'S', "industrialforegoing:mob_slaughter_factory"
    );

    /** 四个端口相对整机中心（中层 dy=0）的偏移，与图纸里的 P 一一对应 */
    public static final int[][] PORT_OFFSETS = {
            {-1, 0, 0},
            {1, 0, 0},
            {0, 0, -1},
            {0, 0, 1}
    };

    // ------------------------------------------------------------------ 参数
    /** 每产出 1 mB 液体消耗多少 FE（排污鸟巢是 10000，此处按需求提高到 100000） */
    public static final int FE_PER_MB = 100_000;
    /** 每 10 秒每个蛋产多少 mB（与排污鸟巢一致：1 mB/蛋/秒） */
    public static final int MB_PER_EGG_PER_10S = 10;
    private static final int EGG_TICKS_PER_MB = 20;
    public static final int TANK_CAPACITY = 64000;
    /** 能量缓存 10,000,000 FE = 10000 kFE */
    public static final int ENERGY_CAPACITY = 10_000_000;
    public static final int SLOTS = 9;

    /** 唯一产物：粉红粘液 */
    public static final String PRODUCT_FLUID = "industrialforegoing:pink_slime";

    private static final TagKey<Item>[] EGG_TAGS = new TagKey[]{
            TagKey.create(net.minecraft.core.registries.Registries.ITEM, new ResourceLocation("balm", "eggs")),
            TagKey.create(net.minecraft.core.registries.Registries.ITEM, new ResourceLocation("forge", "eggs")),
            TagKey.create(net.minecraft.core.registries.Registries.ITEM, new ResourceLocation("exoticbirds", "eggs"))
    };

    /** 粉红粘液的粒子色（粉紫） */
    private static final DustParticleOptions PINK_DUST =
            new DustParticleOptions(new Vector3f(0.98F, 0.45F, 0.72F), 1.4F);

    private static final Map<String, Block> BLOCK_CACHE = new HashMap<>();
    private static final Map<String, Fluid> FLUID_CACHE = new HashMap<>();
    private static boolean loggedMissing = false;

    // ------------------------------------------------------------------ 状态
    private final ItemStackHandler inventory = new ItemStackHandler(SLOTS) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return isEgg(stack);
        }
    };

    private final FluidTank tank = new FluidTank(TANK_CAPACITY) {
        @Override
        protected void onContentsChanged() {
            setChanged();
        }
    };

    private final EnergyStorage energy = new EnergyStorage(ENERGY_CAPACITY, ENERGY_CAPACITY, 0, 0) {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int got = super.receiveEnergy(maxReceive, simulate);
            if (got > 0 && !simulate) setChanged();
            return got;
        }
    };

    private LazyOptional<IItemHandler> itemCap;
    private LazyOptional<IFluidHandler> fluidCap;
    private LazyOptional<IEnergyStorage> energyCap;

    private long eggTicks = 0L;
    private boolean formed = false;
    private int mode = 0;                 // 仅为兼容 UI 数据槽，粉红鸟巢无模式切换
    private int lastProduced = 0;

    // ------------------------------------------------------------------ UI 数据
    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            PinkEggSewerBlockEntity m = master();
            return switch (index) {
                case 0 -> m.tank.getFluidAmount();
                case 1 -> TANK_CAPACITY;
                case 2 -> m.formed ? 1 : 0;
                case 3 -> totalEggs();
                case 4 -> productionPerSecond();
                case 5 -> 0;
                case 6 -> m.energy.getEnergyStored() / 1000;      // kFE
                case 7 -> ENERGY_CAPACITY / 1000;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // 粉红鸟巢无模式切换，接收但不处理
        }

        @Override
        public int getCount() {
            return 8;
        }
    };

    public PinkEggSewerBlockEntity(BlockPos pos, BlockState state) {
        super(MaixrBlockEntities.PINK_EGG_SEWER.get(), pos, state);
        itemCap = LazyOptional.of(() -> inventory);
        fluidCap = LazyOptional.of(() -> new MasterFluidHandler(this));
        energyCap = LazyOptional.of(() -> new MasterEnergyHandler(this));
    }

    public ContainerData data() {
        return data;
    }

    public IItemHandler inventory() {
        return inventory;
    }

    public FluidTank tank() {
        return tank;
    }

    public EnergyStorage energy() {
        return energy;
    }

    public int mode() {
        return mode;
    }

    public boolean isFormed() {
        return formed;
    }

    // ------------------------------------------------------------------ 蛋
    public static boolean isEgg(ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (TagKey<Item> tag : EGG_TAGS) {
            if (stack.is(tag)) return true;
        }
        return false;
    }

    public int countEggs() {
        int n = 0;
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (isEgg(s)) n += s.getCount();
        }
        return n;
    }

    public int totalEggs() {
        int n = 0;
        for (PinkEggSewerBlockEntity p : ports()) n += p.countEggs();
        return n;
    }

    public int productionPerSecond() {
        return totalEggs() * MB_PER_EGG_PER_10S / 10;
    }

    // ------------------------------------------------------------------ 端口集合 / 主机
    /** 当前结构下四个端口的位置（结构不成立时为空表） */
    public List<BlockPos> portPositions() {
        Level lvl = this.level;
        List<BlockPos> out = new ArrayList<>();
        if (lvl == null) return out;
        for (int rot = 0; rot < 4; rot++) {
            for (int[] off : PORT_OFFSETS) {
                int rx = rotX(off[0], off[2], rot);
                int rz = rotZ(off[0], off[2], rot);
                BlockPos center = this.worldPosition.offset(-rx, 0, -rz);
                if (!matchesRotation(lvl, center, rot)) continue;
                for (int[] o : PORT_OFFSETS) {
                    out.add(center.offset(rotX(o[0], o[2], rot), o[1], rotZ(o[0], o[2], rot)));
                }
                return out;
            }
        }
        return out;
    }

    private List<PinkEggSewerBlockEntity> ports() {
        Level lvl = this.level;
        List<PinkEggSewerBlockEntity> out = new ArrayList<>();
        if (lvl != null) {
            for (BlockPos p : portPositions()) {
                if (lvl.getBlockEntity(p) instanceof PinkEggSewerBlockEntity be) out.add(be);
            }
        }
        if (out.isEmpty()) out.add(this);
        return out;
    }

    /** 主机 = 四端口里坐标最小者（排序稳定，不会互相抢） */
    public PinkEggSewerBlockEntity master() {
        PinkEggSewerBlockEntity best = this;
        for (PinkEggSewerBlockEntity be : ports()) {
            BlockPos a = be.worldPosition;
            BlockPos b = best.worldPosition;
            if (a.getY() < b.getY()
                    || (a.getY() == b.getY() && a.getX() < b.getX())
                    || (a.getY() == b.getY() && a.getX() == b.getX() && a.getZ() < b.getZ())) {
                best = be;
            }
        }
        return best;
    }

    private boolean isMaster() {
        return master() == this;
    }

    // ------------------------------------------------------------------ 液体
    /** 唯一产物：粉红粘液（找不到就返回 null，tick 会跳过） */
    @Nullable
    public Fluid outputFluid() {
        return FLUID_CACHE.computeIfAbsent(PRODUCT_FLUID, key -> {
            Fluid f = BuiltInRegistries.FLUID.getValue(new ResourceLocation(key));
            if (f == null && !loggedMissing) {
                loggedMissing = true;
                MaixrMod.LOGGER.warn("[人造粉红鸟巢] 流体 {} 不存在（缺 Industrial Foregoing？）", key);
            }
            return f;
        });
    }

    /** 非主机端口收到 FE → 塞进主机缓冲 */
    private static final class MasterEnergyHandler implements IEnergyStorage {
        private final PinkEggSewerBlockEntity port;

        MasterEnergyHandler(PinkEggSewerBlockEntity port) {
            this.port = port;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            PinkEggSewerBlockEntity m = port.master();
            int got = m.energy.receiveEnergy(maxReceive, simulate);
            if (got > 0 && !simulate) m.setChanged();
            return got;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return port.master().energy.getEnergyStored();
        }

        @Override
        public int getMaxEnergyStored() {
            return ENERGY_CAPACITY;
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    }

    /** 任意端口的面都能抽到主机输出罐 */
    private static final class MasterFluidHandler implements IFluidHandler {
        private final PinkEggSewerBlockEntity port;

        MasterFluidHandler(PinkEggSewerBlockEntity port) {
            this.port = port;
        }

        private FluidTank target() {
            return port.master().tank;
        }

        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return target().getFluidInTank(0);
        }

        @Override
        public int getTankCapacity(int tank) {
            return TANK_CAPACITY;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return false;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            return 0;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return target().drain(resource, action);
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return target().drain(maxDrain, action);
        }
    }

    // ------------------------------------------------------------------ 结构校验
    @Nullable
    private static Block blockOf(String id) {
        return BLOCK_CACHE.computeIfAbsent(id, key -> {
            Block b = BuiltInRegistries.BLOCK.getValue(new ResourceLocation(key));
            if (b == null) {
                if (!loggedMissing) {
                    loggedMissing = true;
                    MaixrMod.LOGGER.warn("[人造粉红鸟巢端口] 图纸方块 {} 不存在（缺模组？）", key);
                }
                return net.minecraft.world.level.block.Blocks.AIR;
            }
            return b;
        });
    }

    public boolean checkStructure() {
        Level lvl = this.level;
        if (lvl == null) return false;
        for (int rot = 0; rot < 4; rot++) {
            for (int[] off : PORT_OFFSETS) {
                int rx = rotX(off[0], off[2], rot);
                int rz = rotZ(off[0], off[2], rot);
                if (matchesRotation(lvl, this.worldPosition.offset(-rx, 0, -rz), rot)) return true;
            }
        }
        return false;
    }

    private boolean matchesRotation(Level lvl, BlockPos center, int rot) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    char kind = LAYERS[dy + 1][dz + 1].charAt(dx + 1);
                    if (kind == 'P') continue;
                    String id = KINDS.get(kind);
                    if (id == null) return false;
                    Block expect = blockOf(id);
                    if (expect == net.minecraft.world.level.block.Blocks.AIR) return false;
                    if (lvl.getBlockState(center.offset(rotX(dx, dz, rot), dy, rotZ(dx, dz, rot))).getBlock() != expect) {
                        return false;
                    }
                }
            }
        }
        for (int[] off : PORT_OFFSETS) {
            if (!(lvl.getBlockState(center.offset(rotX(off[0], off[2], rot), off[1], rotZ(off[0], off[2], rot)))
                    .getBlock() instanceof PinkEggSewerBlock)) {
                return false;
            }
        }
        return true;
    }

    private static int rotX(int x, int z, int rot) {
        return switch (rot) {
            case 1 -> -z;
            case 2 -> -x;
            case 3 -> z;
            default -> x;
        };
    }

    private static int rotZ(int x, int z, int rot) {
        return switch (rot) {
            case 1 -> x;
            case 2 -> -z;
            case 3 -> -x;
            default -> z;
        };
    }

    // ------------------------------------------------------------------ tick
    public static void serverTick(Level level, BlockPos pos, BlockState state, PinkEggSewerBlockEntity be) {
        be.lastProduced = 0;
        if (level.getGameTime() % 20 == 0L || !be.formed) {
            boolean now = be.checkStructure();
            if (now != be.formed) {
                be.formed = now;
                be.setChanged();
                if (now && level.getGameTime() % 20 == 0L) {
                    MaixrMod.LOGGER.info("[人造粉红鸟巢] 结构成立 @ {}", pos);
                }
            }
        }
        if (!be.formed || !be.isMaster()) return;

        int eggs = be.totalEggs();
        Fluid fluid = be.outputFluid();
        if (eggs <= 0 || fluid == null) return;

        be.eggTicks += eggs;
        int mbWanted = (int) (be.eggTicks / EGG_TICKS_PER_MB);
        if (mbWanted <= 0) return;

        int mbByEnergy = be.energy.getEnergyStored() / FE_PER_MB;
        int space = be.tank.getCapacity() - be.tank.getFluidAmount();
        if (!be.tank.getFluid().isEmpty() && be.tank.getFluid().getFluid() != fluid) space = 0;
        int mb = Math.min(mbWanted, Math.min(mbByEnergy, Math.max(0, space)));
        if (mb <= 0) {
            be.eggTicks = Math.min(be.eggTicks, EGG_TICKS_PER_MB);
            return;
        }
        be.eggTicks -= (long) mb * EGG_TICKS_PER_MB;
        be.energy.extractEnergy(mb * FE_PER_MB, false);
        be.lastProduced = be.tank.fill(new FluidStack(fluid, mb), IFluidHandler.FluidAction.EXECUTE);
        if (be.lastProduced > 0) be.setChanged();
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, PinkEggSewerBlockEntity be) {
        if (!be.formed) return;
        if (level.random.nextInt(3) != 0) return;
        double x = pos.getX() + 0.5D + (level.random.nextDouble() - 0.5D) * 1.4D;
        double z = pos.getZ() + 0.5D + (level.random.nextDouble() - 0.5D) * 1.4D;
        level.addParticle(PINK_DUST, x, pos.getY() + 1.1D, z,
                (level.random.nextDouble() - 0.5D) * 0.02D,
                0.02D + level.random.nextDouble() * 0.02D,
                (level.random.nextDouble() - 0.5D) * 0.02D);
    }

    // ------------------------------------------------------------------ 能力
    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ENERGY) return energyCap.cast();
        if (cap == ForgeCapabilities.FLUID_HANDLER) return fluidCap.cast();
        if (cap == ForgeCapabilities.ITEM_HANDLER) return itemCap.cast();
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        itemCap.invalidate();
        fluidCap.invalidate();
        energyCap.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        itemCap = LazyOptional.of(() -> inventory);
        fluidCap = LazyOptional.of(() -> new MasterFluidHandler(this));
        energyCap = LazyOptional.of(() -> new MasterEnergyHandler(this));
    }

    // ------------------------------------------------------------------ 存档
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Items", inventory.serializeNBT());
        tag.put("Tank", tank.writeToNBT(new CompoundTag()));
        tag.putInt("Energy", energy.getEnergyStored());
        tag.putLong("EggTicks", eggTicks);
        tag.putBoolean("Formed", formed);
        tag.putInt("Mode", mode);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Items")) inventory.deserializeNBT(tag.getCompound("Items"));
        if (tag.contains("Tank")) tank.readFromNBT(tag.getCompound("Tank"));
        if (tag.contains("Energy")) energy.receiveEnergy(tag.getInt("Energy"), false);
        eggTicks = tag.getLong("EggTicks");
        formed = tag.getBoolean("Formed");
        mode = tag.getInt("Mode");
    }

    public static Component title() {
        return Component.translatable("block.maixr.pink_egg_sewer");
    }

    /** 供 UI 用：产物显示名 */
    public String fluidLabel() {
        Fluid f = outputFluid();
        if (f == null) return "?";
        return new FluidStack(f, 1).getDisplayName().getString();
    }
}
