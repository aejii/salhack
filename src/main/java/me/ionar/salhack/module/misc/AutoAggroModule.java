package me.ionar.salhack.module.misc;

import me.ionar.salhack.events.MinecraftEvent;
import me.ionar.salhack.events.player.EventPlayerMotionUpdate;
import me.ionar.salhack.module.Module;
import me.ionar.salhack.module.Value;
import me.ionar.salhack.util.Timer;
import me.zero.alpine.fork.listener.EventHandler;
import me.zero.alpine.fork.listener.Listener;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityPigZombie;
import net.minecraft.network.play.client.CPacketUseEntity;
import net.minecraft.util.EnumHand;

public class AutoAggroModule extends Module {

    public final Value<Float> CheckTime = new Value<>("CheckTime", new String[] {""}, "How often to check for aggro pigmen", 5.0f, 0.0f, 300.0f, 1.0f);
    public final Value<Float> MaxAggroDistance = new Value<>("MaxAggroDistance", new String[] {""}, "How far to check for aggro pigmen", 32.0f, 0.0f, 128.0f, 1.0f);
    public final Value<Float> MaxAttackDistance = new Value<>("MaxAttackDistance", new String[] {""}, "Maximum distance the mobs can be to be attacked", 4.0f, 0.0f, 10.0f, 1.0f);
    public final Value<Integer> TicksBetweenAttacks = new Value<>("TicksBetweenAttacks", new String[] {""}, "Time to wait in ticks before trying to attack mobs", 40, 0, 600, 5);


    public AutoAggroModule() {
        super("AutoAggro", new String[]
                { "" }, "Automatically aggros pigmen", "NONE", 0x24DBD4, ModuleType.MISC);
    }

    private Timer timer = new Timer();
    private boolean attackingPigmen = false;
    private int ticks = 0;

    @Override
    public void onEnable() {
        super.onEnable();

        timer.reset();
        attackingPigmen = false;
        ticks = 0;
    }

    @EventHandler
    private Listener<EventPlayerMotionUpdate> OnPlayerUpdate = new Listener<>(p_Event ->
    {
        if (p_Event.getEra() != MinecraftEvent.Era.PRE || mc.player == null)
            return;

        ticks++;

        if (attackingPigmen) {
            if (ticks < TicksBetweenAttacks.getValue()) {
                return;
            }
            ticks = 0;
            if (haveAggroPigman()) {
                SendMessage("Now have aggro pigman, attacking stopping!");
                attackingPigmen = false;
                return;
            }
            // Do attack here
            EntityPigZombie toAttack = null;
            float closestDist = Float.MAX_VALUE;
            for (Entity entity : mc.world.loadedEntityList) {
                if (entity instanceof EntityPigZombie && ((EntityPigZombie) entity).isChild()) {
                    float distance = mc.player.getDistance(entity);
                    if (distance < closestDist) {
                        toAttack = (EntityPigZombie) entity;
                        closestDist = distance;
                    }
                }
            }

            if (toAttack != null && closestDist <= MaxAttackDistance.getValue()) {
                SendMessage(String.format("Attacking child. Distance: %d", closestDist));
                mc.player.connection.sendPacket(new CPacketUseEntity(toAttack));
                mc.player.swingArm(EnumHand.MAIN_HAND);
                mc.player.resetCooldown();
            }
        }
        else if (timer.passed(CheckTime.getValue() * 1000f) && !haveAggroPigman()) {
            attackingPigmen = true;
        }
    });

    private boolean haveAggroPigman() {
        for (Entity entity : mc.world.loadedEntityList) {
            if (mc.player.getDistance(entity) < MaxAggroDistance.getValue() && entity instanceof EntityPigZombie && ((EntityPigZombie) entity).isArmsRaised()) {
                return true;
            }
        }
        return false;
    }
}
