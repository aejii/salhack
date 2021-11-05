package me.ionar.salhack.module.world;

import me.ionar.salhack.events.MinecraftEvent.Era;
import me.ionar.salhack.events.player.EventPlayerMotionUpdate;
import me.ionar.salhack.events.render.EventRenderLayers;
import me.ionar.salhack.events.render.RenderEvent;
import me.ionar.salhack.module.Module;
import me.ionar.salhack.module.Value;
import me.ionar.salhack.util.BlockInteractionHelper;
import me.ionar.salhack.util.BlockInteractionHelper.PlaceResult;
import me.ionar.salhack.util.MathUtil;
import me.ionar.salhack.util.Pair;
import me.ionar.salhack.util.Timer;
import me.ionar.salhack.util.entity.PlayerUtil;
import me.ionar.salhack.util.render.RenderUtil;
import me.zero.alpine.fork.listener.EventHandler;
import me.zero.alpine.fork.listener.Listener;
import net.minecraft.block.Block;
import net.minecraft.block.BlockObsidian;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CPacketEntityAction;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Iterator;

import static org.lwjgl.opengl.GL11.*;

public class AutoBuilderModule extends Module
{
    public final Value<Modes> Mode = new Value<Modes>("Mode", new String[] {""}, "Mode", Modes.Highway);
    public final Value<BuildingModes> BuildingMode = new Value<BuildingModes>("BuildingMode", new String[] {""}, "Dynamic will update source block while walking, static keeps same position and resets on toggle", BuildingModes.Dynamic);
    public final Value<Integer> BlocksPerTick = new Value<Integer>("BlocksPerTick", new String[] {"BPT"}, "Blocks per tick", 4, 1, 10, 1);
    public final Value<Float> Delay = new Value<Float>("Delay", new String[] {"Delay"}, "Delay of the place", 0f, 0.0f, 1.0f, 0.1f);
    public final Value<Boolean> Visualize = new Value<Boolean>("Visualize", new String[] {"Render"}, "Visualizes where blocks are to be placed", true);
    public final Value<Boolean> ObsidianOnly = new Value<Boolean>("ObsidianOnly", new String[] {""}, "Will only build when player is holding obsidian", true);
    public final Value<Integer> XOffset = new Value<Integer>("XOffset", new String[] {""}, "Custom Nether Highway X Offset, leave at -69 for defaults", -69, -69, 29999999, 1000);
    public final Value<Integer> ZOffset = new Value<Integer>("ZOffset", new String[] {""}, "Custom Nether Highway Z Offset, leave at -69 for defaults", -69, -69, 29999999, 1000);
    public final Value<Boolean> PauseOnStuck = new Value<>("PauseOnStuck", new String[] {""}, "Pause this module if player hasn't placed any obsidian for StuckCheckTicks value", true);
    public final Value<Float> StuckCheckTime = new Value<>("StuckCheckTime", new String[] {""}, "If player hasn't placed obsidian for set seconds module will pause until player places obsidian", 5.0f, 0.0f, 60.0f, 1.0f);

    public enum Modes
    {
        CHBXHighway,
        CHBZHighway,
        CHBSEHighway,
        CHBNEHighway,
        NEOWHighway,
        Highway,
        Swastika,
        HighwayTunnel,
        Portal,
        Flat,
        Tower,
        Cover,
        Wall,
        HighwayWall,
        Stair,
    }
    
    public enum BuildingModes
    {
        Dynamic,
        Static,
    }

    public AutoBuilderModule()
    {
        super("AutoBuilder", new String[]
        { "AutoSwastika" }, "Builds cool things at your facing block", "NONE", 0x96DB24, ModuleType.WORLD);
    }
    
    private Vec3d Center = Vec3d.ZERO;
    private ICamera camera = new Frustum();
    private Timer timer = new Timer();
    private Timer NetherPortalTimer = new Timer();
    private Timer stuckCheckTimer = new Timer();
    private BlockPos SourceBlock = null;
    private int obsidianCount = 0;
    private boolean stuck = false;

    @Override
    public void onEnable()
    {
        super.onEnable();
        
        //if (mc.player == null)
        //{
        //    toggle();
        //    return;
        //}
        
        timer.reset();
        SourceBlock = null;
        BlockArray.clear();
        stuck = false;
        stuckCheckTimer.reset();
        obsidianCount = 0;
    }
    
    private float PitchHead = 0.0f;
    private boolean SentPacket = false;

    ArrayList<BlockPos> BlockArray = new ArrayList<BlockPos>();
    
    @Override
    public String getMetaData()
    {
        return Mode.getValue().toString() + " - " + BuildingMode.getValue().toString();
    }

    @EventHandler
    private Listener<EventRenderLayers> OnRender = new Listener<>(p_Event ->
    {
        if (p_Event.getEntityLivingBase() == mc.player)
            p_Event.SetHeadPitch(PitchHead == -420.0f ? mc.player.rotationPitch : PitchHead);
    });

