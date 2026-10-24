package io.github.raincandyfx.rainium;

/** The recording clock as seen from the game loop hook ({@code mixin.DeltaTrackerTimerMixin}). */
public final class RecordingClock {
    private RecordingClock() {}

    /** Milliseconds of game time this frame should advance, or NaN to use real time. */
    public static double gameStepMs() { return Recorder.gameStepMs(System.nanoTime()); }
}
