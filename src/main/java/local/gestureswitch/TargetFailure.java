package local.gestureswitch;

import java.util.List;

/** Some targets of a command failed; positions are 0-based and never contain device IDs. */
final class TargetFailure extends IllegalStateException {
    private final List<Integer> positions;

    TargetFailure(List<Integer> positions, String message) {
        super(message);
        this.positions = List.copyOf(positions);
    }

    List<Integer> positions() { return positions; }
}
