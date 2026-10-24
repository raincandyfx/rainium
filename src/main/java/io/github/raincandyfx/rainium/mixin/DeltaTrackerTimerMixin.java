package io.github.raincandyfx.rainium.mixin;

import io.github.raincandyfx.rainium.RecordingClock;
import it.unimi.dsi.fastutil.floats.FloatUnaryOperator;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a recording runs, game time follows the recording clock instead of the real clock: each
 * frame moves it on by exactly one recording step, or not at all while saving catches up. Client
 * ticks and the partial tick (and so the player model, emotes from other mods and everything else
 * that animates with game time) then advance evenly between saved frames.
 */
@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerTimerMixin {
    @Shadow private float deltaTicks;
    @Shadow private float deltaTickResidual;
    @Shadow private long lastMs;
    @Shadow @Final private float msPerTick;
    @Shadow @Final private FloatUnaryOperator targetMsptProvider;

    @Inject(method = "advanceGameTime", at = @At("HEAD"), cancellable = true)
    private void rainium$recordingClock(long currentMs, CallbackInfoReturnable<Integer> cir) {
        double step = RecordingClock.gameStepMs();
        if (Double.isNaN(step)) return;
        this.deltaTicks = (float) (step / this.targetMsptProvider.apply(this.msPerTick));
        this.lastMs = currentMs;   // so real time picks up smoothly when the recording stops
        this.deltaTickResidual += this.deltaTicks;
        int ticks = (int) this.deltaTickResidual;
        this.deltaTickResidual -= ticks;
        cir.setReturnValue(ticks);
    }
}
