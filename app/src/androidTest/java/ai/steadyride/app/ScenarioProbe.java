package ai.steadyride.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import ai.steadyride.app.core.ScenarioUi;

/** Device integration probe. Uses the real SDK; never supplies synthetic success data. */
public final class ScenarioProbe extends Instrumentation {
    private SteadyRideController controller;
    private String expected;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        expected = args == null ? null : args.getString("expected");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            runOnMainSync(() -> {
                controller = new SteadyRideController(getTargetContext());
                controller.start();
                controller.startNamyangScenario();
            });
            long deadline = SystemClock.elapsedRealtime() + 75000;
            while (controller.getUi().getValue().getScenario().getBusy() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100);
            }
            ScenarioUi state = controller.getUi().getValue().getScenario();
            result.putString("phase", state.getPhase().name());
            result.putString("message", state.getMessage());
            result.putString("distanceM", String.valueOf(state.getDistanceM()));
            result.putString("durationS", String.valueOf(state.getDurationS()));
            if (state.getBusy()) throw new AssertionError("Scenario remained busy");
            if (expected != null && !expected.equals(state.getPhase().name())) {
                throw new AssertionError("Expected " + expected + ", got " + state.getPhase());
            }
            code = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("failure", error.toString());
        } finally {
            if (controller != null) runOnMainSync(() -> controller.release());
            finish(code, result);
        }
    }
}
