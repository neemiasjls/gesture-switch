package local.gestureswitch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** "Por luz" recovery when turning everything off fails partially. No device is contacted. */
public final class ExclusiveLightsProbe {
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    /** Fake panels/groups: failures are configured per panel or per (group, state). */
    static class FakeLights implements GroupLights {
        final List<String> log = new ArrayList<>();
        final Set<Integer> failingPanels = new HashSet<>();
        final Set<String> failingLights = new HashSet<>(); // "3:off"
        boolean failEverything;
        int generalCalls;

        @Override public int lightCount() { return 8; }
        @Override public int generalCount() { return 3; }
        @Override public String name() { return "fake"; }

        @Override
        public void setGeneral(boolean on, List<Integer> positions) {
            generalCalls++;
            log.add("general " + (on ? "on " : "off ") + positions);
            if (failEverything) throw new RuntimeException("rede indisponível");
            List<Integer> failed = positions.stream().filter(failingPanels::contains).toList();
            if (!failed.isEmpty()) throw new TargetFailure(failed, "Falha simulada nos painéis " + failed + ".");
        }

        @Override
        public void setLight(int number, boolean on) {
            String key = number + ":" + (on ? "on" : "off");
            log.add("light " + key);
            if (failingLights.contains(key)) throw new IllegalStateException("Falha simulada na luz " + number + ".");
        }

        @Override
        public boolean isLightOn(int number) { throw new AssertionError("Por luz não deve ler o estado"); }
    }

    private static Exception attempt(ThrowingRunnable action) {
        try { action.run(); return null; }
        catch (Exception error) { return error; }
    }

    interface ThrowingRunnable { void run() throws Exception; }

