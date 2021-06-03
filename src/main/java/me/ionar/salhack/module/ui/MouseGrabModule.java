package me.ionar.salhack.module.ui;

import me.ionar.salhack.module.Module;
import net.minecraft.util.MouseHelper;
import org.lwjgl.input.Mouse;

// Thanks to https://github.com/microsoft/malmo/blob/master/Minecraft/src/main/java/com/microsoft/Malmo/Client/MalmoModClient.java
public class MouseGrabModule extends Module {

    MouseHelper mouseHelperBackup;
    MouseHook mouseHook;

    public MouseGrabModule() {
        super("Mouse Grab", new String[] { "" }, "Control if Minecraft should grab your mouse cursor", "NONE", 0xDBC824, ModuleType.UI);
        mouseHelperBackup = mc.mouseHelper;
        mouseHook = new MouseHook();
    }

    @Override
    public void onEnable() {
        mc.mouseHelper = mouseHook;
    }

    @Override
    public void onDisable() {
        mc.mouseHelper = mouseHelperBackup;
    }

    @Override
    public void onToggle() {
        System.setProperty("fml.noGrab", this.isEnabled() ? "true" : "false");
    }

    public static class MouseHook extends MouseHelper {
        @Override
        public void ungrabMouseCursor() {
            Mouse.setGrabbed(false);
        }
    }
}
