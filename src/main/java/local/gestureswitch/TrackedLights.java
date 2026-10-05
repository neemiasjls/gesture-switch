package local.gestureswitch;

import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Wraps the light groups and remembers, for a few seconds, the state we last commanded to each
 * group. The cloud state read right after a command may still be the old one; trusting our own
 * recent command avoids "toggling" a light to the state it already has. Nothing is sent
 * automatically and no state is polled: the memory only replaces one read.
 */
final class TrackedLights {
    static final long TRUST_MS = 6000;

    record Toggle(boolean on, boolean remembered) {}

    private final GroupLights lights;
    private final LongSupplier clock;
    private final boolean[] known;
    private final boolean[] state;
    private final long[] at;

    TrackedLights(GroupLights lights, LongSupplier clock) {
        this.lights = lights;
        this.clock = clock;
        int count = lights.lightCount();
        known = new boolean[count + 1];
        state = new boolean[count + 1];
        at = new long[count + 1];
    }

    int lightCount() { return lights.lightCount(); }

    int generalCount() { return lights.generalCount(); }

    void setLight(int number, boolean on) throws Exception {
        try {
            lights.setLight(number, on);
            remember(number, on);
        } catch (Exception error) {
            forget(number);
            throw error;
        }
    }

    /** Sends the opposite of the remembered (recent) or read state. */
    Toggle toggle(int number) throws Exception {
        Boolean recent = recent(number);
        boolean current = recent != null ? recent : lights.isLightOn(number);
        setLight(number, !current);
        return new Toggle(!current, recent != null);
    }

    void setGeneral(boolean on, List<Integer> positions) throws Exception {
        try {
            lights.setGeneral(on, positions);
            // A partial panel set cannot be mapped to groups: read the cloud next time.
            if (positions.size() == lights.generalCount()) rememberAll(on);
            else forgetAll();
        } catch (Exception error) {
            forgetAll();
            throw error;
        }
    }

    void set(boolean on) throws Exception {
        setGeneral(on, GroupLights.allGeneral(lights.generalCount()));
    }

    synchronized Boolean recent(int number) {
        if (number < 1 || number >= known.length || !known[number]) return null;
        long age = clock.getAsLong() - at[number];
        return age >= 0 && age < TRUST_MS ? state[number] : null;
    }

    private synchronized void remember(int number, boolean on) {
        if (number < 1 || number >= known.length) return;
        known[number] = true;
        state[number] = on;
        at[number] = clock.getAsLong();
    }

    private synchronized void forget(int number) {
        if (number >= 1 && number < known.length) known[number] = false;
    }

    private synchronized void rememberAll(boolean on) {
        long now = clock.getAsLong();
        Arrays.fill(known, true);
        Arrays.fill(state, on);
        Arrays.fill(at, now);
    }

    private synchronized void forgetAll() {
        Arrays.fill(known, false);
    }
}
