package me.ionar.salhack.module.world;

import me.ionar.salhack.events.network.EventNetworkPostPacketEvent;
import me.ionar.salhack.events.player.EventPlayerDamageBlock;
import me.ionar.salhack.events.player.EventPlayerMotionUpdate;
import me.ionar.salhack.events.render.RenderEvent;
import me.ionar.salhack.module.Module;
import me.ionar.salhack.module.Value;
import me.ionar.salhack.util.Timer;
import me.ionar.salhack.util.entity.PlayerUtil;
import me.ionar.salhack.util.render.RenderUtil;
import me.zero.alpine.fork.listener.EventHandler;
import me.zero.alpine.fork.listener.Listener;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.CPacketPlayerDigging;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import static org.lwjgl.opengl.GL11.*;

public class InstantMineModule extends Module
{
    public final Value<Boolean> Visualize = new Value<Boolean>("Visualize", new String[] {"Render"}, "Visualizes which block is being mined", true);
    public final Value<Boolean> AutoBreak = new Value<Boolean>("Auto Break", new String[] {""}, "Automatically mines selected block", true);
    public final Value<Float> Delay = new Value<Float>("Delay", new String[] {"Delay"}, "Delay of the mining in ms", 20f, 0.0f, 500.0f, 1f);
    public final Value<Boolean> PicOnly = new Value<Boolean>("Pickaxe Only", new String[] {""}, "Only mines when holding a pickaxe", true);
    public final Value<Boolean> OffhandEChest = new Value<>("Offhand EChest", new String[] {""}, "Automatically put EChests into empty offhand", false);


    private ICamera camera = new Frustum();
    private BlockPos renderBlock;
    private BlockPos lastBlock;
    private boolean packetCancel = false;
    private Timer breakTimer = new Timer();
    private EnumFacing direction;

    public InstantMineModule()
    {
        super("InstantMine", new String[]
                { "InstantMine" }, "Instantly Mines Blocks", "NONE", 0x96DB24, ModuleType.WORLD);
    }

    @Override
    public void onEnable() {
        super.onEnable();
        renderBlock = null;
        lastBlock = null;
        breakTimer.reset();
        direction = null;
    }

    @EventHandler
    private Listener<RenderEvent> OnRenderEvent = new Listener<>(p_Event ->
    {
        if (!Visualize.getValue() || renderBlock == null)
            return;
        // Do drawing on renderBlock here

        IBlockState l_State = mc.world.getBlockState(renderBlock);

        if (l_State != null && l_State.getBlock() != Blocks.AIR && l_State.getBlock() != Blocks.WATER)
            return;

        final AxisAlignedBB bb = new AxisAlignedBB(renderBlock.getX() - mc.getRenderManager().viewerPosX,
                renderBlock.getY() - mc.getRenderManager().viewerPosY, renderBlock.getZ() - mc.getRenderManager().viewerPosZ,
                renderBlock.getX() + 1 - mc.getRenderManager().viewerPosX,
                renderBlock.getY() + (1) - mc.getRenderManager().viewerPosY,
                renderBlock.getZ() + 1 - mc.getRenderManager().viewerPosZ);

        camera.setPosition(mc.getRenderViewEntity().posX, mc.getRenderViewEntity().posY,
                mc.getRenderViewEntity().posZ);

        if (camera.isBoundingBoxInFrustum(new AxisAlignedBB(bb.minX + mc.getRenderManager().viewerPosX,
                bb.minY + mc.getRenderManager().viewerPosY, bb.minZ + mc.getRenderManager().viewerPosZ,
                bb.maxX + mc.getRenderManager().viewerPosX, bb.maxY + mc.getRenderManager().viewerPosY,
                bb.maxZ + mc.getRenderManager().viewerPosZ)))
        {
            GlStateManager.pushMatrix();
            GlStateManager.enableBlend();
            GlStateManager.disableDepth();
            GlStateManager.tryBlendFuncSeparate(770, 771, 0, 1);
            GlStateManager.disableTexture2D();
            GlStateManager.depthMask(false);
            glEnable(GL_LINE_SMOOTH);
            glHint(GL_LINE_SMOOTH_HINT, GL_NICEST);
            glLineWidth(1.5f);

            final double dist = mc.player.getDistance(renderBlock.getX() + 0.5f, renderBlock.getY() + 0.5f, renderBlock.getZ() + 0.5f)
                    * 0.75f;


            int l_Color = 0x9000FFFF;

            RenderUtil.drawBoundingBox(bb, 1.0f, l_Color);
            RenderUtil.drawFilledBox(bb, l_Color);
            glDisable(GL_LINE_SMOOTH);
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.popMatrix();
        }
    });

    @EventHandler
    private Listener<EventPlayerMotionUpdate> OnPlayerUpdate = new Listener<>(p_Event ->
    {
        if (renderBlock == null) {
            return;
        }

        if (OffhandEChest.getValue() && mc.player.inventory.offHandInventory.get(0).isEmpty()) {
            int eChestSlot = PlayerUtil.GetItemSlot(Item.getIdFromItem(Item.getItemFromBlock(Blocks.ENDER_CHEST)));
            if (eChestSlot != -1)
                PlayerUtil.MoveToOffhand(eChestSlot);
        }

        if (AutoBreak.getValue() && breakTimer.passed(Delay.getValue())) {
            if (PicOnly.getValue() && !(mc.player.getHeldItemMainhand().getItem() instanceof ItemPickaxe)) {
                return;
            }
            mc.player.connection.sendPacket(new CPacketPlayerDigging(CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK, renderBlock, direction));
            breakTimer.reset();

            try {
                mc.playerController.blockHitDelay = 0;
            } catch (Exception ignored) {}
        }
    });

    @EventHandler
    private Listener<EventNetworkPostPacketEvent> PacketSendEvent = new Listener<>(p_Event ->
    {
        Packet packet = p_Event.GetPacket();
        if (packet instanceof CPacketPlayerDigging) {
            //CPacketPlayerDigging diggingPacket = (CPacketPlayerDigging) packet;
            if (((CPacketPlayerDigging) packet).getAction() == CPacketPlayerDigging.Action.START_DESTROY_BLOCK && packetCancel) {
                p_Event.cancel();
            }
        }
    });

    @EventHandler
    private Listener<EventPlayerDamageBlock> DamageBlockEvent = new Listener<>(p_Event ->
    {
        if (canBreak(p_Event.getPos())) {
            if (lastBlock == null || p_Event.getPos().getX() != lastBlock.getX() || p_Event.getPos().getY() != lastBlock.getY() || p_Event.getPos().getZ() != lastBlock.getZ()) {
                packetCancel = false;
                mc.player.swingArm(EnumHand.MAIN_HAND);
                mc.player.connection.sendPacket(new CPacketPlayerDigging(CPacketPlayerDigging.Action.START_DESTROY_BLOCK, p_Event.getPos(), p_Event.getDirection()));

                renderBlock = p_Event.getPos();
                lastBlock = p_Event.getPos();
                direction = p_Event.getDirection();

                p_Event.cancel();
            }
        }
    });

    private boolean canBreak(BlockPos pos) {
        final IBlockState blockState = mc.world.getBlockState(pos);
        final Block block = blockState.getBlock();

        return block.getBlockHardness(blockState, mc.world, pos) != -1;
    }
}