    @EventHandler
    private Listener<EventPlayerMotionUpdate> OnPlayerUpdate = new Listener<>(p_Event ->
    {
        if (p_Event.getEra() != Era.PRE ||
                mc.player == null ||
                !timer.passed(Delay.getValue() * 1000f) ||
                !mc.world.getEntitiesWithinAABBExcludingEntity(mc.player, mc.player.getEntityBoundingBox()).isEmpty()) {
            return;
        }

        if (ObsidianOnly.getValue() && Item.getIdFromItem(mc.player.getHeldItemMainhand().getItem()) != Item.getIdFromItem(Item.getItemFromBlock(Blocks.OBSIDIAN))) {
            stuckCheckTimer.reset();
            return;
        }

        if (PauseOnStuck.getValue()) {
            int curObbyCount = getItemCountInventory(Item.getIdFromItem(Item.getItemFromBlock(Blocks.OBSIDIAN)));
            if (stuck) {
                if (curObbyCount != obsidianCount) {
                    stuck = false;
                    stuckCheckTimer.reset();
                    obsidianCount = curObbyCount;
                } else {
                    return;
                }
            } else if (stuckCheckTimer.passed(StuckCheckTime.getValue() * 1000f)) {
                if (curObbyCount == obsidianCount) {
                    SendMessage(String.format("We are stuck. Pausing AutoBuilder until obsidian count changes. Current count: %d", obsidianCount));
                    stuck = true;
                    return;
                } else {
                    stuckCheckTimer.reset();
                    obsidianCount = curObbyCount;
                }
            }
        }
        
        timer.reset();
        
        final Vec3d pos = MathUtil.interpolateEntity(mc.player, mc.getRenderPartialTicks());

        BlockPos orignPos = new BlockPos(pos.x, pos.y+0.5f, pos.z);

        int lastSlot;
        Pair<Integer, Block> l_Pair = findStackHotbar();
        
        int slot = -1;
        double l_Offset = pos.y - orignPos.getY();
        
        if (l_Pair != null)
        {
            slot = l_Pair.getFirst();
            
            if (l_Pair.getSecond() instanceof BlockSlab)
            {
                if (l_Offset == 0.5f)
                {
                    orignPos = new BlockPos(pos.x, pos.y+0.5f, pos.z);
                }
            }
        }
        
        if (BuildingMode.getValue() == BuildingModes.Dynamic)
            BlockArray.clear();
        
        if (BlockArray.isEmpty())
            FillBlockArrayAsNeeded(pos, orignPos, l_Pair);
        
        boolean l_NeedPlace = false;

        float[] rotations = null;
        
        if (slot != -1)
        {
            if ((mc.player.onGround))
            {
                lastSlot = mc.player.inventory.currentItem;
                mc.player.inventory.currentItem = slot;
                mc.playerController.updateController();
                
                int l_BlocksPerTick = BlocksPerTick.getValue();

                if (mc.player == null) {
                    return;
                }
                BlockPos playerPos = mc.player.getPosition();
                // Sort the block array so it tries to place closest blocks first
                BlockArray.sort((o1, o2) -> {
                    double dist1 = playerPos.getDistance(o1.getX(), o1.getY(), o1.getZ());
                    double dist2 = playerPos.getDistance(o2.getX(), o2.getY(), o2.getZ());
                    return Double.compare(dist1, dist2);
                });
                for (BlockPos l_Pos : BlockArray)
                {
                    /*ValidResult l_Result = BlockInteractionHelper.valid(l_Pos);
                    
                    if (l_Result == ValidResult.AlreadyBlockThere && !mc.world.getBlockState(l_Pos).getMaterial().isReplaceable())
                        continue;
                    
                    if (l_Result == ValidResult.NoNeighbors)
                        continue;*/

                    // Don't try to place if entities are in the way
                    if (!mc.world.checkNoEntityCollision(new AxisAlignedBB(l_Pos))) {
                        continue;
                    }

                    PlaceResult l_Place = BlockInteractionHelper.place (l_Pos, 5.0f, false, l_Offset == -0.5f);

                    if (l_Place != PlaceResult.Placed)
                        continue;
                    
                    l_NeedPlace = true;
                    rotations = BlockInteractionHelper.getLegitRotations(new Vec3d(l_Pos.getX(), l_Pos.getY(), l_Pos.getZ()));
                    if (--l_BlocksPerTick <= 0)
                        break;
                }

                if (!slotEqualsBlock(lastSlot, l_Pair.getSecond()))
                {
                    mc.player.inventory.currentItem = lastSlot;
                }
                mc.playerController.updateController();
            }
        }
        
        if (!l_NeedPlace && Mode.getValue() == Modes.Portal)
        {
            if (mc.world.getBlockState(BlockArray.get(0).up()).getBlock() == Blocks.PORTAL || !VerifyPortalFrame(BlockArray))
                return;
            
            if (mc.player.getHeldItemMainhand().getItem() != Items.FLINT_AND_STEEL)
            {
                for (int l_I = 0; l_I < 9; ++l_I)
                {
                    ItemStack l_Stack = mc.player.inventory.getStackInSlot(l_I);
                    if (l_Stack.isEmpty())
                        continue;
                    
                    if (l_Stack.getItem() == Items.FLINT_AND_STEEL)
                    {
                        mc.player.inventory.currentItem = l_I;
                        mc.playerController.updateController();
                        NetherPortalTimer.reset();
                        break;
                    }
                }
            }
            
            if (!NetherPortalTimer.passed(500))
            {
                if (SentPacket)
                {
                    mc.player.swingArm(EnumHand.MAIN_HAND);
                    mc.getConnection().sendPacket(new CPacketPlayerTryUseItemOnBlock(BlockArray.get(0), EnumFacing.UP, EnumHand.MAIN_HAND, 0f, 0f, 0f));
                }
                
                rotations = BlockInteractionHelper.getLegitRotations(new Vec3d(BlockArray.get(0).getX(), BlockArray.get(0).getY()+0.5f, BlockArray.get(0).getZ()));
                l_NeedPlace = true;
            }
            else
                return;
        }
        else if (l_NeedPlace && Mode.getValue() == Modes.Portal)
            NetherPortalTimer.reset();
        
        if (!l_NeedPlace || rotations == null)
        {
            PitchHead = -420.0f;
            SentPacket = false;
            return;
        }
        
        p_Event.cancel();
        
        /// @todo: clean this up

        boolean l_IsSprinting = mc.player.isSprinting();

        if (l_IsSprinting != mc.player.serverSprintState)
        {
            if (l_IsSprinting)
            {
                mc.player.connection.sendPacket(new CPacketEntityAction(mc.player, CPacketEntityAction.Action.START_SPRINTING));
            }
            else
            {
                mc.player.connection.sendPacket(new CPacketEntityAction(mc.player, CPacketEntityAction.Action.STOP_SPRINTING));
            }

            mc.player.serverSprintState = l_IsSprinting;
        }

        boolean l_IsSneaking = mc.player.isSneaking();

        if (l_IsSneaking != mc.player.serverSneakState)
        {
            if (l_IsSneaking)
            {
                mc.player.connection.sendPacket(new CPacketEntityAction(mc.player, CPacketEntityAction.Action.START_SNEAKING));
            }
            else
            {
                mc.player.connection.sendPacket(new CPacketEntityAction(mc.player, CPacketEntityAction.Action.STOP_SNEAKING));
            }

            mc.player.serverSneakState = l_IsSneaking;
        }

        if (PlayerUtil.isCurrentViewEntity())
        {
            float l_Pitch = rotations[1];
            float l_Yaw = rotations[0];
            
            mc.player.rotationYawHead = l_Yaw;
            PitchHead = l_Pitch;
            
            AxisAlignedBB axisalignedbb = mc.player.getEntityBoundingBox();
            double l_PosXDifference = mc.player.posX - mc.player.lastReportedPosX;
            double l_PosYDifference = axisalignedbb.minY - mc.player.lastReportedPosY;
            double l_PosZDifference = mc.player.posZ - mc.player.lastReportedPosZ;
            double l_YawDifference = (double)(l_Yaw - mc.player.lastReportedYaw);
            double l_RotationDifference = (double)(l_Pitch - mc.player.lastReportedPitch);
            ++mc.player.positionUpdateTicks;
            boolean l_MovedXYZ = l_PosXDifference * l_PosXDifference + l_PosYDifference * l_PosYDifference + l_PosZDifference * l_PosZDifference > 9.0E-4D || mc.player.positionUpdateTicks >= 20;
            boolean l_MovedRotation = l_YawDifference != 0.0D || l_RotationDifference != 0.0D;

            if (mc.player.isRiding()) {
                mc.player.connection.sendPacket(new CPacketPlayer.PositionRotation(mc.player.motionX, -999.0D, mc.player.motionZ, l_Yaw, l_Pitch, mc.player.onGround));
                l_MovedXYZ = false;
            } else if (l_MovedXYZ && l_MovedRotation) {
                mc.player.connection.sendPacket(new CPacketPlayer.PositionRotation(mc.player.posX, axisalignedbb.minY, mc.player.posZ, l_Yaw, l_Pitch, mc.player.onGround));
            } else if (l_MovedXYZ) {
                mc.player.connection.sendPacket(new CPacketPlayer.Position(mc.player.posX, axisalignedbb.minY, mc.player.posZ, mc.player.onGround));
            } else if (l_MovedRotation) {
                mc.player.connection.sendPacket(new CPacketPlayer.Rotation(l_Yaw, l_Pitch, mc.player.onGround));
            } else if (mc.player.prevOnGround != mc.player.onGround) {
                mc.player.connection.sendPacket(new CPacketPlayer(mc.player.onGround));
            }

            if (l_MovedXYZ)
            {
                mc.player.lastReportedPosX = mc.player.posX;
                mc.player.lastReportedPosY = axisalignedbb.minY;
                mc.player.lastReportedPosZ = mc.player.posZ;
                mc.player.positionUpdateTicks = 0;
            }

            if (l_MovedRotation)
            {
                mc.player.lastReportedYaw = l_Yaw;
                mc.player.lastReportedPitch = l_Pitch;
            }

            SentPacket = true;
            mc.player.prevOnGround = mc.player.onGround;
            mc.player.autoJumpEnabled = mc.player.mc.gameSettings.autoJump;
        }
    });
    
    
    @EventHandler
    private Listener<RenderEvent> OnRenderEvent = new Listener<>(p_Event ->
    {
        if (!Visualize.getValue())
            return;
        
        Iterator l_Itr = BlockArray.iterator();

        while (l_Itr.hasNext()) 
        {
            BlockPos l_Pos = (BlockPos) l_Itr.next();
            
            IBlockState l_State = mc.world.getBlockState(l_Pos);
            
            if (l_State != null && l_State.getBlock() != Blocks.AIR && l_State.getBlock() != Blocks.WATER)
                continue;
            
            final AxisAlignedBB bb = new AxisAlignedBB(l_Pos.getX() - mc.getRenderManager().viewerPosX,
                    l_Pos.getY() - mc.getRenderManager().viewerPosY, l_Pos.getZ() - mc.getRenderManager().viewerPosZ,
                    l_Pos.getX() + 1 - mc.getRenderManager().viewerPosX,
                    l_Pos.getY() + (1) - mc.getRenderManager().viewerPosY,
                    l_Pos.getZ() + 1 - mc.getRenderManager().viewerPosZ);
    
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
    
                final double dist = mc.player.getDistance(l_Pos.getX() + 0.5f, l_Pos.getY() + 0.5f, l_Pos.getZ() + 0.5f)
                        * 0.75f;
    
                // float alpha = MathUtil.clamp((float) (dist * 255.0f / 5.0f / 255.0f), 0.0f, 0.3f);

                //  public static void drawBoundingBox(AxisAlignedBB bb, float width, int color)
                
                
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
        }
    });
    
