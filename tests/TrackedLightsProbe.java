package local.gestureswitch;

import java.util.List;

/** Short memory of commanded states versus a stale cloud read. No device is contacted. */
public final class TrackedLightsProbe {
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    /** The "cloud" keeps reporting the state from before our commands (worst case: stale read). */
    static final class StaleCloud implements GroupLights {
        final boolean[] cloud = new boolean[9];
        int reads;
        int sets;
        boolean failGeneral;
        boolean failLight;

        @Override public int lightCount() { return 8; }
        @Override public int generalCount() { return 3; }
        @Override public String name() { return "fake"; }

        @Override public boolean isLightOn(int number) { reads++; return cloud[number]; }

        @Override public void setLight(int number, boolean on) {
            sets++;
            if (failLight) throw new IllegalStateException("Falha simulada.");
        }

        @Override public void setGeneral(boolean on, List<Integer> positions) {
            if (failGeneral) throw new TargetFailure(List.of(1), "Falha simulada.");
        }
    }

    public static void main(String[] args) throws Exception {
        long[] now = {0};
        StaleCloud cloud = new StaleCloud();
        TrackedLights lights = new TrackedLights(cloud, () -> now[0]);

        TrackedLights.Toggle first = lights.toggle(1);
        check(first.on() && !first.remembered() && cloud.reads == 1, "Primeira alternância deve ler a nuvem");

        // Repeating 1 shortly after: the stale cloud still says "off"; our own command wins.
        now[0] = 1000;
        TrackedLights.Toggle second = lights.toggle(1);
        check(!second.on() && second.remembered() && cloud.reads == 1,
                "Leitura atrasada da nuvem causaria ligar de novo em vez de apagar");

        // After the trust window, the cloud is the source of truth again (e.g. app/Alexa changes).
        now[0] = 1000 + TrackedLights.TRUST_MS;
        TrackedLights.Toggle third = lights.toggle(1);
        check(third.on() && !third.remembered() && cloud.reads == 2, "Memória vencida ainda foi usada");

        // All on is remembered for every group; toggling 8 right after turns it off.
        now[0] = 20000;
        lights.set(true);
        now[0] = 21000;
        TrackedLights.Toggle eight = lights.toggle(8);
        check(!eight.on() && eight.remembered() && cloud.reads == 2, "Ligar todas não foi lembrado");

        // Partial failure of a general command: nothing is assumed, read again.
        cloud.failGeneral = true;
        try { lights.set(false); throw new AssertionError("Falha parcial não propagada"); }
        catch (TargetFailure expected) { }
        cloud.failGeneral = false;
        lights.toggle(2);
        check(cloud.reads == 3, "Após falha parcial deveria ler a nuvem");

        // A panel subset (Por luz retry) cannot be mapped to groups: forget.
        now[0] = 22000;
        lights.setLight(3, true);
        lights.setGeneral(false, List.of(1));
        lights.toggle(3);
        check(cloud.reads == 4, "Apagamento parcial de painéis não invalidou a memória");

        // A failed individual command forgets that group.
        now[0] = 23000;
        lights.setLight(4, true);
        cloud.failLight = true;
        try { lights.setLight(4, false); throw new AssertionError("Falha não propagada"); }
        catch (IllegalStateException expected) { }
        cloud.failLight = false;
        lights.toggle(4);
        check(cloud.reads == 5, "Falha de comando deveria invalidar a memória");

        // Clock going backwards (should not happen) never trusts the memory.
        now[0] = 30000;
        lights.setLight(5, true);
        now[0] = 29000;
        lights.toggle(5);
        check(cloud.reads == 6, "Relógio voltando não deveria usar a memória");

        System.out.println("TrackedLights OK: memória curta, expiração, falhas e comandos gerais");
    }
}
