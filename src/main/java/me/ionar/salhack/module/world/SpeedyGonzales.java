package me.ionar.salhack.module.world;

import me.ionar.salhack.events.client.EventClientTick;
import me.ionar.salhack.events.minecraft.WorldLoadEvent;
import me.ionar.salhack.events.player.EventPlayerClickBlock;
import me.ionar.salhack.events.player.EventPlayerDamageBlock;
import me.ionar.salhack.events.player.EventPlayerResetBlockRemoving;
import me.ionar.salhack.events.player.EventPlayerUpdate;
import me.ionar.salhack.module.Module;
import me.ionar.salhack.module.Value;
import me.ionar.salhack.util.Timer;
import me.zero.alpine.fork.listener.EventHandler;
import me.zero.alpine.fork.listener.Listener;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.client.CPacketPlayerDigging;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;

public final class SpeedyGonzales extends Module
{
    public final Value<Mode> mode = new Value<Mode>("Mode", new String[]
    { "Mode", "M" }, "The speed-mine mode to use.", Mode.Instant);
    public final Value<Float> Speed = new Value<Float>("Speed", new String[]
    { "S" }, "Speed for Bypass Mode", 1.0f, 0.0f, 1.0f, 0.1f);

    private enum Mode
    {
        Packet, Damage, Instant, Bypass
    }

    public final Value<Boolean> reset = new Value<Boolean>("Reset", new String[]
    { "Res" }, "Stops current block destroy damage from resetting if enabled.", true);
    public final Value<Boolean> doubleBreak = new Value<Boolean>("DoubleBreak", new String[]
    { "DoubleBreak", "Double", "DB" }, "Mining a block will also mine the block above it, if enabled.", false);
    public final Value<Boolean> FastFall = new Value<Boolean>("FastFall", new String[]
    { "FF" }, "Makes it so you fall faster.", false);
    public final Value<Integer> SlowModeSpeed = new Value<>("SMSpeed", new String[] { "SlowModeSpeed" }, "Time in ms between block breaks when in slow mode instant mine", 200, 0, 1000, 10);
    public final Value<Integer> SlowModeLength = new Value<>("SMLength", new String[] { "SlowModeLength" }, "Time in minutes to use slow mode before going back to normal speed", 30, 0, 120, 1);
    public final Value<Integer> SlowModeWorldLoads = new Value<>("SMWLoads", new String[] { "SlowModeWorldLoads" }, "Amount of world loads in SMWLTime before activating slow mode", 3, 1, 20, 1);
    public final Value<Integer> SlowModeWorldLoadsTime = new Value<>("SMWLTime", new String[] { "SlowModeWorldLoadsTime" }, "Time in minutes to check for SMWLoads count before activating slow mode", 2, 0, 10, 1);
    public final Value<Boolean> OnlyNetherrack = new Value<>("OnlyNetherrack", new String[] { "" }, "If enabled will only mine netherrack quicker", true);

    private Timer timer = new Timer();
    private int worldLoads = 0;
    private boolean customPause = false;

    private boolean slowMode = false;
    private Timer slowModeTimer = new Timer();
    private Timer slowModeRunningTime = new Timer();

    public SpeedyGonzales()
    {
        super("SpeedyGonzales", new String[]
        { "Speedy Gonzales" }, "Allows you to break blocks faster", "NONE", 0x24DB60, ModuleType.WORLD);
    }

    @Override
    public String getMetaData()
    {
        return this.mode.getValue().name();
    }

    @EventHandler
    private Listener<WorldLoadEvent> onWorldLoad = new Listener<>(p_Event -> {
        if (p_Event.Client != null) {
            if (worldLoads == 0) {
                timer.reset();
            }
            worldLoads++;
        }

    });

    @EventHandler
    private Listener<EventClientTick> onTick = new Listener<>(p_Event -> {
        // Been 30 minutes and mod is disabled, so enable it
       if ((timer.passed(SlowModeLength.getValue() * 60000) && customPause) || (slowModeRunningTime.passed(SlowModeLength.getValue() * 60000) && slowMode)) {
           timer.reset();
           customPause = false;
           slowMode = false;
           worldLoads = 0;
           SendMessage("It has been " + SlowModeLength.getValue() + " minutes. Resuming this module.");
           //this.toggle();
       }


        // In 120 seconds there have been 3 world loads
        if (!timer.passed(SlowModeWorldLoadsTime.getValue() * 60000) && worldLoads >= SlowModeWorldLoads.getValue() && !customPause) {
            timer.reset();
            worldLoads = 0;
            //this.toggle();
            if (slowMode) {
                customPause = true;
                SendMessage("There have been " + SlowModeWorldLoads.getValue() + " world loads in less than " + SlowModeWorldLoadsTime.getValue() + " minutes. Pausing this module for " + SlowModeLength.getValue() + " minutes.");
            } else {
                slowMode = true;
                slowModeRunningTime.reset();
                SendMessage("Trying slower mining mode.");
            }


            return;
        }

        if (timer.passed(SlowModeWorldLoadsTime.getValue() * 60000) && !customPause) {
            timer.reset();
            worldLoads = 0;
        }
    });

