package local.gestureswitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * "Por luz" mode: one group lit at a time.
 *
 * <p>Entering turns all panels off. If some panels do not accept it (even after one immediate
 * retry), exclusivity is NOT assumed: the failed panels stay pending and are retried at most once
 * per {@link #PANEL_RETRY_MS}, and only when the user changes the selection (never in a loop).
 * Groups that we lit, or failed to turn off, are tracked and turned off on later changes.
 */
final class ExclusiveLights {
    static final long PANEL_RETRY_MS = 5000;

    private final TrackedLights lights;
    private final LongSupplier clock;
    private final Set<Integer> maybeOn = new TreeSet<>();
    private final Set<Integer> pendingPanels = new TreeSet<>();
    private long lastPanelAttempt = Long.MIN_VALUE / 2;
    private int selected;
    private boolean closed;

    ExclusiveLights(TrackedLights lights, LongSupplier clock) {
        this.lights = lights;
        this.clock = clock;
    }

    /** Turns all panels off. Throws (after doing everything possible) when exclusivity is not confirmed. */
    synchronized void enter() throws Exception {
        if (closed) return;
        selected = 0;
        pendingPanels.clear();
        maybeOn.clear();
        lastPanelAttempt = clock.getAsLong();
        List<Integer> failed = offPanels(GroupLights.allGeneral(lights.generalCount()));
        if (!failed.isEmpty()) failed = offPanels(failed); // a single immediate retry, only of the failures
        pendingPanels.addAll(failed);
        if (!failed.isEmpty())
            throw new IllegalStateException("Por luz: " + failed.size() + " de " + lights.generalCount()
                    + " painéis não apagaram; outras luzes podem continuar acesas. Nova tentativa ao escolher"
                    + " uma luz. Motivo: " + lastReason + ".");
    }

    /** Applies a selection from ButtonGate (0 = none). Throws a summary if any step failed. */
    synchronized void select(int next) throws Exception {
        if (closed) return;
        List<String> problems = new ArrayList<>();
        for (int group : new ArrayList<>(maybeOn)) {
            if (group == next) continue;
            try {
                lights.setLight(group, false);
                maybeOn.remove(group);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception error) {
                problems.add("luz " + group + " pode continuar acesa (" + reason(error) + ")");
            }
        }
        long now = clock.getAsLong();
        if (!pendingPanels.isEmpty() && now - lastPanelAttempt >= PANEL_RETRY_MS) {
            lastPanelAttempt = now;
            List<Integer> failed = offPanels(new ArrayList<>(pendingPanels));
            pendingPanels.retainAll(failed);
            if (pendingPanels.isEmpty()) System.out.println("[POR LUZ] Painéis pendentes apagados; seleção exclusiva restabelecida.");
            else problems.add(pendingPanels.size() + " painel(is) ainda sem confirmar o apagamento (" + lastReason + ")");
        }
        selected = next;
        if (next != 0) {
            maybeOn.add(next); // even a failed "on" may have reached the device; turn it off later
            try {
                lights.setLight(next, true);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception error) {
                problems.add("falha ao acender a luz " + next + " (" + reason(error) + ")");
            }
        }
        if (!problems.isEmpty()) throw new IllegalStateException("Por luz: " + String.join("; ", problems) + ".");
    }

    /** Turns off what this mode lit. Single attempt: leaving never keeps retrying. */
    synchronized void leave() throws Exception {
        List<String> problems = new ArrayList<>();
        for (int group : new ArrayList<>(maybeOn)) {
            try {
                lights.setLight(group, false);
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception error) {
                problems.add("luz " + group + " pode continuar acesa (" + reason(error) + ")");
            }
        }
        maybeOn.clear();
        pendingPanels.clear();
        selected = 0;
        if (!problems.isEmpty()) throw new IllegalStateException("Ao sair do Por luz: " + String.join("; ", problems) + ".");
    }

    /** Final cleanup (normal exit or Ctrl+C); later selections are ignored. */
    synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        leave();
    }

    synchronized boolean exclusive() { return pendingPanels.isEmpty(); }

    synchronized int pendingPanelCount() { return pendingPanels.size(); }

    synchronized int selected() { return selected; }

    synchronized boolean hasLit() { return !maybeOn.isEmpty(); }

    private String lastReason = "";

    /** Returns the positions that failed. */
    private List<Integer> offPanels(List<Integer> positions) throws Exception {
        try {
            lights.setGeneral(false, positions);
            return List.of();
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (TargetFailure failure) {
            lastReason = reason(failure);
            return failure.positions().stream().filter(positions::contains).toList();
        } catch (Exception error) {
            lastReason = reason(error);
            return positions;
        }
    }

    private static String reason(Exception error) {
        return error instanceof IllegalStateException && error.getMessage() != null
                ? error.getMessage().replaceAll("\\.$", "") : "falha de comunicação";
    }
}
