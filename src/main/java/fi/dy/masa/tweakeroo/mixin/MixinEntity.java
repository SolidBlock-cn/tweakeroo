package fi.dy.masa.tweakeroo.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import fi.dy.masa.tweakeroo.config.Configs;
import fi.dy.masa.tweakeroo.config.FeatureToggle;
import fi.dy.masa.tweakeroo.config.Hotkeys;
import fi.dy.masa.tweakeroo.util.CameraEntity;
import fi.dy.masa.tweakeroo.util.MiscUtils;
import fi.dy.masa.tweakeroo.util.SnapAimMode;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

@Mixin(Entity.class)
public abstract class MixinEntity
{
    @Shadow
    public World world;

    @Shadow public float yaw;
    @Shadow public float pitch;
    @Shadow public float prevYaw;
    @Shadow public float prevPitch;

    private double forcedPitch;
    private double forcedYaw;

    @Shadow public abstract Vec3d getVelocity();

    @Redirect(method = "clipSneakingMovement", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/entity/Entity;isSneaking()Z", ordinal = 0))
    private boolean fakeSneaking(Entity entity)
    {
        if (FeatureToggle.TWEAK_FAKE_SNEAKING.getBooleanValue() && ((Object) this) instanceof ClientPlayerEntity)
        {
            return true;
        }

        return ((Entity) (Object) this).isSneaking();
    }

    @Inject(method = "movementInputToVelocity", at = @At("HEAD"), cancellable = true)
    private void moreAccurateMoveRelative(Vec3d motion, float float_1, float yaw, CallbackInfoReturnable<Vec3d> cir)
    {
        if ((Object) this instanceof ClientPlayerEntity)
        {
            if (FeatureToggle.TWEAK_FREE_CAMERA.getBooleanValue() && FeatureToggle.TWEAK_FREE_CAMERA_MOTION.getBooleanValue())
            {
                CameraEntity camera = CameraEntity.getCamera();

                if (camera != null)
                {
                    cir.setReturnValue(this.getVelocity().multiply(1D, 0D, 1D));
                }
            }
            else if (FeatureToggle.TWEAK_SNAP_AIM.getBooleanValue())
            {
                double speed = motion.lengthSquared();

                if (speed < 1.0E-7D)
                {
                   cir.setReturnValue(Vec3d.ZERO);
                }
                else
                {
                   motion = (speed > 1.0D ? motion.normalize() : motion).multiply((double) float_1);
                   double xFactor = Math.sin(yaw * Math.PI / 180D);
                   double zFactor = Math.cos(yaw * Math.PI / 180D);

                   cir.setReturnValue(new Vec3d(motion.x * zFactor - motion.z * xFactor, motion.y, motion.z * zFactor + motion.x * xFactor));
                }
            }
        }
    }

    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void overrideYaw(double yawChange, double pitchChange, CallbackInfo ci)
    {
        if ((Object) this instanceof ClientPlayerEntity)
        {
            if (FeatureToggle.TWEAK_FREE_CAMERA.getBooleanValue() && FeatureToggle.TWEAK_FREE_CAMERA_MOTION.getBooleanValue())
            {
                this.yaw = this.prevYaw;
                this.pitch = this.prevPitch;

                this.updateCustomRotations(yawChange, pitchChange, true, true, 90);

                CameraEntity camera = CameraEntity.getCamera();

                if (camera != null)
                {
                    camera.setRotations((float) this.forcedYaw, (float) this.forcedPitch);
                }

                ci.cancel();

                return;
            }

            if (FeatureToggle.TWEAK_AIM_LOCK.getBooleanValue() ||
                (FeatureToggle.TWEAK_FREE_CAMERA.getBooleanValue() && FeatureToggle.TWEAK_FREE_CAMERA_MOTION.getBooleanValue()))
            {
                this.yaw = (float) this.forcedYaw;
                this.pitch = (float) this.forcedPitch;
                this.prevYaw = this.yaw;
                this.prevPitch = this.pitch;
                ci.cancel();

                return;
            }

            if (FeatureToggle.TWEAK_SNAP_AIM.getBooleanValue())
            {
                int pitchLimit = Configs.Generic.SNAP_AIM_PITCH_OVERSHOOT.getBooleanValue() ? 180 : 90;
                SnapAimMode mode = (SnapAimMode) Configs.Generic.SNAP_AIM_MODE.getOptionListValue();
                boolean snapAimLock = FeatureToggle.TWEAK_SNAP_AIM_LOCK.getBooleanValue();

                // Not locked, or not snapping the yaw (ie. not in Yaw or Both modes)
                boolean updateYaw = snapAimLock == false || mode == SnapAimMode.PITCH;
                // Not locked, or not snapping the pitch (ie. not in Pitch or Both modes)
                boolean updatePitch = snapAimLock == false || mode == SnapAimMode.YAW;

                this.updateCustomRotations(yawChange, pitchChange, updateYaw, updatePitch, pitchLimit);

                this.yaw = MiscUtils.getSnappedYaw(this.forcedYaw);
                this.pitch = MiscUtils.getSnappedPitch(this.forcedPitch);
                this.prevYaw = this.yaw;
                this.prevPitch = this.pitch;
                ci.cancel();

                return;
            }

            if (FeatureToggle.TWEAK_ELYTRA_CAMERA.getBooleanValue() && Hotkeys.ELYTRA_CAMERA.getKeybind().isKeybindHeld())
            {
                int pitchLimit = Configs.Generic.SNAP_AIM_PITCH_OVERSHOOT.getBooleanValue() ? 180 : 90;

                this.updateCustomRotations(yawChange, pitchChange, true, true, pitchLimit);

                MiscUtils.setCameraYaw((float) this.forcedYaw);
                MiscUtils.setCameraPitch((float) this.forcedPitch);

                this.yaw = this.prevYaw;
                this.pitch = this.prevPitch;
                this.prevYaw = this.yaw;
                this.prevPitch = this.pitch;
                ci.cancel();

                return;
            }

            // Update the internal rotations while no locking features are enabled
            // They will then be used as the forced rotations when some of the locking features are activated.
            this.forcedYaw = this.yaw;
            this.forcedPitch = this.pitch;
        }
    }

    private void updateCustomRotations(double yawChange, double pitchChange, boolean updateYaw, boolean updatePitch, float pitchLimit)
    {
        if (updateYaw)
        {
            this.forcedYaw += yawChange * 0.15D;
        }

        if (updatePitch)
        {
            this.forcedPitch = MathHelper.clamp(this.forcedPitch + pitchChange * 0.15D, -pitchLimit, pitchLimit);
        }
    }
}
