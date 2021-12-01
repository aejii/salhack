package me.ionar.salhack.module.misc;

import me.ionar.salhack.events.MinecraftEvent;
import me.ionar.salhack.events.player.EventPlayerMotionUpdate;
import me.ionar.salhack.module.Module;
import me.ionar.salhack.module.Value;
import me.ionar.salhack.util.BlockInteractionHelper;
import me.ionar.salhack.util.Timer;
import me.ionar.salhack.util.entity.PlayerUtil;
import me.zero.alpine.fork.listener.EventHandler;
import me.zero.alpine.fork.listener.Listener;
import net.minecraft.client.gui.inventory.GuiShulkerBox;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.init.Enchantments;
import net.minecraft.init.Items;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAir;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityShulkerBox;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;

import java.util.HashSet;
import java.util.Set;

public class AutoMendModule extends Module {
    // Thanks to https://github.com/fr1kin/ForgeHax/blob/1.16/src/main/java/dev/fiki/forgehax/main/mods/player/AutoMend.java

    public final Value<Modes> Mode = new Value<>("Mode", new String[] {""}, "Mending mode to use", Modes.Offhand);
    public final Value<Integer> HotBarSlot = new Value<>("HotBarSlot", new String[] {""}, "Hotbar slot to use when using Hotbar mode", 7, 0, 8, 1);
    public final Value<Boolean> SwapOnTimer = new Value<>("SwapOnTimer", new String[] {""}, "Swap to another mendable item if the item durability hasn't changed in the set time", true);
    public final Value<Float> SwapTime = new Value<>("SwapTime", new String[] {""}, "If item hasn't changed durability for set seconds a swap will occur", 30.0f, 0.0f, 300.0f, 1.0f);
    public final Value<Boolean> AutoLootStore = new Value<>("AutoLootStore", new String[] {""}, "If enabled will automatically get items to repair and store fully repaired items", false);
    public final Value<Shulkers> LootShulker = new Value<>("LootShulker", new String[] {""}, "Color of shulker to loot items to repair from", Shulkers.White);
    public final Value<Shulkers> StoreShulker = new Value<>("StoreShulker", new String[] {""}, "Color of shulker to store repaired items in", Shulkers.Black);
    public final Value<Integer> ItemsToLoot = new Value<>("ItemsToLoot", new String[] {""}, "Number of mendable items to loot from shulker", 9, 1, 27, 1);
    public final Value<Boolean> Whitelist = new Value<>("Whitelist", new String[] {""}, "If enabled will only use items in WhiteListItems", false);
    public final Value<Set<String>> WhitelistItems = new Value<>("WhiteListItems", new String[] {""}, "Only items in this list will be counted when looking for mendable items", new HashSet<>());
    public final Value<Set<String>> EjectItems = new Value<>("EjectItems", new String[] {""}, "If an item in this list is on the cursor it will be dropped", new HashSet<>());
    public final Value<Directions> LookDirection = new Value<>("LookDirection", new String[] {""}, "Direction to look at. Helps with AutoAggro and opening shulkers", Directions.None);

    public AutoMendModule() {
        super("AutoMend", new String[]
                { "" }, "Automatically mends items", "NONE", 0x24DBD4, ModuleType.MISC);
    }

    private States state = States.Mending;
    private Timer swapTimer = new Timer();
    private int currentDurability = 0;
    private int ticks = 0;
    private boolean lootShulkerEmpty = false;
    private boolean storeShulkerFull = false;

    private enum Modes {
        Offhand, Hotbar,
    }

    private enum Shulkers {
        Black, Blue, Brown, Cyan,
        Gray, Green, LightBlue, Lime,
        Magenta, Orange, Pink, Purple,
        Red, Silver, White, Yellow,
    }

    private enum Directions {
        South, SouthWest, West,
        NorthWest, North, NorthEast,
        East, SouthEast, None,
    }

    private enum States {
        Mending,
        OpeningStoreShulker, StoringStoreShulker,
        OpeningLootShulker, LootingLootShulker,
    }

    @Override
    public void onEnable() {
        super.onEnable();

        swapTimer.reset();
        currentDurability = 0;
        ticks = 0;
        lootShulkerEmpty = false;
        storeShulkerFull = false;
        state = States.Mending;
    }

    public boolean addEjectItem(String item) {
        boolean successful = EjectItems.getValue().add(item);
        this.SignalValueChange(EjectItems);
        return successful;
    }

