package local.gestureswitch;

/** Debounces the finger count before changing the exclusively selected light. */
final class ButtonGate {
    private static final long STABLE_MS = 250;
    private static final long MAX_GAP_MS = 1500;
    private static final int MIN_SAMPLES = 3;
    private final int maxNumber;

    private int candidate = -1;
    private long candidateSince;
    private long lastSample = -1;
    private int samples;
    private int selected;

    ButtonGate(int maxNumber) {
        if (maxNumber < 1 || maxNumber > 10) throw new IllegalArgumentException("Limite de luzes inválido");
        this.maxNumber = maxNumber;
    }

    ButtonGate() { this(10); }

    /** Returns a changed selection, including zero for all off; null means no change. */
    Integer accept(long now, int number, int hands) {
        if (lastSample >= 0 && (now <= lastSample || now - lastSample > MAX_GAP_MS)) {
            candidate = -1;
            samples = 0;
        }
        lastSample = now;
        boolean valid = number == 0 || number >= 1 && number <= maxNumber &&
                (hands == 1 || hands == 2) && (number <= 5 || hands == 2);
        if (!valid) number = 0;
        if (number == selected) {
            candidate = -1;
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
        if (samples < MIN_SAMPLES || now - candidateSince < STABLE_MS) return null;
        selected = number;
        candidate = -1;
        samples = 0;
        return number;
    }
}
