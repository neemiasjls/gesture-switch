package local.gestureswitch;

import java.util.Arrays;
import java.util.List;

/** Prints what would happen; never contacts a device. Eight groups on three panels. */
final class SimulatedController implements GroupLights {
    private static final int LIGHTS = 8;
    private static final int PANELS = 3;
    private final Boolean[] lights = new Boolean[LIGHTS + 1];

    @Override
    public int lightCount() { return LIGHTS; }

    @Override
    public int generalCount() { return PANELS; }

    @Override
    public void setGeneral(boolean on, List<Integer> positions) {
        if (positions.size() == PANELS) {
            boolean unchanged = true;
            for (int i = 1; i <= LIGHTS; i++) unchanged &= lights[i] != null && lights[i] == on;
            if (unchanged) {
                System.out.println("[SIMULAÇÃO] Os 3 já estão " + (on ? "acesos" : "apagados") + "; nenhum comando novo.");
                return;
            }
            Arrays.fill(lights, on);
            System.out.println("[SIMULAÇÃO] 3 interruptores da sala: " + (on ? "ACENDER TODOS" : "APAGAR TODOS"));
        } else {
            // Panel-to-group mapping is unknown here, as in the real installation.
            Arrays.fill(lights, null);
            System.out.println("[SIMULAÇÃO] " + positions.size() + " de 3 interruptores: "
                    + (on ? "ACENDER" : "APAGAR"));
        }
    }

    @Override
    public void setLight(int number, boolean on) {
        check(number);
        lights[number] = on;
        System.out.println("[SIMULAÇÃO] Luz " + number + (on ? " ligada." : " desligada."));
    }

    @Override
    public boolean isLightOn(int number) {
        check(number);
        return Boolean.TRUE.equals(lights[number]);
    }

    private static void check(int number) {
        if (number < 1 || number > LIGHTS) throw new IllegalArgumentException("Luz fora da sequência configurada");
    }

    @Override
    public String name() { return "simulação (nenhum dispositivo será acionado)"; }
}
