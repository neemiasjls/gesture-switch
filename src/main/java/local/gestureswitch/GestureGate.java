package local.gestureswitch;

/** Requires a stable gesture and cooldown; repeating one gesture also needs a neutral reset. */
public final class GestureGate {
    public enum Command { ON, OFF }
    private static final long HOLD_MS = 1200;
    private static final long RESET_MS = 500;
    private static final long COOLDOWN_MS = 2500;
    private static final long MAX_GAP_MS = 1500;
    private static final int MIN_SAMPLES = 8;
    private static final double MIN_SCORE = 0.80;

    private String candidate;
    private long candidateSince;
    private long lastSample = -1;
    private int samples;
    private boolean armed = true;
    private long neutralSince = -1;
    private long lastCommand = Long.MIN_VALUE / 2;
    private String lastCommandLabel;

    public Command accept(long now, String label, double score, int hands) {
        if (lastSample >= 0 && (now <= lastSample || now - lastSample > MAX_GAP_MS)) {
            candidate = null;
            samples = 0;
            neutralSince = -1;
        }
        lastSample = now;
        boolean valid = score >= MIN_SCORE &&
                (hands == 1 && ("Open_Palm".equals(label) || "Closed_Fist".equals(label))
                        || hands == 2 && "All_On".equals(label));
        if (!valid) {
            candidate = null;
            samples = 0;
            if (!armed) {
                if (neutralSince < 0) neutralSince = now;
                if (now - neutralSince >= RESET_MS) armed = true;
            }
            return null;
        }
        neutralSince = -1;
        if (!label.equals(candidate)) {
            candidate = label;
            candidateSince = now;
            samples = 1;
            return null;
        }
        samples++;
        boolean oppositeAfterCooldown = !armed && lastCommandLabel != null
                && !label.equals(lastCommandLabel) && now - lastCommand >= COOLDOWN_MS;
        if ((!armed && !oppositeAfterCooldown) || now - lastCommand < COOLDOWN_MS || samples < MIN_SAMPLES
                || now - candidateSince < HOLD_MS) return null;
        armed = false;
        lastCommand = now;
        lastCommandLabel = label;
        return "Open_Palm".equals(label) || "All_On".equals(label) ? Command.ON : Command.OFF;
    }
}
