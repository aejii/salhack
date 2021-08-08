package me.ionar.salhack.command.impl;

import me.ionar.salhack.command.Command;
import me.ionar.salhack.managers.ModuleManager;
import me.ionar.salhack.module.misc.AutoMendModule;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;

public class AutoMendCommand extends Command {
    public AutoMendCommand() {
        super("AutoMend", "Allows you to change AutoMend config");
    }

    @Override
    public void ProcessCommand(String p_Args)
    {
        String[] l_Split = p_Args.split(" ");

        if (l_Split == null || l_Split.length <= 1)
        {
            SendToChat("Invalid Input");
            return;
        }

        AutoMendModule autoMendModule = (AutoMendModule) ModuleManager.Get().GetMod(AutoMendModule.class);

        if (autoMendModule != null)
        {
            if (l_Split[1].equalsIgnoreCase("addEject") || l_Split[1].equalsIgnoreCase("removeEject")) {
                if (l_Split.length <= 2) {
                    SendToChat(String.format("Not enough arguments for addEject. Example command: AutoMend %s netherrack", l_Split[1]));
                    return;
                }

                Item tempItem = Item.getByNameOrId(l_Split[2]);
                if (tempItem == null) {
                    SendToChat("Invalid item name or id provided");
                    return;
                }
                ResourceLocation itemResource = Item.REGISTRY.getNameForObject(tempItem);
                if (itemResource == null) {
                    SendToChat("Invalid item name or id provided");
                    return;
                }

                String itemString = itemResource.toString();
                if (l_Split[1].equalsIgnoreCase("addEject")) {
                    if (autoMendModule.addEjectItem(itemString)) {
                        SendToChat(String.format("Successfully added %s to the ejection list.", itemString));
                    } else {
                        SendToChat(String.format("Error adding %s to the ejection list. %s", itemString, autoMendModule.containsEjectItem(itemString) ? "Already exists in the list." : ""));
                    }
                } else if (l_Split[1].equalsIgnoreCase("removeEject")) {
                    if (autoMendModule.removeEjectItem(itemString)) {
                        SendToChat(String.format("Successfully removed %s from the ejection list.", itemString));
                    } else {
                        SendToChat(String.format("Error removing %s from the ejection list. %s", itemString, autoMendModule.containsEjectItem(itemString) ? "" : "Doesn't exist in the list."));
                    }
                }
            }

            //SendToChat(String.format("%sToggled %s", l_Mod.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED, l_Mod.GetArrayListDisplayName()));
        }
    }

    @Override
    public String GetHelp()
    {
        return "Allows you to change AutoMend config";
    }
}