    public static void main(String[] args) {
        long[] now = {0};
        FakeLights fake = new FakeLights();
        ExclusiveLights mode = new ExclusiveLights(new TrackedLights(fake, () -> now[0]), () -> now[0]);

        // 1. Panel 2 (position 1) refuses "off": one immediate retry of that panel only, then report.
        fake.failingPanels.add(1);
        Exception error = attempt(mode::enter);
        check(error instanceof IllegalStateException, "Falha parcial ao entrar não foi informada");
        check(error.getMessage().contains("1 de 3"), "Mensagem não indica quantos painéis falharam: " + error.getMessage());
        check(fake.log.equals(List.of("general off [0, 1, 2]", "general off [1]")),
                "Entrada deveria tentar tudo e repetir só o painel com falha uma vez: " + fake.log);
        check(!mode.exclusive() && mode.pendingPanelCount() == 1, "Exclusividade assumida sem confirmação");

        // 2. Selections soon after do not hammer the panels; the chosen light is still lit.
        fake.log.clear();
        now[0] = 1000;
        check(attempt(() -> mode.select(3)) == null, "Seleção falhou");
        now[0] = 2000;
        check(attempt(() -> mode.select(5)) == null, "Troca falhou");
        check(fake.log.equals(List.of("light 3:on", "light 3:off", "light 5:on")),
                "Seleção antes do intervalo não deveria repetir o apagamento geral: " + fake.log);
        check(fake.generalCalls == 2, "Painéis repetidos cedo demais");

        // 3. Still failing after the interval: one retry, reported, light still selected.
        fake.log.clear();
        now[0] = 5500;
        error = attempt(() -> mode.select(6));
        check(error != null && error.getMessage().contains("painel"), "Falha persistente não foi informada");
        check(fake.log.equals(List.of("light 5:off", "general off [1]", "light 6:on")),
                "Ordem errada (apagar anterior, repetir painel, acender nova): " + fake.log);
        now[0] = 6000;
        fake.log.clear();
        check(attempt(() -> mode.select(2)) == null, "Seleção falhou");
        check(fake.log.equals(List.of("light 6:off", "light 2:on")), "Repetiu o painel antes de 5 s: " + fake.log);

        // 4. Panel recovers: the next selection after the interval restores exclusivity.
        fake.failingPanels.clear();
        fake.log.clear();
        now[0] = 10600;
        check(attempt(() -> mode.select(4)) == null, "Recuperação falhou");
        check(fake.log.equals(List.of("light 2:off", "general off [1]", "light 4:on")),
                "Painel pendente não foi apagado antes de acender a nova luz: " + fake.log);
        check(mode.exclusive(), "Exclusividade não restabelecida");
        fake.log.clear();
        now[0] = 20000;
        check(attempt(() -> mode.select(7)) == null, "Seleção falhou");
        check(fake.log.equals(List.of("light 4:off", "light 7:on")), "Painel apagado de novo sem necessidade: " + fake.log);

        // 5. A group that fails to turn off stays tracked and is turned off on the next change.
        fake.failingLights.add("7:off");
        fake.log.clear();
        error = attempt(() -> mode.select(1));
        check(error != null && error.getMessage().contains("luz 7"), "Falha ao apagar a luz anterior não informada");
        check(fake.log.equals(List.of("light 7:off", "light 1:on")), "Nova luz não foi acesa: " + fake.log);
        fake.failingLights.clear();
        fake.log.clear();
        check(attempt(() -> mode.select(0)) == null, "Zero falhou");
        check(fake.log.equals(List.of("light 1:off", "light 7:off")), "Luz pendente não foi apagada: " + fake.log);

        // 6. Selecting a pending group again just turns it on (no off/on flicker).
        fake.failingLights.add("8:off");
        attempt(() -> mode.select(8));
        attempt(() -> mode.select(2));
        fake.failingLights.clear();
        fake.log.clear();
        check(attempt(() -> mode.select(8)) == null, "Seleção falhou");
        check(fake.log.equals(List.of("light 2:off", "light 8:on")), "Grupo pendente piscou: " + fake.log);

        // 7. Leaving tries once; a failure is reported and never retried in a loop.
        fake.failingLights.add("8:off");
        fake.log.clear();
        error = attempt(mode::leave);
        check(error != null && error.getMessage().contains("luz 8"), "Falha ao sair não informada");
        fake.log.clear();
        check(attempt(mode::leave) == null && fake.log.isEmpty(), "Saída repetiu comandos: " + fake.log);
        fake.failingLights.clear();

        // 8. Immediate retry succeeds: exclusivity confirmed, no error.
        FakeLights flaky = new FakeLights() {
            int calls;
            @Override public void setGeneral(boolean on, List<Integer> positions) {
                if (++calls == 1) { generalCalls++; log.add("general off " + positions);
                    throw new TargetFailure(List.of(0, 2), "Falha simulada."); }
                super.setGeneral(on, positions);
            }
        };
        ExclusiveLights second = new ExclusiveLights(new TrackedLights(flaky, () -> 0), () -> 0);
        check(attempt(second::enter) == null, "Repetição imediata bem-sucedida não deveria gerar erro");
        check(flaky.log.equals(List.of("general off [0, 1, 2]", "general off [0, 2]")), "Repetição errada: " + flaky.log);
        check(second.exclusive(), "Exclusividade não confirmada");

        // 9. Total communication failure: every panel stays pending.
        FakeLights offline = new FakeLights();
        offline.failEverything = true;
        ExclusiveLights third = new ExclusiveLights(new TrackedLights(offline, () -> 0), () -> 0);
        check(attempt(third::enter) != null && third.pendingPanelCount() == 3, "Falha total não deixou 3 painéis pendentes");
        check(offline.generalCalls == 2, "Falha total repetiu mais de uma vez");

        // 10. After close (exit/Ctrl+C) nothing else is lit.
        FakeLights closing = new FakeLights();
        ExclusiveLights fourth = new ExclusiveLights(new TrackedLights(closing, () -> 0), () -> 0);
        attempt(fourth::enter);
        attempt(() -> fourth.select(3));
        closing.log.clear();
        check(attempt(fourth::close) == null && closing.log.equals(List.of("light 3:off")), "Fechar não apagou: " + closing.log);
        closing.log.clear();
        attempt(() -> fourth.select(5));
        check(closing.log.isEmpty(), "Seleção após fechar acendeu luz");

        System.out.println("ExclusiveLights OK: falha parcial, repetição limitada, recuperação, saída e fechamento");
    }
}