    public boolean removeEjectItem(String item) {
        boolean successful = EjectItems.getValue().remove(item);
        this.SignalValueChange(EjectItems);
        return successful;
    }

    public boolean containsEjectItem(String item) {
        return EjectItems.getValue().contains(item);
    }

    public boolean addWhitelistItem(String item) {
        boolean successful = WhitelistItems.getValue().add(item);
        this.SignalValueChange(WhitelistItems);
        return successful;
    }

    public boolean removeWhitelistItem(String item) {
        boolean successful = WhitelistItems.getValue().remove(item);
        this.SignalValueChange(WhitelistItems);
        return successful;
    }

    public boolean containsWhitelistItem(String item) {
        return WhitelistItems.getValue().contains(item);
    }

    private boolean hasEnchant(ItemStack stack, Enchantment enchantment) {
        if (stack == null) {
            return false;
        }
        NBTTagList tagList = stack.getEnchantmentTagList();
        for (int i = 0; i < tagList.tagCount(); i++) {
            NBTTagCompound tagCompound = tagList.getCompoundTagAt(i);
            if (tagCompound.getShort("id") == Enchantment.getEnchantmentID(enchantment)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMendableTool(ItemStack stack) {
        if (Whitelist.getValue() && stack.getItem().getRegistryName() != null &&
                !WhitelistItems.getValue().contains(stack.getItem().getRegistryName().toString())) {
            return false;
        }
        return stack.isItemStackDamageable()
                && hasEnchant(stack, Enchantments.MENDING);
    }

    private boolean shouldSwap(ItemStack currentStack) {
        boolean doSwap = false;
        if (SwapOnTimer.getValue() && swapTimer.passed(SwapTime.getValue() * 1000f) && !currentStack.isEmpty()) {
            if (currentStack.isItemDamaged()) {
                if (currentStack.getItemDamage() == currentDurability) {
                    doSwap = true;
                    SendMessage("Swap time has passed, swapping item.");
                } else {
                    currentDurability = currentStack.getItemDamage();
                }
            }
            swapTimer.reset();
        }
        return doSwap;
    }

    private int getBestSlot() {
        int bestSlot = -1;
        int bestSlotDamage = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.inventory.mainInventory.get(i);
            if (isMendableTool(stack) && stack.isItemDamaged() && stack.getItemDamage() > bestSlotDamage) {
                bestSlotDamage = stack.getItemDamage();
                bestSlot = i;
            }
        }

        return bestSlot;
    }

    private int getNonMendedCount() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.inventory.mainInventory.get(i);
            if (isMendableTool(stack) && stack.isItemDamaged()) {
                count++;
            }
        }
        return count;
    }

