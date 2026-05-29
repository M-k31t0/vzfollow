package com.vz.follow;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side "follow" mod.
 * Press the toggle key to make YOUR character auto-walk behind the nearest player.
 * - Safe mode: steps down at most 1 block; stops at 2+ block drops, lava, fire and water.
 * - Auto-jump: jumps a single 1-block step-up.
 * Movement is driven through vanilla key bindings, so normal collision/physics apply.
 */
public class VzFollowClient implements ClientModInitializer {

    // Tunables
    private static final double FOLLOW_DISTANCE = 2.5;  // stop walking once this close
    private static final double SPRINT_DISTANCE = 6.0;  // sprint to catch up beyond this
    private static final double MAX_FOLLOW = 80.0;      // give up if target gets this far
    private static final double AHEAD = 0.6;            // how far ahead to sample terrain
    private static final long MSG_COOLDOWN_MS = 1500;

    // Zoom tunables (OptiFine-style hold-to-zoom)
    private static final double ZOOM_FOV_FACTOR = 0.25;          // FOV multiplier while zoomed (~4x, like OptiFine)
    private static final double ZOOM_SENSITIVITY_FACTOR = 0.4;   // slower look while zoomed = finer aim

    private boolean following = false;
    private long lastMsgAt = 0L;
    private KeyBinding toggleKey;

    // Zoom state. `zooming` is read by GameRendererZoomMixin (same client/render thread).
    private KeyBinding zoomKey;
    private static volatile boolean zooming = false;
    private double savedSensitivity;

    /** Whether the zoom key is currently held (read by the GameRenderer mixin). */
    public static boolean isZooming() {
        return zooming;
    }