    @EventHandler
    private Listener<EventPlayerUpdate> OnPlayerUpdate = new Listener<>(p_Event ->
    {
        if (customPause) {
            return;
        }

        mc.playerController.blockHitDelay = 0;

        if (this.reset.getValue() && Minecraft.getMinecraft().gameSettings.keyBindUseItem.isKeyDown())
        {
            mc.playerController.isHittingBlock = false;
        }

        if (FastFall.getValue())
        {
            if (mc.player.onGround)
                --mc.player.motionY;
        }
    });

    @EventHandler
    private Listener<EventPlayerResetBlockRemoving> ResetBlock = new Listener<>(p_Event ->
    {
        if (customPause) {
            return;
        }

        if (this.reset.getValue())
        {
            p_Event.cancel();
        }
    });

    @EventHandler
    private Listener<EventPlayerClickBlock> ClickBlock = new Listener<>(p_Event ->
    {
        if (customPause) {
            return;
        }

        if (this.reset.getValue())
        {
            if (mc.playerController.curBlockDamageMP > 0.1f)
            {
                mc.playerController.isHittingBlock = true;
            }
        }
    });

    @EventHandler
    private Listener<EventPlayerDamageBlock> OnDamageBlock = new Listener<>(p_Event ->
    {
        if (customPause) {
            return;
        }

        if (canBreak(p_Event.getPos()))
        {
            if (this.reset.getValue())
            {
                mc.playerController.isHittingBlock = false;
            }

            switch (this.mode.getValue())
            {
            case Packet:
                mc.player.swingArm(EnumHand.MAIN_HAND);
                mc.player.connection.sendPacket(new CPacketPlayerDigging(
                        CPacketPlayerDigging.Action.START_DESTROY_BLOCK, p_Event.getPos(), p_Event.getDirection()));
                mc.player.connection.sendPacket(new CPacketPlayerDigging(CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK,
                        p_Event.getPos(), p_Event.getDirection()));
                p_Event.cancel();
                break;
            case Damage:
                if (mc.playerController.curBlockDamageMP >= 0.7f)
                {
                    mc.playerController.curBlockDamageMP = 1.0f;
                }
                break;
            case Instant:
                if (slowMode && !slowModeTimer.passed(SlowModeSpeed.getValue())) {
                    break;
                }
                mc.player.swingArm(EnumHand.MAIN_HAND);
                mc.player.connection.sendPacket(new CPacketPlayerDigging(
                        CPacketPlayerDigging.Action.START_DESTROY_BLOCK, p_Event.getPos(), p_Event.getDirection()));
                mc.player.connection.sendPacket(new CPacketPlayerDigging(CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK,
                        p_Event.getPos(), p_Event.getDirection()));
                mc.playerController.onPlayerDestroyBlock(p_Event.getPos());
                mc.world.setBlockToAir(p_Event.getPos());

                slowModeTimer.reset();
                break;
            case Bypass:

                mc.player.swingArm(EnumHand.MAIN_HAND);

                final IBlockState blockState = Minecraft.getMinecraft().world.getBlockState(p_Event.getPos());
                
                float l_Speed = blockState.getPlayerRelativeBlockHardness(mc.player, mc.world, p_Event.getPos()) * Speed.getValue();
                
                
              //  mc.playerController.onPlayerDestroyBlock(;)
                
                break;
            }
        }

        if (this.doubleBreak.getValue())
        {
            final BlockPos above = p_Event.getPos().add(0, 1, 0);

            if (canBreak(above) && mc.player.getDistance(above.getX(), above.getY(), above.getZ()) <= 5f)
            {
                mc.player.swingArm(EnumHand.MAIN_HAND);
                mc.player.connection.sendPacket(new CPacketPlayerDigging(
                        CPacketPlayerDigging.Action.START_DESTROY_BLOCK, above, p_Event.getDirection()));
                mc.player.connection.sendPacket(new CPacketPlayerDigging(CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK,
                        above, p_Event.getDirection()));
                mc.playerController.onPlayerDestroyBlock(above);
                mc.world.setBlockToAir(above);
            }
        }
    });

    private boolean canBreak(BlockPos pos)
    {
        final IBlockState blockState = mc.world.getBlockState(pos);
        final Block block = blockState.getBlock();

        if (OnlyNetherrack.getValue()) {
            return block == Blocks.NETHERRACK && block.getBlockHardness(blockState, Minecraft.getMinecraft().world, pos) != -1;
        } else {
            return block.getBlockHardness(blockState, Minecraft.getMinecraft().world, pos) != -1;
        }
    }

}
