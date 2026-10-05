package local.gestureswitch;

public final class ToggleGateProbe {
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        ToggleGate gate = new ToggleGate(8);
        check(gate.accept(0, 6, 2) == null, "Contagem prematura");
        check(gate.accept(100, 6, 2) == null, "Contagem prematura");
        check(gate.accept(300, 6, 2) == 6, "Seis dedos não alternou");
        for (int time = 5000; time <= 6000; time += 100)
            check(gate.accept(time, 6, 2) == null, "Atraso da rede repetiu o comando");
        for (int time = 6100; time <= 6700; time += 100)
            check(gate.accept(time, 0, 2) == null, "Gesto desconhecido emitiu comando");
        for (int time = 6800; time <= 7300; time += 100)
            check(gate.accept(time, 6, 2) == null, "Gesto desconhecido rearmou o número");
        gate.accept(7400, 0, 0);
        gate.accept(7500, 0, 0);
        gate.accept(7600, 6, 2);
        check(gate.accept(7900, 6, 2) == null, "Perda breve da mão rearmou o número");
        gate.accept(8000, 0, 0);
        gate.accept(8200, 0, 0);
        gate.accept(8400, 0, 0);
        gate.accept(8500, 6, 2);
        gate.accept(8600, 6, 2);
        check(gate.accept(8800, 6, 2) == 6, "Retirar mãos não permitiu repetir");
        gate.accept(8900, 4, 1);
        gate.accept(9000, 4, 1);
        check(gate.accept(9200, 4, 1) == 4, "Não permitiu escolher outra luz");
        for (int time = 9300; time <= 9800; time += 100)
            check(gate.accept(time, 9, 2) == null, "Selecionou luz inexistente");

        // Returning to a number through another one, without removing the hands, must not toggle again
        // (e.g. a count flickering between 3 and 4 would otherwise toggle 3, 4, 3, 4...).
        ToggleGate flicker = new ToggleGate(8);
        flicker.accept(0, 3, 1);
        flicker.accept(100, 3, 1);
        check(flicker.accept(300, 3, 1) == 3, "Três dedos não alternou");
        flicker.accept(400, 4, 1);
        flicker.accept(500, 4, 1);
        check(flicker.accept(700, 4, 1) == 4, "Outra luz não alternou");
        for (int time = 800; time <= 2000; time += 100)
            check(flicker.accept(time, time % 400 < 200 ? 3 : 4, 1) == null, "Oscilação 3/4 repetiu comandos");
        for (int time = 2100; time <= 2600; time += 100)
            check(flicker.accept(time, 3, 1) == null, "Voltar a 3 sem retirar as mãos alternou de novo");
        for (int time = 2700; time <= 3100; time += 100) flicker.accept(time, 0, 0);
        flicker.accept(3200, 3, 1);
        flicker.accept(3300, 3, 1);
        check(flicker.accept(3500, 3, 1) == 3, "Retirar as mãos não liberou o 3");

        ButtonGate exclusive = new ButtonGate(8);
        exclusive.accept(0, 6, 2);
        exclusive.accept(100, 6, 2);
        check(exclusive.accept(300, 6, 2) == 6, "Seleção exclusiva falhou");
        check(exclusive.accept(5000, 6, 2) == null, "Atraso apagou a luz exclusiva");
        exclusive.accept(5100, 0, 0);
        exclusive.accept(5200, 0, 0);
        check(exclusive.accept(5400, 0, 0) == 0, "Remover mãos não apagou a luz exclusiva");
        System.out.println("ToggleGate OK: atraso, repetição, retirada das mãos e seleção exclusiva");
    }
}