    /** How much to multiply the rendered FOV by while zooming (smaller = stronger zoom). */
    public static double zoomFovMultiplier() {
        return ZOOM_FOV_FACTOR;
    }

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("vzfollow", "general"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.vzfollow.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                category
        ));

        zoomKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.vzfollow.zoom",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_C,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                toggle(client);
            }
            if (following) {
                tick(client);
            }
            handleZoom(client);
        });
    }

    /**
     * OptiFine-style hold-to-zoom. The actual FOV is scaled in {@code GameRendererZoomMixin}
     * (which bypasses the vanilla 30-degree FOV-option floor, so the zoom is as strong as
     * OptiFine's). Here we just flip the {@code zooming} flag and lower look sensitivity for
     * finer aim, restoring it on release. FOV is purely client-side — this only changes how
     * YOU see the world.
     */
    private void handleZoom(MinecraftClient mc) {
        boolean wantZoom = zoomKey.isPressed() && mc.player != null && mc.currentScreen == null;
        if (wantZoom && !zooming) {
            savedSensitivity = mc.options.getMouseSensitivity().getValue();
            mc.options.getMouseSensitivity().setValue(savedSensitivity * ZOOM_SENSITIVITY_FACTOR);
            zooming = true;
        } else if (!wantZoom && zooming) {
            mc.options.getMouseSensitivity().setValue(savedSensitivity);
            zooming = false;
        }
    }

    private void toggle(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            return;
        }
        if (following) {
            stop(mc, "追尾を停止しました");
            return;
        }
        PlayerEntity target = nearestPlayer(mc, mc.player);
        if (target == null) {
            actionbar(mc, "近くにプレイヤーがいません");
            return;
        }
        following = true;
        actionbar(mc, "追尾開始: " + target.getName().getString());
    }

    private void stop(MinecraftClient mc, String why) {
        following = false;
        releaseInputs(mc);
        if (why != null) {
            actionbar(mc, why);
        }
    }

    private void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld world = mc.world;
        if (p == null || world == null) {
            stop(mc, null);
            return;
        }
        // Don't drive movement while a screen (inventory/chat/menu) is open.
        if (mc.currentScreen != null) {
            releaseInputs(mc);
            return;
        }

        PlayerEntity target = nearestPlayer(mc, p);
        if (target == null) {
            releaseInputs(mc);
            throttledMsg(mc, "対象が見つかりません");
            return;
        }

        double px = p.getX();
        double py = p.getY();
        double pz = p.getZ();
        double dx = target.getX() - px;
        double dz = target.getZ() - pz;
        double dist = Math.sqrt(dx * dx + dz * dz);

        // Always face the target.
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        p.setYaw(yaw);
        p.setBodyYaw(yaw);
        p.setHeadYaw(yaw);

        if (dist > MAX_FOLLOW) {
            stop(mc, "対象が遠すぎるため停止しました");
            return;
        }

        if (dist <= FOLLOW_DISTANCE) {
            releaseInputs(mc); // close enough; idle but keep facing
            return;
        }

        boolean moveForward = true;
        boolean jump = false;

        if (p.isOnGround() && dist > 1.0e-4) {
            double ux = dx / dist;
            double uz = dz / dist;
            int fx = (int) Math.floor(px + ux * AHEAD);
            int fz = (int) Math.floor(pz + uz * AHEAD);
            int feetY = (int) Math.floor(py);

            boolean solidAtFeet = isSolid(world, fx, feetY, fz);
            boolean solidAbove1 = isSolid(world, fx, feetY + 1, fz);
            boolean solidAbove2 = isSolid(world, fx, feetY + 2, fz);
            boolean solidBelow1 = isSolid(world, fx, feetY - 1, fz);
            boolean solidBelow2 = isSolid(world, fx, feetY - 2, fz);

            boolean danger = isDanger(world, fx, feetY, fz)
                    || isDanger(world, fx, feetY - 1, fz)
                    || isDanger(world, fx, feetY - 2, fz);

            if (danger) {
                moveForward = false;
                throttledMsg(mc, "危険ブロックを検知して停止");
            } else if (solidAtFeet) {
                if (!solidAbove1 && !solidAbove2) {
                    jump = true; // 1-block step up
                } else {
                    moveForward = false; // wall too tall
                    throttledMsg(mc, "壁を検知して停止");
                }
            } else if (solidBelow1) {
                // flat ground ahead
            } else if (solidBelow2) {
                // 1-block step down (allowed)
            } else {
                moveForward = false; // 2+ block drop / void
                throttledMsg(mc, "崖を検知して停止");
            }
        }

        setForward(mc, moveForward);
        setSprint(mc, moveForward && dist > SPRINT_DISTANCE);
        setJump(mc, jump);
    }

    private PlayerEntity nearestPlayer(MinecraftClient mc, ClientPlayerEntity self) {
        if (mc.world == null) {
            return null;
        }
        PlayerEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (PlayerEntity pe : mc.world.getPlayers()) {
            if (pe == self || !pe.isAlive()) {
                continue;
            }
            double dSq = pe.squaredDistanceTo(self);
            if (dSq < bestSq) {
                bestSq = dSq;
                best = pe;
            }
        }
        return best;
    }

    private boolean isSolid(ClientWorld world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState st = world.getBlockState(pos);
        return !st.getCollisionShape(world, pos).isEmpty();
    }

    private boolean isDanger(ClientWorld world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState st = world.getBlockState(pos);
        if (st.getFluidState().isIn(FluidTags.LAVA) || st.getFluidState().isIn(FluidTags.WATER)) {
            return true;
        }
        return st.isOf(Blocks.FIRE)
                || st.isOf(Blocks.SOUL_FIRE)
                || st.isOf(Blocks.MAGMA_BLOCK)
                || st.isOf(Blocks.LAVA);
    }

    private void setForward(MinecraftClient mc, boolean v) {
        mc.options.forwardKey.setPressed(v);
    }

    private void setSprint(MinecraftClient mc, boolean v) {
        mc.options.sprintKey.setPressed(v);
    }

    private void setJump(MinecraftClient mc, boolean v) {
        mc.options.jumpKey.setPressed(v);
    }

    private void releaseInputs(MinecraftClient mc) {
        setForward(mc, false);
        setSprint(mc, false);
        setJump(mc, false);
    }

    private void actionbar(MinecraftClient mc, String text) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[追尾] " + text), true);
        }
    }

    private void throttledMsg(MinecraftClient mc, String text) {
        long now = System.currentTimeMillis();
        if (now - lastMsgAt >= MSG_COOLDOWN_MS) {
            lastMsgAt = now;
            actionbar(mc, text);
        }
    }
}
