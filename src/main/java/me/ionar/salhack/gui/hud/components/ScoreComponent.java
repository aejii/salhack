package me.ionar.salhack.gui.hud.components;

import com.mojang.realmsclient.gui.ChatFormatting;
import me.ionar.salhack.gui.hud.HudComponentItem;
import me.ionar.salhack.util.render.RenderUtil;

import java.text.DecimalFormat;

public class ScoreComponent extends HudComponentItem {

    public ScoreComponent() {
        super("Score", 2, 260);
    }

    private final DecimalFormat format = new DecimalFormat("#,###");
    @Override
    public void render(int p_MouseX, int p_MouseY, float p_PartialTicks) {
        super.render(p_MouseX, p_MouseY, p_PartialTicks);

        final String score = String.format(ChatFormatting.GRAY + "Score%s %s", ChatFormatting.WHITE, format.format(mc.player.getScore()));

        RenderUtil.drawStringWithShadow(score, GetX(), GetY(), -1);

        SetWidth(RenderUtil.getStringWidth(score));
        SetHeight(RenderUtil.getStringHeight(score) + 1);
    }
}
