package keystrokesmod.module.impl.player;

import keystrokesmod.event.PreTickEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.ReceivePacketEvent;
import keystrokesmod.mixins.impl.network.S14PacketEntityAccessor;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.script.classes.Vec3;
import keystrokesmod.utility.PacketUtils;
import keystrokesmod.utility.Utils;
import keystrokesmod.utility.backtrack.TimedPacket;
import keystrokesmod.utility.render.Animation;
import keystrokesmod.utility.render.Easing;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.*;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Backtrack extends Module {

    public static final Color color = new Color(72, 125, 227);

    private final SliderSetting minLatency = new SliderSetting("Min latency", 50, 10, 1000, 10);
    private final SliderSetting maxLatency = new SliderSetting("Max latency", 100, 10, 1000, 10);
    private final SliderSetting minDistance = new SliderSetting("Min distance", 0.0, 0.0, 3.0, 0.1);
    private final SliderSetting maxDistance = new SliderSetting("Max distance", 6.0, 0.0, 10.0, 0.1);
    private final SliderSetting stopOnTargetHurtTime = new SliderSetting("Stop on target HurtTime", -1, -1, 10, 1);
    private final SliderSetting stopOnSelfHurtTime = new SliderSetting("Stop on self HurtTime", -1, -1, 10, 1);
    private final ButtonSetting drawRealPosition = new ButtonSetting("Draw real position", true);

    private final Queue<TimedPacket> packetQueue = new ConcurrentLinkedQueue<>();
    private final List<Packet<?>> skipPackets = new ArrayList<>();

    private @Nullable Animation animationX;
    private @Nullable Animation animationY;
    private @Nullable Animation animationZ;

    private Vec3 vec3;
    private EntityPlayer target;

    private int currentLatency = 0;

    public Backtrack() {
        super("Backtrack", category.player);
        this.registerSetting(new DescriptionSetting("Allows you to hit past opponents by delaying packets."));
        this.registerSetting(minLatency);
        this.registerSetting(maxLatency);
        this.registerSetting(minDistance);
        this.registerSetting(maxDistance);
        this.registerSetting(stopOnTargetHurtTime);
        this.registerSetting(stopOnSelfHurtTime);
        this.registerSetting(drawRealPosition);
    }

    @Override
    public String getInfo() {
        return (currentLatency == 0 ? (int) maxLatency.getInput() : currentLatency) + "ms";
    }

    @Override
    public void guiUpdate() {
        Utils.correctValue(minLatency, maxLatency);
        Utils.correctValue(minDistance, maxDistance);
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        releaseAll();
        resetState();
    }

    private void resetState() {
        packetQueue.clear();
        skipPackets.clear();
        vec3 = null;
        target = null;
        currentLatency = 0;
        animationX = null;
        animationY = null;
        animationZ = null;
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (target == null || vec3 == null) return;

        try {
            double distance = vec3.distanceTo(mc.thePlayer.getPositionVector());
            if (distance > maxDistance.getInput() || distance < minDistance.getInput()) {
                currentLatency = 0;
            }
        } catch (Exception ignored) {}
    }

    @SubscribeEvent
    public void onPreTick(PreTickEvent e) {
        while (!packetQueue.isEmpty()) {
            TimedPacket timed = packetQueue.peek();
            if (timed == null) break;

            if (timed.getCold().getCum(currentLatency)) {
                Packet<?> packet = packetQueue.poll().getPacket();
                skipPackets.add(packet);
                PacketUtils.receivePacket(packet);
            } else {
                break;
            }
        }

        if (packetQueue.isEmpty() && target != null && !target.isDead) {
            vec3 = new Vec3(target.getPositionVector());
        }
    }

    @SubscribeEvent
    public void onRender(RenderWorldLastEvent e) {
        if (target == null || vec3 == null || target.isDead) {
            return;
        }

        net.minecraft.util.Vec3 renderPos = currentLatency > 0 ? vec3.toVec3() : target.getPositionVector();

        if (animationX == null || animationY == null || animationZ == null) {
            animationX = new Animation(Easing.EASE_OUT_CIRC, 300);
            animationY = new Animation(Easing.EASE_OUT_CIRC, 300);
            animationZ = new Animation(Easing.EASE_OUT_CIRC, 300);

            animationX.setValue(renderPos.xCoord);
            animationY.setValue(renderPos.yCoord);
            animationZ.setValue(renderPos.zCoord);
        }

        animationX.run(renderPos.xCoord);
        animationY.run(renderPos.yCoord);
        animationZ.run(renderPos.zCoord);

        if (drawRealPosition.isToggled()) {
            // Replace Blink.drawBox with your own render method if needed
            // e.g. RenderUtils.drawFilledBox or ESPUtil
            Blink.drawBox(new net.minecraft.util.Vec3(animationX.getValue(), animationY.getValue(), animationZ.getValue()));
        }
    }

    @SubscribeEvent
    public void onAttack(@NotNull AttackEntityEvent e) {
        if (!(e.target instanceof EntityPlayer)) return;

        EntityPlayer newTarget = (EntityPlayer) e.target;
        Vec3 targetPos = new Vec3(newTarget);

        if (target == null || newTarget != target) {
            vec3 = targetPos;

            if (animationX != null && animationY != null && animationZ != null) {
                long duration = target == null ? 0 :
                        Math.min(500, Math.max(100, (long) (new Vec3(e.target).distanceTo(target) * 50)));
                animationX.setDuration(duration);
                animationY.setDuration(duration);
                animationZ.setDuration(duration);
            }
        } else if (animationX != null && animationY != null && animationZ != null) {
            animationX.setDuration(100);
            animationY.setDuration(100);
            animationZ.setDuration(100);
        }

        target = newTarget;

        try {
            double distance = targetPos.distanceTo(mc.thePlayer.getPositionVector());
            if (distance > maxDistance.getInput() || distance < minDistance.getInput()) {
                return;
            }
        } catch (Exception ignored) {}

        currentLatency = (int) (Math.random() * (maxLatency.getInput() - minLatency.getInput()) + minLatency.getInput());
    }

    @SubscribeEvent
    public void onReceivePacket(@NotNull ReceivePacketEvent e) {
        if (Utils.nullCheck()) return;

        Packet<?> p = e.getPacket();

        if (skipPackets.contains(p)) {
            skipPackets.remove(p);
            return;
        }

        if (target != null && stopOnTargetHurtTime.getInput() != -1 && target.hurtTime == stopOnTargetHurtTime.getInput()) {
            releaseAll();
            return;
        }
        if (stopOnSelfHurtTime.getInput() != -1 && mc.thePlayer.hurtTime == stopOnSelfHurtTime.getInput()) {
            releaseAll();
            return;
        }

        if (mc.thePlayer == null || mc.thePlayer.ticksExisted < 20) {
            packetQueue.clear();
            return;
        }

        if (target == null) {
            releaseAll();
            return;
        }

        if (e.isCanceled()) return;

        // Skip certain packets that shouldn't be delayed
        if (p instanceof S19PacketEntityStatus
                || p instanceof S02PacketChat
                || p instanceof S0BPacketAnimation
                || p instanceof S06PacketUpdateHealth) {
            return;
        }

        if (p instanceof S08PacketPlayerPosLook || p instanceof S40PacketDisconnect) {
            releaseAll();
            target = null;
            vec3 = null;
            return;
        }

        if (p instanceof S13PacketDestroyEntities) {
            S13PacketDestroyEntities wrapper = (S13PacketDestroyEntities) p;
            for (int id : wrapper.getEntityIDs()) {
                if (id == target.getEntityId()) {
                    releaseAll();
                    target = null;
                    vec3 = null;
                    return;
                }
            }
        }

        if (p instanceof S14PacketEntity) {
            S14PacketEntity wrapper = (S14PacketEntity) p;
            if (((S14PacketEntityAccessor) wrapper).getEntityId() == target.getEntityId()) {
                vec3 = vec3.add(
                        wrapper.func_149062_c() / 32.0D,
                        wrapper.func_149061_d() / 32.0D,
                        wrapper.func_149064_e() / 32.0D
                );
            }
        }

        if (p instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport wrapper = (S18PacketEntityTeleport) p;
            if (wrapper.getEntityId() == target.getEntityId()) {
                vec3 = new Vec3(
                        wrapper.getX() / 32.0D,
                        wrapper.getY() / 32.0D,
                        wrapper.getZ() / 32.0D
                );
            }
        }

        // Queue the packet for delay
        packetQueue.add(new TimedPacket(p));
        e.setCanceled(true);
    }

    private void releaseAll() {
        if (!packetQueue.isEmpty()) {
            for (TimedPacket timed : packetQueue) {
                Packet<?> packet = timed.getPacket();
                skipPackets.add(packet);
                PacketUtils.receivePacket(packet);
            }
            packetQueue.clear();
        }
        currentLatency = 0;
    }
}