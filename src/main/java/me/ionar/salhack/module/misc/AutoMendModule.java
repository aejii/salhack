package me.ionar.salhack.module.misc;

import me.ionar.salhack.events.MinecraftEvent;
import me.ionar.salhack.events.player.EventPlayerMotionUpdate;
import me.ionar.salhack.module.Module;
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

public class AutoMendModule extends Module {
    // Thanks to https://github.com/fr1kin/ForgeHax/blob/1.16/src/main/java/dev/fiki/forgehax/main/mods/player/AutoMend.java

    public AutoMendModule() {
        super("AutoMend", new String[]
                { "" }, "Moves non full durability mendable items into offhand", "NONE", 0x24DBD4, ModuleType.MISC);
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

    private void swapOffhand(int slot) {
        mc.playerController.windowClick(0, 45, 0, ClickType.PICKUP, mc.player);
        mc.playerController.windowClick(0, slot < 9 ? slot + 36 : slot, 0, ClickType.PICKUP, mc.player);
        mc.playerController.windowClick(0, 45, 0, ClickType.PICKUP, mc.player);
        mc.playerController.updateController();
    }

    private int getItemSlot(int itemId) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.inventory.mainInventory.get(i);
            if (Item.getIdFromItem(stack.getItem()) == itemId) {
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

        if (mc.player.openContainer != mc.player.inventoryContainer) {
            return;
        }

        final int currentSlot = mc.player.inventory.currentItem;
        final ItemStack offhandStack = mc.player.inventory.offHandInventory.get(0);

        int bestSlot = -1;
        int bestSlotDamage = -1;
        // Only swap once offhand item is fully repaired
        if ((!offhandStack.isItemDamaged() && isMendableTool(offhandStack)) || offhandStack.isEmpty()) {
            for (int i = 0; i < 36; i++) {
                ItemStack stack = mc.player.inventory.mainInventory.get(i);
                if (isMendableTool(stack) && stack.isItemDamaged() && i != currentSlot && stack.getItemDamage() > bestSlotDamage) {
                    bestSlot = i;
                }
            }
        }

        if (bestSlot != -1) {
            // Put items on cursor into first air slot
            if (!mc.player.inventory.getItemStack().isEmpty() && mc.player.openContainer == mc.player.inventoryContainer) {
                int emptySlot = getItemSlot(Item.getIdFromItem(Items.AIR));
                if (emptySlot != -1) {
                    if (emptySlot <= 8) {
                        // Fix slot id if it's a hotbar slot
                        emptySlot += 36;
                    }
                    mc.playerController.windowClick(mc.player.inventoryContainer.windowId, emptySlot, 0, ClickType.PICKUP, mc.player);
                    mc.playerController.updateController();
                    return;
                }
            }
            swapOffhand(bestSlot);
        }
        });
}