    private int getNonMendedSlot() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.inventory.mainInventory.get(i);
            if (isMendableTool(stack) && stack.isItemDamaged()) {
                return i;
            }
        }
        return -1;
    }

    private int getMendedSlot() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.inventory.mainInventory.get(i);
            if (isMendableTool(stack) && !stack.isItemDamaged()) {
                return i;
            }
        }
        return -1;
    }

    private void lookAtDirection(Directions direction) {
        switch (direction) {
            case South:
                mc.player.rotationYaw = 0.0f;
                break;
            case SouthWest:
                mc.player.rotationYaw = 45.0f;
                break;
            case West:
                mc.player.rotationYaw = 90.0f;
                break;
            case NorthWest:
                mc.player.rotationYaw = 135.0f;
                break;
            case North:
                mc.player.rotationYaw = 180.0f;
                break;
            case NorthEast:
                mc.player.rotationYaw = 225.0f;
                break;
            case East:
                mc.player.rotationYaw = 270.0f;
                break;
            case SouthEast:
                mc.player.rotationYaw = 315.0f;
                break;
            case None:
            default:
                break;
        }
    }

    private EnumDyeColor getDyeColor(Shulkers shulkerColor) {
        switch (shulkerColor) {
            case Black:
                return EnumDyeColor.BLACK;
            case Blue:
                return EnumDyeColor.BLUE;
            case Brown:
                return EnumDyeColor.BROWN;
            case Cyan:
                return EnumDyeColor.CYAN;
            case Gray:
                return EnumDyeColor.GRAY;
            case Green:
                return EnumDyeColor.GREEN;
            case LightBlue:
                return EnumDyeColor.LIGHT_BLUE;
            case Lime:
                return EnumDyeColor.LIME;
            case Magenta:
                return EnumDyeColor.MAGENTA;
            case Orange:
                return EnumDyeColor.ORANGE;
            case Pink:
                return EnumDyeColor.PINK;
            case Purple:
                return EnumDyeColor.PURPLE;
            case Red:
                return EnumDyeColor.RED;
            case Silver:
                return EnumDyeColor.SILVER;
            case White:
                return EnumDyeColor.WHITE;
            case Yellow:
                return EnumDyeColor.YELLOW;
        }

        return EnumDyeColor.BLACK;
    }

    private BlockPos getClosestShulker(EnumDyeColor shulkerColor) {
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;
        for (TileEntity tileEntity : mc.world.loadedTileEntityList) {
            if (tileEntity instanceof TileEntityShulkerBox && ((TileEntityShulkerBox) tileEntity).getColor() == shulkerColor) {
                double dist = mc.player.getDistanceSq(tileEntity.getPos());
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = tileEntity.getPos();
                }
            }
        }

        return closest;
    }

    private void openShulker(Shulkers shulkerColor) {
        BlockPos closestShulker = getClosestShulker(getDyeColor(shulkerColor));
        if (closestShulker == null || mc.player.getDistanceSq(closestShulker) > Math.pow(mc.playerController.getBlockReachDistance() + 1.0, 2)) {
            SendMessage(String.format("No valid %s shulker found", shulkerColor));
            return;
        }

        Vec3d shulkerVec = new Vec3d(closestShulker);
        float[] rotations = BlockInteractionHelper.getLegitRotations(shulkerVec);
        mc.player.rotationYaw = rotations[0];
        mc.player.rotationPitch = rotations[1];
        BlockInteractionHelper.faceVectorPacketInstant(shulkerVec);
        BlockInteractionHelper.processRightClickBlock(closestShulker, EnumFacing.UP, shulkerVec);
        mc.player.swingArm(EnumHand.MAIN_HAND);
    }

    private int lootShulker() {
        Container curContainer = mc.player.openContainer;
        for (int i = 0; i < 27; i++) {
            ItemStack stack = curContainer.getSlot(i).getStack();
            if (isMendableTool(stack)) {
                mc.playerController.windowClick(curContainer.windowId, i, 0, ClickType.QUICK_MOVE, mc.player);
                mc.playerController.updateController();
                return 1;
            }
        }
        return -1;
    }

    private int storeShulker() {
        Container curContainer = mc.player.openContainer;
        for (int i = 0; i < 27; i++) {
            if (curContainer.getSlot(i).getStack().getItem() instanceof ItemAir) {
                int mendedSlot = getMendedSlot();
                mc.playerController.windowClick(curContainer.windowId, mendedSlot < 9 ? mendedSlot + 54 : mendedSlot + 18, 0, ClickType.QUICK_MOVE, mc.player);
                mc.playerController.updateController();
                return 1;
            }
        }

        return -1;
    }

    @EventHandler
    private Listener<EventPlayerMotionUpdate> OnPlayerUpdate = new Listener<>(p_Event ->
    {
        if (p_Event.getEra() != MinecraftEvent.Era.PRE)
            return;

        if (mc.player == null) {
            return;
        }

        if (ticks++ < 20) {
            return;
        }
        ticks = 0;
        lookAtDirection(LookDirection.getValue());

        switch (state) {
            case Mending: {
                // No more damaged items
                if (AutoLootStore.getValue() && getNonMendedSlot() == -1) {
                    if (getMendedSlot() == -1 && !lootShulkerEmpty) {
                        state = States.OpeningLootShulker;
                    } else if (getMendedSlot() != -1 && !storeShulkerFull) {
                        state = States.OpeningStoreShulker;
                    }
                    return;
                }

                if (mc.player.openContainer == mc.player.inventoryContainer) {
                    // Handle items on cursor
                    if (!mc.player.inventory.getItemStack().isEmpty()) {
                        if (mc.player.inventory.getItemStack().getItem().getRegistryName() != null &&
                                EjectItems.getValue().contains(mc.player.inventory.getItemStack().getItem().getRegistryName().toString())) {
                            SendMessage(String.format("Ejecting %s as it's on our cursor and in the EjectItems list.", mc.player.inventory.getItemStack().getDisplayName()));
                            mc.playerController.windowClick(mc.player.inventoryContainer.windowId, -999, 0, ClickType.PICKUP, mc.player);
                            mc.playerController.updateController();
                        } else {
                            int slot = PlayerUtil.GetItemSlot(Item.getIdFromItem(Items.AIR));
                            if (slot == -1) {
                                for (int i = 0; i < 36; i++) {
                                    ItemStack stack = mc.player.inventory.mainInventory.get(i);
                                    if (stack.getItem().getRegistryName() != null && EjectItems.getValue().contains(stack.getItem().getRegistryName().toString())) {
                                        slot = i;
                                        break;
                                    }
                                }
                            }
                            if (slot != -1) {
                                SendMessage(String.format("Placing %s into slot %d.", mc.player.inventory.getItemStack().getDisplayName(), slot));
                                mc.playerController.windowClick(mc.player.inventoryContainer.windowId, slot < 9 ? slot + 36 : slot, 0, ClickType.PICKUP, mc.player);
                                mc.playerController.updateController();
                            }
                        }
                        return;
                    }

                    switch (Mode.getValue()) {
                        case Offhand: {
                            final ItemStack offhandStack = mc.player.inventory.offHandInventory.get(0);
                            boolean doSwap = shouldSwap(offhandStack);

                            // Only swap once offhand item is fully repaired
                            if (doSwap || (!offhandStack.isItemDamaged() && isMendableTool(offhandStack)) || offhandStack.isEmpty()) {
                                int bestSlot = getBestSlot();
                                if (bestSlot != -1) {
                                    PlayerUtil.SwapOffhand(bestSlot);
                                } else {
                                    mc.player.connection.getNetworkManager().closeChannel(new TextComponentString("Best slot is invalid. Reconnect"));
                                }
                            }
                            break;
                        }
                        case Hotbar: {
                            final ItemStack currentStack = mc.player.inventory.getCurrentItem();
                            boolean doSwap = shouldSwap(currentStack);
                            if (doSwap || !currentStack.isItemDamaged() || currentStack.isEmpty()) {
                                int bestSlot = getBestSlot();
                                if (bestSlot != -1) {
                                    if (bestSlot == mc.player.inventory.currentItem) {
                                        mc.player.connection.getNetworkManager().closeChannel(new TextComponentString("Best slot is current slot. Reconnect"));
                                    } else if (bestSlot < 9) {
                                        mc.player.inventory.currentItem = bestSlot;
                                    } else {
                                        PlayerUtil.SwapWithHotBar(bestSlot, HotBarSlot.getValue());
                                    }
                                }
                            }
                            break;
                        }
                    }
                }
                break;
            }

            case OpeningStoreShulker: {
                if (mc.currentScreen instanceof GuiShulkerBox) {
                    state = States.StoringStoreShulker;
                    return;
                }

                openShulker(StoreShulker.getValue());
                break;
            }

            case StoringStoreShulker: {
                if (!(mc.currentScreen instanceof GuiShulkerBox)) {
                    state = States.OpeningStoreShulker;
                    return;
                }

                if (getMendedSlot() == -1) {
                    state = States.Mending;
                    mc.player.closeScreen();
                    return;
                }

                int itemsStored = storeShulker();
                if (itemsStored > 0) {
                    SendMessage(String.format("Stored %d items", itemsStored));
                } else {
                    SendMessage("Can't store items. Stopping");
                    mc.player.closeScreen();
                    storeShulkerFull = true;
                    state = States.Mending;
                }
                break;
            }

            case OpeningLootShulker: {
                if (mc.currentScreen instanceof GuiShulkerBox) {
                    state = States.LootingLootShulker;
                    return;
                }

                openShulker(LootShulker.getValue());
                break;
            }

            case LootingLootShulker: {
                if (!(mc.currentScreen instanceof GuiShulkerBox)) {
                    state = States.OpeningLootShulker;
                    return;
                }

                if (getNonMendedCount() >= ItemsToLoot.getValue()) {
                    SendMessage("Finished looting");
                    state = States.Mending;
                    mc.player.closeScreen();
                    return;
                }

                int itemsLooted = lootShulker();
                if (itemsLooted > 0) {
                    SendMessage(String.format("Looted %d items", itemsLooted));
                } else {
                    SendMessage("No more items to loot. Stopping");
                    mc.player.closeScreen();
                    lootShulkerEmpty = true;
                    state = States.Mending;
                }
                break;
            }
        }
    });
}
