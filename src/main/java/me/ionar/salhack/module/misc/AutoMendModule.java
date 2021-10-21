package me.ionar.salhack.module.misc;

import me.ionar.salhack.events.MinecraftEvent;
import me.ionar.salhack.events.player.EventPlayerMotionUpdate;
import me.ionar.salhack.module.Module;
import me.ionar.salhack.module.Value;
import me.ionar.salhack.util.Timer;
import me.ionar.salhack.util.entity.PlayerUtil;
import me.zero.alpine.fork.listener.EventHandler;
import me.zero.alpine.fork.listener.Listener;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.init.Enchantments;
import net.minecraft.init.Items;
import net.minecraft.inventory.ClickType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

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
    public final Value<Set<String>> EjectItems = new Value<>("EjectItems", new String[] {""}, "If an item in this list is on the cursor it will be dropped", new HashSet<>());

    public AutoMendModule() {
        super("AutoMend", new String[]
                { "" }, "Moves non full durability mendable items into offhand", "NONE", 0x24DBD4, ModuleType.MISC);
    }

    private Timer swapTimer = new Timer();
    private int currentDurability = 0;
    private int ticks = 0;

    private enum Modes {
        Offhand, Hotbar
    }

    private enum Shulkers {
        Black, Blue, Brown, Cyan,
        Gray, Green, LightBlue, Lime,
        Magenta, Orange, Pink, Purple,
        Red, Silver, White, Yellow
    }

    @Override
    public void onEnable() {
        super.onEnable();

        swapTimer.reset();
        currentDurability = 0;
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
                        }
                    }
                    break;
                }
                case Hotbar: {
                    final ItemStack currentStack = mc.player.inventory.getCurrentItem();
                    boolean doSwap = shouldSwap(currentStack);
                    if (doSwap || (!currentStack.isItemDamaged() && isMendableTool(currentStack)) || currentStack.isEmpty()) {
                        int bestSlot = getBestSlot();
                        if (bestSlot != -1) {
                            if (bestSlot < 9) {
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

    });
}
