package local.gestureswitch;

/**
 * Confirms a count without treating network delays or uncertain frames as a release.
 * Each number toggles at most once until the hands leave for {@link #RELEASE_MS}; this also covers
 * returning to a number through another one (3 -> 4 -> 3), e.g. a count flickering at a boundary.
 */
final class ToggleGate {
    private static final long HOLD_MS = 250;
    private static final long RELEASE_MS = 400;
    private static final long MAX_GAP_MS = 1500;
    private final int maxNumber;
    private int usedSinceRelease; // bit per number already toggled while the hands stayed
    private int candidate;
    private int samples;
    private long candidateSince;
    private long neutralSince = -1;
    private long lastSample = -1;

    ToggleGate(int maxNumber) {
        if (maxNumber < 1 || maxNumber > 10) throw new IllegalArgumentException("Limite de luzes inválido");
        this.maxNumber = maxNumber;
    }

    Integer accept(long now, int number, int hands) {
        if (lastSample >= 0 && (now <= lastSample || now - lastSample > MAX_GAP_MS)) {
            candidate = 0;
            samples = 0;
            neutralSince = -1;
        }
        lastSample = now;
        if (hands == 0) {
            candidate = 0;
            samples = 0;
            if (neutralSince < 0) neutralSince = now;
            if (now - neutralSince >= RELEASE_MS) usedSinceRelease = 0;
            return null;
        }
        neutralSince = -1;
        boolean valid = number >= 1 && number <= maxNumber
                && (hands == 1 || hands == 2) && (number <= 5 || hands == 2);
        if (!valid || (usedSinceRelease & (1 << number)) != 0) {
            candidate = 0;
            samples = 0;
            return null;
        }
        if (number != candidate) {
            candidate = number;
            candidateSince = now;
            samples = 1;
            return null;
        }
        samples++;
        if (samples < 3 || now - candidateSince < HOLD_MS) return null;
        usedSinceRelease |= 1 << number;
        candidate = 0;
        samples = 0;
        return number;
    }
}
