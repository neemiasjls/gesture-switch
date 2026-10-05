package local.gestureswitch;

public final class ButtonGateProbe {
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        ButtonGate gate = new ButtonGate();
        check(gate.accept(0, 6, 2) == null, "Comando prematuro");
        check(gate.accept(100, 6, 2) == null, "Comando prematuro");
        check(gate.accept(200, 6, 2) == null, "Comando prematuro");
        check(gate.accept(300, 6, 2) == 6, "Botão 6 não foi selecionado");
        check(gate.accept(400, 6, 2) == null, "Seleção repetida");
        check(gate.accept(500, 5, 2) == null, "Mudança prematura");
        check(gate.accept(600, 6, 2) == null, "Oscilação mudou a seleção");
        check(gate.accept(700, 5, 2) == null, "Mudança prematura");
        check(gate.accept(800, 5, 2) == null, "Mudança prematura");
        check(gate.accept(1000, 5, 2) == 5, "6 para 5 falhou");
        check(gate.accept(1100, 4, 1) == null, "Mudança prematura");
        check(gate.accept(1200, 4, 1) == null, "Mudança prematura");
        check(gate.accept(1400, 4, 1) == 4, "5 para 4 falhou");
        check(gate.accept(1500, 0, 0) == null, "Desligamento prematuro");
        check(gate.accept(1600, 0, 0) == null, "Desligamento prematuro");
        check(gate.accept(1800, 0, 0) == 0, "Zero não desligou");

        ButtonGate invalid = new ButtonGate();
        for (int time = 0; time <= 2000; time += 100)
            check(invalid.accept(time, 7, 1) == null, "Botão 7 exige duas mãos");
        ButtonGate eightLights = new ButtonGate(8);
        for (int time = 0; time <= 1000; time += 100)
            check(eightLights.accept(time, 9, 2) == null, "Nove dedos não deve selecionar luz inexistente");
        System.out.println("ButtonGate OK");
    }
}
