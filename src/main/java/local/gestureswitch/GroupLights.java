package local.gestureswitch;

import java.util.ArrayList;
import java.util.List;

/** Individually addressable light groups plus the general (whole panel) targets. */
interface GroupLights extends LightController {
    int lightCount();

    int generalCount();

    void setLight(int number, boolean on) throws Exception;

    boolean isLightOn(int number) throws Exception;

    /**
     * Sends on/off to the given general targets (0-based positions). If only some fail, throws
     * {@link TargetFailure} listing the failed positions; the others were accepted.
     */
    void setGeneral(boolean on, List<Integer> positions) throws Exception;

    @Override
    default void set(boolean on) throws Exception {
        setGeneral(on, allGeneral(generalCount()));
    }

    static List<Integer> allGeneral(int count) {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < count; i++) all.add(i);
        return all;
    }
}