    private boolean slotEqualsBlock(int slot, Block type)
    {
        if (mc.player.inventory.getStackInSlot(slot).getItem() instanceof ItemBlock)
        {
            final ItemBlock block = (ItemBlock) mc.player.inventory.getStackInSlot(slot).getItem();
            return block.getBlock() == type;
        }

        return false;
    }

    private void FillBlockArrayAsNeeded(final Vec3d pos, final BlockPos orignPos, final Pair<Integer, Block> p_Pair)
    {
        BlockPos interpPos = null;
        
        switch (Mode.getValue())
        {
            case CHBXHighway: {
                int xOff = XOffset.getValue() == -69 ? 0 : XOffset.getValue();
                int ZOff = ZOffset.getValue() == -69 ? 2 : ZOffset.getValue() + 2;
                BlockPos newOriginPos = getClosestPoint(new Vec3d(xOff, 0, ZOff), new Vec3d(1, 0, 0), new Vec3d(orignPos.getX(), orignPos.getY(), orignPos.getZ()));
                for (int i = 0; i < 7; i++) {
                    BlockArray.add(newOriginPos.add(i, 0, 0));
                    BlockArray.add(newOriginPos.down().north(1).add(i, 0, 0));
                    BlockArray.add(newOriginPos.down().north(2).add(i, 0, 0));
                    BlockArray.add(newOriginPos.down().north(3).add(i, 0, 0));
                    BlockArray.add(newOriginPos.down().north(4).add(i, 0, 0));
                    BlockArray.add(newOriginPos.north(5).add(i, 0, 0));

                    BlockArray.add(newOriginPos.add(-i, 0, 0));
                    BlockArray.add(newOriginPos.down().north(1).add(-i, 0, 0));
                    BlockArray.add(newOriginPos.down().north(2).add(-i, 0, 0));
                    BlockArray.add(newOriginPos.down().north(3).add(-i, 0, 0));
                    BlockArray.add(newOriginPos.down().north(4).add(-i, 0, 0));
                    BlockArray.add(newOriginPos.north(5).add(-i, 0, 0));
                }
                break;
            }

            case CHBZHighway: {
                int xOff = XOffset.getValue() == -69 ? 2 : XOffset.getValue() + 2;
                int ZOff = ZOffset.getValue() == -69 ? 0 : ZOffset.getValue();
                BlockPos newOriginPos = getClosestPoint(new Vec3d(xOff, 0, ZOff), new Vec3d(0, 0, 1), new Vec3d(orignPos.getX(), orignPos.getY(), orignPos.getZ()));
                for (int i = 0; i < 7; i++) {
                    BlockArray.add(newOriginPos.add(0, 0, i));
                    BlockArray.add(newOriginPos.down().west(1).add(0, 0, i));
                    BlockArray.add(newOriginPos.down().west(2).add(0, 0, i));
                    BlockArray.add(newOriginPos.down().west(3).add(0, 0, i));
                    BlockArray.add(newOriginPos.down().west(4).add(0, 0, i));
                    BlockArray.add(newOriginPos.west(5).add(0, 0, i));

                    BlockArray.add(newOriginPos.add(0, 0, -i));
                    BlockArray.add(newOriginPos.down().west(1).add(0, 0, -i));
                    BlockArray.add(newOriginPos.down().west(2).add(0, 0, -i));
                    BlockArray.add(newOriginPos.down().west(3).add(0, 0, -i));
                    BlockArray.add(newOriginPos.down().west(4).add(0, 0, -i));
                    BlockArray.add(newOriginPos.west(5).add(0, 0, -i));
                }
                break;
            }

            case CHBSEHighway: {
                int xOff = XOffset.getValue() == -69 ? 0 : XOffset.getValue();
                int ZOff = ZOffset.getValue() == -69 ? -4 : ZOffset.getValue() - 4;
                BlockPos newOriginPos = getClosestPoint(new Vec3d(xOff, 0, ZOff), new Vec3d(1, 0, 1), new Vec3d(orignPos.getX(), orignPos.getY(), orignPos.getZ()));
                for (int i = 0; i < 7; i++) {
                    BlockArray.add(newOriginPos.add(i, 0, i));
                    if (!mc.world.getBlockState(newOriginPos.add(i, 0, i)).isFullBlock())
                        BlockArray.add(newOriginPos.down().add(i, 0, i)); // Under Rail
                    BlockArray.add(newOriginPos.down().west(1).add(i, 0, i));
                    BlockArray.add(newOriginPos.down().west(2).add(i, 0, i));
                    BlockArray.add(newOriginPos.down().west(3).add(i, 0, i));
                    BlockArray.add(newOriginPos.down().west(4).add(i, 0, i));
                    BlockArray.add(newOriginPos.down().west(5).add(i, 0, i));
                    BlockArray.add(newOriginPos.down().west(6).add(i, 0, i));
                    BlockArray.add(newOriginPos.down().west(7).add(i, 0, i));
                    if (!mc.world.getBlockState(newOriginPos.west(8).add(i, 0, i)).isFullBlock())
                        BlockArray.add(newOriginPos.down().west(8).add(i, 0, i)); // Under Rail
                    BlockArray.add(newOriginPos.west(8).add(i, 0, i));

                    if (i == 0)
                        continue;

                    BlockArray.add(newOriginPos.add(-i, 0, -i));
                    if (!mc.world.getBlockState(newOriginPos.add(-i, 0, -i)).isFullBlock())
                        BlockArray.add(newOriginPos.down().add(-i, 0, -i)); // Under Rail
                    BlockArray.add(newOriginPos.down().west(1).add(-i, 0, -i));
                    BlockArray.add(newOriginPos.down().west(2).add(-i, 0, -i));
                    BlockArray.add(newOriginPos.down().west(3).add(-i, 0, -i));
                    BlockArray.add(newOriginPos.down().west(4).add(-i, 0, -i));
                    BlockArray.add(newOriginPos.down().west(5).add(-i, 0, -i));
                    BlockArray.add(newOriginPos.down().west(6).add(-i, 0, -i));
                    BlockArray.add(newOriginPos.down().west(7).add(-i, 0, -i));
                    if (!mc.world.getBlockState(newOriginPos.west(8).add(-i, 0, -i)).isFullBlock())
                        BlockArray.add(newOriginPos.down().west(8).add(-i, 0, -i)); // Under Rail
                    BlockArray.add(newOriginPos.west(8).add(-i, 0, -i));
                }
                break;
            }

            case CHBNEHighway: {
                int xOff = XOffset.getValue() == -69 ? 3 : XOffset.getValue() + 3;
                int ZOff = ZOffset.getValue() == -69 ? 0 : ZOffset.getValue();
                BlockPos newOriginPos = getClosestPoint(new Vec3d(xOff, 0, ZOff), new Vec3d(1, 0, -1), new Vec3d(orignPos.getX(), orignPos.getY(), orignPos.getZ()));
                for (int i = 0; i < 7; i++) {
                    BlockArray.add(newOriginPos.add(i, 0, -i));
                    if (!mc.world.getBlockState(newOriginPos.add(i, 0, -i)).isFullBlock())
                        BlockArray.add(newOriginPos.down().add(i, 0, -i)); // Under Rail
                    BlockArray.add(newOriginPos.down().north(1).add(i, 0, -i));
                    BlockArray.add(newOriginPos.down().north(2).add(i, 0, -i));
                    BlockArray.add(newOriginPos.down().north(3).add(i, 0, -i));
                    BlockArray.add(newOriginPos.down().north(4).add(i, 0, -i));
                    BlockArray.add(newOriginPos.down().north(5).add(i, 0, -i));
                    BlockArray.add(newOriginPos.down().north(6).add(i, 0, -i));
                    BlockArray.add(newOriginPos.down().north(7).add(i, 0, -i));
                    if (!mc.world.getBlockState(newOriginPos.north(8).add(i, 0, -i)).isFullBlock())
                        BlockArray.add(newOriginPos.down().north(8).add(i, 0, -i)); // Under Rail
                    BlockArray.add(newOriginPos.north(8).add(i, 0, -i));

                    if (i == 0)
                        continue;

                    BlockArray.add(newOriginPos.add(-i, 0, i));
                    if (!mc.world.getBlockState(newOriginPos.add(-i, 0, i)).isFullBlock())
                        BlockArray.add(newOriginPos.down().add(-i, 0, i)); // Under Rail
                    BlockArray.add(newOriginPos.down().north(1).add(-i, 0, i));
                    BlockArray.add(newOriginPos.down().north(2).add(-i, 0, i));
                    BlockArray.add(newOriginPos.down().north(3).add(-i, 0, i));
                    BlockArray.add(newOriginPos.down().north(4).add(-i, 0, i));
                    BlockArray.add(newOriginPos.down().north(5).add(-i, 0, i));
                    BlockArray.add(newOriginPos.down().north(6).add(-i, 0, i));
                    BlockArray.add(newOriginPos.down().north(7).add(-i, 0, i));
                    if (!mc.world.getBlockState(newOriginPos.north(8).add(-i, 0, i)).isFullBlock())
                        BlockArray.add(newOriginPos.down().north(8).add(-i, 0, i)); // Under Rail
                    BlockArray.add(newOriginPos.north(8).add(-i, 0, i));
                }
                break;
            }

            case NEOWHighway:
                BlockPos newOriginPos = getClosestPoint(new Vec3d(-1, 0, 0), new Vec3d(1, 0, -1), new Vec3d(orignPos.getX(), orignPos.getY(), orignPos.getZ()));

                BlockArray.add(newOriginPos);
                BlockArray.add(newOriginPos.north());
                BlockArray.add(newOriginPos.north().west());
                BlockArray.add(newOriginPos.north().east());
                BlockArray.add(newOriginPos.west());
                BlockArray.add(newOriginPos.east());
                BlockArray.add(newOriginPos.south());
                BlockArray.add(newOriginPos.south().west());
                BlockArray.add(newOriginPos.south().east());

                // Front 1
                BlockArray.add(newOriginPos.north().north());
                BlockArray.add(newOriginPos.north().north().east());
                BlockArray.add(newOriginPos.north().north().east().east());
                BlockArray.add(newOriginPos.north().north().east().east().south());
                BlockArray.add(newOriginPos.north().north().east().east().south().south());

                // Front 2
                BlockArray.add(newOriginPos.north().north().east().north());
                BlockArray.add(newOriginPos.north().north().east().north().east());
                BlockArray.add(newOriginPos.north().north().east().north().east().east());
                BlockArray.add(newOriginPos.north().north().east().north().east().east().south());
                BlockArray.add(newOriginPos.north().north().east().north().east().east().south().south());

                // Front 3
                BlockArray.add(newOriginPos.north().north().east().north().east().north());
                BlockArray.add(newOriginPos.north().north().east().north().east().north().east());
                BlockArray.add(newOriginPos.north().north().east().north().east().north().east().east());
                BlockArray.add(newOriginPos.north().north().east().north().east().north().east().east().south());
                BlockArray.add(newOriginPos.north().north().east().north().east().north().east().east().south().south());

                // Front 4
                BlockArray.add(newOriginPos.north().north().east().north().east().north().east().north());
                BlockArray.add(newOriginPos.north().north().east().north().east().north().east().north().east());
                BlockArray.add(newOriginPos.north().north().east().north().east().north().east().north().east().east());
                BlockArray.add(newOriginPos.north().north().east().north().east().north().east().north().east().east().south());
                BlockArray.add(newOriginPos.north().north().east().north().east().north().east().north().east().east().south().south());

                // Front 5
                BlockArray.add(newOriginPos.north().north().east().north().east().north().east().north().east().north());
                BlockArray.add(newOriginPos.north().north().east().north().east().north().east().north().east().north().east());
                BlockArray.add(newOriginPos.north().north().east().north().east().north().east().north().east().north().east().east());
                BlockArray.add(newOriginPos.north().north().east().north().east().north().east().north().east().north().east().east().south());
                BlockArray.add(newOriginPos.north().north().east().north().east().north().east().north().east().north().east().east().south().south());
                // Get 2nd one and add north to it
                // then 2 east and 2 south

                // Back 1
                BlockArray.add(newOriginPos.west().west());
                BlockArray.add(newOriginPos.west().west().south());
                BlockArray.add(newOriginPos.west().west().south().south());
                BlockArray.add(newOriginPos.west().west().south().south().east());
                BlockArray.add(newOriginPos.west().west().south().south().east().east());

                // Back 2
                BlockArray.add(newOriginPos.west().west().south().west());
                BlockArray.add(newOriginPos.west().west().south().west().south());
                BlockArray.add(newOriginPos.west().west().south().west().south().south());
                BlockArray.add(newOriginPos.west().west().south().west().south().south().east());
                BlockArray.add(newOriginPos.west().west().south().west().south().south().east().east());

                // Back 3
                BlockArray.add(newOriginPos.west().west().south().west().south().west());
                BlockArray.add(newOriginPos.west().west().south().west().south().west().south());
                BlockArray.add(newOriginPos.west().west().south().west().south().west().south().south());
                BlockArray.add(newOriginPos.west().west().south().west().south().west().south().south().east());
                BlockArray.add(newOriginPos.west().west().south().west().south().west().south().south().east().east());

                // Back 4
                BlockArray.add(newOriginPos.west().west().south().west().south().west().south().west());
                BlockArray.add(newOriginPos.west().west().south().west().south().west().south().west().south());
                BlockArray.add(newOriginPos.west().west().south().west().south().west().south().west().south().south());
                BlockArray.add(newOriginPos.west().west().south().west().south().west().south().west().south().south().east());
                BlockArray.add(newOriginPos.west().west().south().west().south().west().south().west().south().south().east().east());

                // Back 5
                BlockArray.add(newOriginPos.west().west().south().west().south().west().south().west().south().west());
                BlockArray.add(newOriginPos.west().west().south().west().south().west().south().west().south().west().south());
                BlockArray.add(newOriginPos.west().west().south().west().south().west().south().west().south().west().south().south());
                BlockArray.add(newOriginPos.west().west().south().west().south().west().south().west().south().west().south().south().east());
                BlockArray.add(newOriginPos.west().west().south().west().south().west().south().west().south().west().south().south().east().east());
                // Grab 2nd one and add west to it
                // Then 2 souths on that
                // and 2 east on that
                break;
            case Highway:
                switch (PlayerUtil.GetFacing())
                {
                    case East:
                        BlockArray.add(orignPos.down());
                        BlockArray.add(orignPos.down().east());
                        BlockArray.add(orignPos.down().east().north());
                        BlockArray.add(orignPos.down().east().south());
                        BlockArray.add(orignPos.down().east().north().north());
                        BlockArray.add(orignPos.down().east().south().south());
                        BlockArray.add(orignPos.down().east().north().north().north());
                        BlockArray.add(orignPos.down().east().south().south().south());
                        BlockArray.add(orignPos.down().east().north().north().north().up());
                        BlockArray.add(orignPos.down().east().south().south().south().up());
                        break;
                    case North:
                        BlockArray.add(orignPos.down());
                        BlockArray.add(orignPos.down().north());
                        BlockArray.add(orignPos.down().north().east());
                        BlockArray.add(orignPos.down().north().west());
                        BlockArray.add(orignPos.down().north().east().east());
                        BlockArray.add(orignPos.down().north().west().west());
                        BlockArray.add(orignPos.down().north().east().east().east());
                        BlockArray.add(orignPos.down().north().west().west().west());
                        BlockArray.add(orignPos.down().north().east().east().east().up());
                        BlockArray.add(orignPos.down().north().west().west().west().up());
                        break;
                    case South:
                        BlockArray.add(orignPos.down());
                        BlockArray.add(orignPos.down().south());
                        BlockArray.add(orignPos.down().south().east());
                        BlockArray.add(orignPos.down().south().west());
                        BlockArray.add(orignPos.down().south().east().east());
                        BlockArray.add(orignPos.down().south().west().west());
                        BlockArray.add(orignPos.down().south().east().east().east());
                        BlockArray.add(orignPos.down().south().west().west().west());
                        BlockArray.add(orignPos.down().south().east().east().east().up());
                        BlockArray.add(orignPos.down().south().west().west().west().up());
                        break;
                    case West:
                        BlockArray.add(orignPos.down());
                        BlockArray.add(orignPos.down().west());
                        BlockArray.add(orignPos.down().west().north());
                        BlockArray.add(orignPos.down().west().south());
                        BlockArray.add(orignPos.down().west().north().north());
                        BlockArray.add(orignPos.down().west().south().south());
                        BlockArray.add(orignPos.down().west().north().north().north());
                        BlockArray.add(orignPos.down().west().south().south().south());
                        BlockArray.add(orignPos.down().west().north().north().north().up());
                        BlockArray.add(orignPos.down().west().south().south().south().up());
                        break;
                    default:
                        break;
                }
                break;
            case HighwayTunnel:
                BlockArray.add(orignPos.down());
                BlockArray.add(orignPos.down().north());
                BlockArray.add(orignPos.down().north().east());
                BlockArray.add(orignPos.down().north().west());
                BlockArray.add(orignPos.down().north().east().east());
                BlockArray.add(orignPos.down().north().west().west());
                BlockArray.add(orignPos.down().north().east().east().east());
                BlockArray.add(orignPos.down().north().west().west().west());
                BlockArray.add(orignPos.down().north().east().east().east().up());
                BlockArray.add(orignPos.down().north().west().west().west().up());
                BlockArray.add(orignPos.down().north().east().east().east().up().up());
                BlockArray.add(orignPos.down().north().west().west().west().up().up());
                BlockArray.add(orignPos.down().north().east().east().east().up().up().up());
                BlockArray.add(orignPos.down().north().west().west().west().up().up().up());
                BlockArray.add(orignPos.down().north().east().east().east().up().up().up().up());
                BlockArray.add(orignPos.down().north().west().west().west().up().up().up().up());
                BlockArray.add(orignPos.down().north().east().east().east().up().up().up().up().west());
                BlockArray.add(orignPos.down().north().west().west().west().up().up().up().up().east());
                BlockArray.add(orignPos.down().north().east().east().east().up().up().up().up().west().west());
                BlockArray.add(orignPos.down().north().west().west().west().up().up().up().up().east().east());
                BlockArray.add(orignPos.down().north().east().east().east().up().up().up().up().west().west().west());
                BlockArray.add(orignPos.down().north().west().west().west().up().up().up().up().east().east().east());
                break;
            case Swastika:
                switch (PlayerUtil.GetFacing())
                {
                    case East:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).east().east();
                        BlockArray.add(interpPos);
                        BlockArray.add(interpPos.north());
                        BlockArray.add(interpPos.north().north());
                        BlockArray.add(interpPos.up());
                        BlockArray.add(interpPos.up().up());
                        BlockArray.add(interpPos.up().up().north());
                        BlockArray.add(interpPos.up().up().north().north());
                        BlockArray.add(interpPos.up().up().north().north().up());
                        BlockArray.add(interpPos.up().up().north().north().up().up());
                        BlockArray.add(interpPos.up().up().south());
                        BlockArray.add(interpPos.up().up().south().south());
                        BlockArray.add(interpPos.up().up().south().south().down());
                        BlockArray.add(interpPos.up().up().south().south().down().down());
                        BlockArray.add(interpPos.up().up().up());
                        BlockArray.add(interpPos.up().up().up().up());
                        BlockArray.add(interpPos.up().up().up().up().south());
                        BlockArray.add(interpPos.up().up().up().up().south().south());
                        break;
                    case North:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).north().north();
                        BlockArray.add(interpPos);
                        BlockArray.add(interpPos.west());
                        BlockArray.add(interpPos.west().west());
                        BlockArray.add(interpPos.up());
                        BlockArray.add(interpPos.up().up());
                        BlockArray.add(interpPos.up().up().west());
                        BlockArray.add(interpPos.up().up().west().west());
                        BlockArray.add(interpPos.up().up().west().west().up());
                        BlockArray.add(interpPos.up().up().west().west().up().up());
                        BlockArray.add(interpPos.up().up().east());
                        BlockArray.add(interpPos.up().up().east().east());
                        BlockArray.add(interpPos.up().up().east().east().down());
                        BlockArray.add(interpPos.up().up().east().east().down().down());
                        BlockArray.add(interpPos.up().up().up());
                        BlockArray.add(interpPos.up().up().up().up());
                        BlockArray.add(interpPos.up().up().up().up().east());
                        BlockArray.add(interpPos.up().up().up().up().east().east());
                        break;
                    case South:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).south().south();
                        BlockArray.add(interpPos);
                        BlockArray.add(interpPos.east());
                        BlockArray.add(interpPos.east().east());
                        BlockArray.add(interpPos.up());
                        BlockArray.add(interpPos.up().up());
                        BlockArray.add(interpPos.up().up().east());
                        BlockArray.add(interpPos.up().up().east().east());
                        BlockArray.add(interpPos.up().up().east().east().up());
                        BlockArray.add(interpPos.up().up().east().east().up().up());
                        BlockArray.add(interpPos.up().up().west());
                        BlockArray.add(interpPos.up().up().west().west());
                        BlockArray.add(interpPos.up().up().west().west().down());
                        BlockArray.add(interpPos.up().up().west().west().down().down());
                        BlockArray.add(interpPos.up().up().up());
                        BlockArray.add(interpPos.up().up().up().up());
                        BlockArray.add(interpPos.up().up().up().up().west());
                        BlockArray.add(interpPos.up().up().up().up().west().west());
                        break;
                    case West:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).west().west();
                        BlockArray.add(interpPos);
                        BlockArray.add(interpPos.south());
                        BlockArray.add(interpPos.south().south());
                        BlockArray.add(interpPos.up());
                        BlockArray.add(interpPos.up().up());
                        BlockArray.add(interpPos.up().up().south());
                        BlockArray.add(interpPos.up().up().south().south());
                        BlockArray.add(interpPos.up().up().south().south().up());
                        BlockArray.add(interpPos.up().up().south().south().up().up());
                        BlockArray.add(interpPos.up().up().north());
                        BlockArray.add(interpPos.up().up().north().north());
                        BlockArray.add(interpPos.up().up().north().north().down());
                        BlockArray.add(interpPos.up().up().north().north().down().down());
                        BlockArray.add(interpPos.up().up().up());
                        BlockArray.add(interpPos.up().up().up().up());
                        BlockArray.add(interpPos.up().up().up().up().north());
                        BlockArray.add(interpPos.up().up().up().up().north().north());
                        break;
                    default:
                        break;
                }
                break;
            case Portal:

                switch (PlayerUtil.GetFacing())
                {
                    case East:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).east().east();
                        BlockArray.add(interpPos.south());
                        BlockArray.add(interpPos.south().south());
                        BlockArray.add(interpPos);
                        BlockArray.add(interpPos.south().south().up());
                        BlockArray.add(interpPos.south().south().up().up());
                        BlockArray.add(interpPos.south().south().up().up().up());
                        BlockArray.add(interpPos.south().south().up().up().up().up());
                        BlockArray.add(interpPos.south().south().up().up().up().up().north());
                        BlockArray.add(interpPos.south().south().up().up().up().up().north().north());
                        BlockArray.add(interpPos.south().south().up().up().up().up().north().north().north());
                        BlockArray.add(interpPos.south().south().up().up().up().up().north().north().north().down());
                        BlockArray.add(interpPos.south().south().up().up().up().up().north().north().north().down().down());
                        BlockArray.add(interpPos.south().south().up().up().up().up().north().north().north().down().down().down());
                        BlockArray.add(interpPos.south().south().up().up().up().up().north().north().north().down().down().down().down());
                        break;
                    case North:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).north().north();
                        BlockArray.add(interpPos.east());
                        BlockArray.add(interpPos.east().east());
                        BlockArray.add(interpPos);
                        BlockArray.add(interpPos.east().east().up());
                        BlockArray.add(interpPos.east().east().up().up());
                        BlockArray.add(interpPos.east().east().up().up().up());
                        BlockArray.add(interpPos.east().east().up().up().up().up());
                        BlockArray.add(interpPos.east().east().up().up().up().up().west());
                        BlockArray.add(interpPos.east().east().up().up().up().up().west().west());
                        BlockArray.add(interpPos.east().east().up().up().up().up().west().west().west());
                        BlockArray.add(interpPos.east().east().up().up().up().up().west().west().west().down());
                        BlockArray.add(interpPos.east().east().up().up().up().up().west().west().west().down().down());
                        BlockArray.add(interpPos.east().east().up().up().up().up().west().west().west().down().down().down());
                        BlockArray.add(interpPos.east().east().up().up().up().up().west().west().west().down().down().down().down());
                        break;
                    case South:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).south().south();
                        BlockArray.add(interpPos.east());
                        BlockArray.add(interpPos.east().east());
                        BlockArray.add(interpPos);
                        BlockArray.add(interpPos.east().east().up());
                        BlockArray.add(interpPos.east().east().up().up());
                        BlockArray.add(interpPos.east().east().up().up().up());
                        BlockArray.add(interpPos.east().east().up().up().up().up());
                        BlockArray.add(interpPos.east().east().up().up().up().up().west());
                        BlockArray.add(interpPos.east().east().up().up().up().up().west().west());
                        BlockArray.add(interpPos.east().east().up().up().up().up().west().west().west());
                        BlockArray.add(interpPos.east().east().up().up().up().up().west().west().west().down());
                        BlockArray.add(interpPos.east().east().up().up().up().up().west().west().west().down().down());
                        BlockArray.add(interpPos.east().east().up().up().up().up().west().west().west().down().down().down());
                        BlockArray.add(interpPos.east().east().up().up().up().up().west().west().west().down().down().down().down());
                        break;
                    case West:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).west().west();
                        BlockArray.add(interpPos.south());
                        BlockArray.add(interpPos.south().south());
                        BlockArray.add(interpPos);
                        BlockArray.add(interpPos.south().south().up());
                        BlockArray.add(interpPos.south().south().up().up());
                        BlockArray.add(interpPos.south().south().up().up().up());
                        BlockArray.add(interpPos.south().south().up().up().up().up());
                        BlockArray.add(interpPos.south().south().up().up().up().up().north());
                        BlockArray.add(interpPos.south().south().up().up().up().up().north().north());
                        BlockArray.add(interpPos.south().south().up().up().up().up().north().north().north());
                        BlockArray.add(interpPos.south().south().up().up().up().up().north().north().north().down());
                        BlockArray.add(interpPos.south().south().up().up().up().up().north().north().north().down().down());
                        BlockArray.add(interpPos.south().south().up().up().up().up().north().north().north().down().down().down());
                        BlockArray.add(interpPos.south().south().up().up().up().up().north().north().north().down().down().down().down());
                        break;
                    default:
                        break;
                }
                break;
            case Flat:
                
                for (int l_X = -3; l_X <= 3; ++l_X)
                    for (int l_Y = -3; l_Y <= 3; ++l_Y)
                    {
                        BlockArray.add(orignPos.down().add(l_X, 0, l_Y));
                    }
                
                break;
            case Cover:
                if (p_Pair == null)
                    return;
                
                for (int l_X = -3; l_X < 3; ++l_X)
                    for (int l_Y = -3; l_Y < 3; ++l_Y)
                    {
                        int l_Tries = 5;
                        BlockPos l_Pos = orignPos.down().add(l_X, 0, l_Y);
                        
                        if (mc.world.getBlockState(l_Pos).getBlock() == p_Pair.getSecond() || mc.world.getBlockState(l_Pos.down()).getBlock() == Blocks.AIR || mc.world.getBlockState(l_Pos.down()).getBlock() == p_Pair.getSecond())
                            continue;
                        
                        while (mc.world.getBlockState(l_Pos).getBlock() != Blocks.AIR && mc.world.getBlockState(l_Pos).getBlock() != Blocks.FIRE)
                        {
                            if (mc.world.getBlockState(l_Pos).getBlock() == p_Pair.getSecond())
                                break;
                            
                            l_Pos = l_Pos.up();
                            
                            if (--l_Tries <= 0)
                                break;
                        }
                        
                        BlockArray.add(l_Pos);
                    }
                break;
            case Tower:
                BlockArray.add(orignPos.up());
                BlockArray.add(orignPos);
                BlockArray.add(orignPos.down());
                break;
            case Wall:

                switch (PlayerUtil.GetFacing())
                {
                    case East:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).east().east();
                        
                        for (int l_X = -3; l_X <= 3; ++l_X)
                        {
                            for (int l_Y = -3; l_Y <= 3; ++l_Y)
                            {
                                BlockArray.add(interpPos.add(0, l_Y, l_X));
                            }
                        }
                        break;
                    case North:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).north().north();
                        
                        for (int l_X = -3; l_X <= 3; ++l_X)
                        {
                            for (int l_Y = -3; l_Y <= 3; ++l_Y)
                            {
                                BlockArray.add(interpPos.add(l_X, l_Y, 0));
                            }
                        }
                        break;
                    case South:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).south().south();
                        
                        for (int l_X = -3; l_X <= 3; ++l_X)
                        {
                            for (int l_Y = -3; l_Y <= 3; ++l_Y)
                            {
                                BlockArray.add(interpPos.add(l_X, l_Y, 0));
                            }
                        }
                        break;
                    case West:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).west().west();
                        
                        for (int l_X = -3; l_X <= 3; ++l_X)
                        {
                            for (int l_Y = -3; l_Y <= 3; ++l_Y)
                            {
                                BlockArray.add(interpPos.add(0, l_Y, l_X));
                            }
                        }
                        break;
                    default:
                        break;
                }
                break;
            case HighwayWall:
                switch (PlayerUtil.GetFacing())
                {
                    case East:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).east().east();
                        
                        for (int l_X = -2; l_X <= 3; ++l_X)
                        {
                            for (int l_Y = 0; l_Y < 3; ++l_Y)
                            {
                                BlockArray.add(interpPos.add(0, l_Y, l_X));
                            }
                        }
                        break;
                    case North:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).north().north();
                        
                        for (int l_X = -2; l_X <= 3; ++l_X)
                        {
                            for (int l_Y = 0; l_Y < 3; ++l_Y)
                            {
                                BlockArray.add(interpPos.add(l_X, l_Y, 0));
                            }
                        }
                        break;
                    case South:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).south().south();
                        
                        for (int l_X = -2; l_X <= 3; ++l_X)
                        {
                            for (int l_Y = 0; l_Y < 3; ++l_Y)
                            {
                                BlockArray.add(interpPos.add(l_X, l_Y, 0));
                            }
                        }
                        break;
                    case West:
                        interpPos = new BlockPos(pos.x, pos.y, pos.z).west().west();
                        
                        for (int l_X = -2; l_X <= 3; ++l_X)
                        {
                            for (int l_Y = 0; l_Y < 3; ++l_Y)
                            {
                                BlockArray.add(interpPos.add(0, l_Y, l_X));
                            }
                        }
                        break;
                    default:
                        break;
                }
                break;
            case Stair:
                
                interpPos = orignPos.down();
                
                switch (PlayerUtil.GetFacing())
                {
                    case East:
                        BlockArray.add(interpPos.east());
                        BlockArray.add(interpPos.east().up());
                        break;
                    case North:
                        BlockArray.add(interpPos.north());
                        BlockArray.add(interpPos.north().up());
                        break;
                    case South:
                        BlockArray.add(interpPos.south());
                        BlockArray.add(interpPos.south().up());
                        break;
                    case West:
                        BlockArray.add(interpPos.west());
                        BlockArray.add(interpPos.west().up());
                        break;
                    default:
                        break;
                }
                break;
            default:
                break;
            
        }
    }

    private Pair<Integer, Block> findStackHotbar()
    {
        if ((ObsidianOnly.getValue() && Item.getIdFromItem(mc.player.getHeldItemMainhand().getItem()) == Item.getIdFromItem(Item.getItemFromBlock(Blocks.OBSIDIAN))) ||
                (!ObsidianOnly.getValue() && mc.player.getHeldItemMainhand().getItem() instanceof ItemBlock)) {
            return new Pair<Integer, Block>(mc.player.inventory.currentItem, ((ItemBlock)mc.player.getHeldItemMainhand().getItem()).getBlock());
        }
        
        for (int i = 0; i < 9; i++)
        {
            final ItemStack stack = Minecraft.getMinecraft().player.inventory.getStackInSlot(i);
            if ((ObsidianOnly.getValue() && Item.getIdFromItem(stack.getItem()) == Item.getIdFromItem(Item.getItemFromBlock(Blocks.OBSIDIAN))) ||
                    (!ObsidianOnly.getValue() && stack.getItem() instanceof ItemBlock)) {
                final ItemBlock block = (ItemBlock) stack.getItem();
                
                return new Pair<Integer, Block>(i, block.getBlock());
            }
        }
        return null;
    }

    public Vec3d GetCenter(double posX, double posY, double posZ)
    {
        double x = Math.floor(posX) + 0.5D;
        double y = Math.floor(posY);
        double z = Math.floor(posZ) + 0.5D ;
        
        return new Vec3d(x, y, z);
    }

    private BlockPos getClosestPoint(Vec3d origin, Vec3d direction, Vec3d point) {
        int yLevel = 99;
        if (this.Mode.getValue() == Modes.CHBXHighway || this.Mode.getValue() == Modes.CHBZHighway || this.Mode.getValue() == Modes.CHBSEHighway || this.Mode.getValue() == Modes.CHBNEHighway) {
            yLevel = 120;
        }

        direction = direction.normalize();
        Vec3d lhs = point.subtract(origin);
        double dotP = lhs.dotProduct(direction);
        Vec3d closest = origin.add(direction.scale(dotP));
        return new BlockPos(Math.round(closest.x), yLevel, Math.round(closest.z));

        /*
        Vec3d directBackup = new Vec3d(direction.x, direction.y, direction.z);

        boolean diag = direction.x != 0 && direction.z != 0;
        direction.normalize();

        Vec3d lhs = point.subtract(origin);
        double dotP = lhs.dotProduct(direction);
        Vec3d scaled = direction.scale(dotP);
        Vec3d closestPos =  origin.add(scaled);

        int iClosestPosX = (int) closestPos.x;
        int iClosestPosZ = (int) closestPos.z;

        if (directBackup.x == 0) {
            iClosestPosX = (int) origin.x;
        }
        else if (directBackup.z == 0) {
            iClosestPosZ = (int) origin.z;
        }

        if (diag) {
            int absX = Math.abs(iClosestPosX);
            int absY = Math.abs(iClosestPosZ);
            if ((absX + Math.abs(origin.x)) == (absY + Math.abs(origin.z))) {
                return new BlockPos(iClosestPosX, yLevel, iClosestPosZ);
            } else {
                return new BlockPos((absY + origin.x) * directBackup.x, yLevel, (absY + origin.z) * directBackup.z);
            }
        }
        return new BlockPos(iClosestPosX, yLevel, iClosestPosZ);
         */
        /*
        // https://stackoverflow.com/a/51906100
        direction.normalise();
        Vector2f lhs = Vector2f.sub(point, origin, null);

        float dotP = Vector2f.dot(lhs, direction);
        Vector2f closestPos = Vector2f.add(origin, (Vector2f) direction.scale(dotP), null);

        int iClosestPosX = (int) closestPos.x;
        int iClosestPosY = (int) closestPos.y;

        int absX = Math.abs(iClosestPosX);
        int absY = Math.abs(iClosestPosY);



        if ((absX + Math.abs(origin.x)) == (absY + Math.abs(origin.y))) {
            return new BlockPos(iClosestPosX, yLevel, iClosestPosY);
        } else {
            return new BlockPos(absY + origin.x, yLevel, -absY);
        }
         */
    }

    /// Verifies the array is all obsidian
    private boolean VerifyPortalFrame(ArrayList<BlockPos> p_Blocks)
    {
        for (BlockPos l_Pos : p_Blocks)
        {
            IBlockState l_State = mc.world.getBlockState(l_Pos);
            
            if (l_State == null || !(l_State.getBlock() instanceof BlockObsidian))
                return false;
        }
        
        return true;
    }

    private int getItemCountInventory(int itemId) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.inventory.mainInventory.get(i);
            if (Item.getIdFromItem(stack.getItem()) == itemId) {
                if (itemId == 0) {
                    // We're counting air slots
                    count++;
                } else {
                    count += stack.getCount();
                }
            }
        }

        return count;
    }
}
