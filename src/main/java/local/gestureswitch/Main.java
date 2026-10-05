package local.gestureswitch;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

public final class Main {
    public static void main(String[] args) throws Exception {
        PrivateConfig config = PrivateConfig.load();
        String backend = System.getenv().getOrDefault("GESTURE_BACKEND",
                config.get("app", "backend", "simulation")).toLowerCase();
        LightController lights = switch (backend) {
            case "simulation" -> new SimulatedController();
            case "homeassistant" -> new HomeAssistantController(config);
            case "tuya_local" -> new TuyaLocalController(config);
            case "smartthings" -> new SmartThingsController(config);
            default -> throw new IllegalArgumentException("GESTURE_BACKEND desconhecido: " + backend);
        };
        if (args.length == 2 && "--command".equals(args[0])) {
            if (!"on".equals(args[1]) && !"off".equals(args[1]))
                throw new IllegalArgumentException("Use --command on ou --command off");
            lights.set("on".equals(args[1]));
            return;
        }
        if (args.length == 3 && "--button-command".equals(args[0])) {
            if (!(lights instanceof SmartThingsController smart))
                throw new IllegalStateException("Comando por botão exige backend SmartThings");
            int number = Integer.parseInt(args[1]);
            if (!"on".equals(args[2]) && !"off".equals(args[2]))
                throw new IllegalArgumentException("Use --button-command NUMERO on|off");
            smart.setButton(number, "on".equals(args[2]));
            return;
        }
        if (args.length == 2 && ("--light-state".equals(args[0]) || "--light-toggle".equals(args[0]))) {
            if (!(lights instanceof SmartThingsController smart))
                throw new IllegalStateException("Leitura por luz exige backend SmartThings");
            int number = Integer.parseInt(args[1]);
            if ("--light-state".equals(args[0]))
                System.out.println("Luz " + number + ": " + (smart.isLightOn(number) ? "on" : "off"));
            else
                System.out.println("Luz " + number + ": " + (smart.toggleLight(number) ? "on" : "off"));
            return;
        }
        boolean fromStdin = args.length == 1 && "--stdin".equals(args[0])
                || args.length == 3 && "--stdin".equals(args[0]) && "--mode".equals(args[1]);
        String initialMode = args.length == 2 && "--mode".equals(args[0]) ? args[1]
                : args.length == 3 && fromStdin ? args[2]
                : fromStdin ? "all" : "general";
        if (!"general".equals(initialMode) && !"all".equals(initialMode) && !"buttons".equals(initialMode))
            throw new IllegalArgumentException("Use --mode general, all ou buttons");
        GroupLights groups = lights instanceof SmartThingsController smart ? (smart.hasLights() ? smart : null)
                : lights instanceof SimulatedController simulated ? simulated : null;
        boolean lightsReady = groups != null;
        int maxLights = lightsReady ? groups.lightCount() : 8;
        if ("buttons".equals(initialMode) && !lightsReady)
            throw new IllegalStateException("Modo Por luz exige smartthings.lights no arquivo privado");
        Process worker = null;
        BufferedReader observations;
        if (fromStdin) {
            observations = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        } else {
            String python = System.getenv().getOrDefault("GESTURE_PYTHON", ".venv\\Scripts\\python.exe");
            String model = System.getenv().getOrDefault("GESTURE_MODEL", "models\\gesture_recognizer.task");
            String camera = System.getenv().getOrDefault("GESTURE_CAMERA", "0");
            List<String> command = new ArrayList<>(List.of(python, "-u", "vision/gesture_camera.py",
                    "--model", model, "--camera", camera, "--mode", initialMode,
                    "--max-lights", Integer.toString(maxLights)));
            if (System.getenv("GESTURE_MAX_FRAMES") != null) {
                command.add("--max-frames");
                command.add(System.getenv("GESTURE_MAX_FRAMES"));
            }
            if ("1".equals(System.getenv("GESTURE_HEADLESS"))) command.add("--headless");
            worker = new ProcessBuilder(command).directory(Path.of("").toAbsolutePath().toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            Process child = worker;
            Runtime.getRuntime().addShutdownHook(new Thread(child::destroy));
            observations = new BufferedReader(new InputStreamReader(worker.getInputStream(), StandardCharsets.UTF_8));
        }
        Status status = new Status(worker);
        // Observation time (camera milliseconds) drives the short memory of commanded states
        // and the spacing of panel retries in "Por luz"; it is monotonic and testable via --stdin.
        long[] clockNow = {0};
        LongSupplier clock = () -> clockNow[0];
        TrackedLights tracked = lightsReady ? new TrackedLights(groups, clock) : null;
        ExclusiveLights exclusive = lightsReady ? new ExclusiveLights(tracked, clock) : null;
        GestureGate gate = new GestureGate();
        ButtonGate buttonGate = new ButtonGate(maxLights);
        ToggleGate toggleGate = new ToggleGate(maxLights);
        boolean generalNeedsNeutral = false;
        long generalNeutralSince = -1;
        long lastReleaseEpoch = -1;
        String currentMode = initialMode;
        if (exclusive != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { exclusive.close(); }
                catch (Exception error) { System.err.println("Falha ao apagar a luz do Por luz ao sair."); }
            }));
        }
        boolean diagnostics = "1".equals(System.getenv("GESTURE_DIAGNOSTICS"));
        String lastLabel = "";
        long lastDiagnostic = Long.MIN_VALUE / 2;
        System.out.println("Gesture Switch: " + lights.name() + ". Geral: 1-" + maxLights
                + " dedos alternam luz; punho apaga tudo; 10 dedos ligam tudo.");
        System.out.println("Todas luzes: mão aberta liga tudo, punho apaga tudo. Por luz: o número escolhe uma luz por vez.");
        System.out.println("Clique no modo na câmera ou pressione M; Q/ESC sai.");
        try {
          // Inside the try: a failed "all off" at start no longer aborts the program.
          if ("buttons".equals(currentMode)) enterButtons(exclusive, status, worker, observations);
          String line;
          while ((line = observations.readLine()) != null) {
            String[] fields = line.split("\\t");
            if (fields.length < 4 || fields.length > 6) continue;
            try {
                long timestamp = Long.parseLong(fields[0]);
                String label = fields[1];
                double score = Double.parseDouble(fields[2]);
                int hands = Integer.parseInt(fields[3]);
                String mode = fields.length >= 5 ? fields[4] : initialMode;
                if (!"general".equals(mode) && !"all".equals(mode) && !"buttons".equals(mode)) continue;
                clockNow[0] = timestamp;
                // The camera retains this counter across every frame, including discarded ones.
                // A release during a slow API call must still unlock the next individual gesture.
                if (fields.length == 6) {
                    long releaseEpoch = Long.parseLong(fields[5]);
                    if (lastReleaseEpoch >= 0 && releaseEpoch > lastReleaseEpoch) {
                        toggleGate = new ToggleGate(maxLights);
                        generalNeedsNeutral = false;
                        generalNeutralSince = -1;
                    }
                    lastReleaseEpoch = releaseEpoch;
                }
                if (!mode.equals(currentMode)) {
                    if ("buttons".equals(currentMode) && exclusive != null) {
                        // One attempt only: a failure must not block the switch nor repeat on every frame.
                        try { exclusive.leave(); }
                        catch (InterruptedException interrupted) { throw interrupted; }
                        catch (Exception error) { status.error(describe(error)); }
                    }
                    currentMode = mode;
                    gate = new GestureGate();
                    buttonGate = new ButtonGate(maxLights);
                    toggleGate = new ToggleGate(maxLights);
                    generalNeedsNeutral = false;
                    generalNeutralSince = -1;
                    System.out.println("[MODO] " + switch (mode) {
                        case "general" -> "Geral";
                        case "all" -> "Todas luzes";
                        default -> "Por luz";
                    });
                    if ("buttons".equals(mode) && exclusive != null) enterButtons(exclusive, status, worker, observations);
                    else drain(worker, observations);
                }
                if (diagnostics && (!label.equals(lastLabel) || timestamp - lastDiagnostic >= 1000)) {
                    System.out.printf("[VISÃO] %s %.2f | mãos=%d%n", label, score, hands);
                    lastLabel = label;
                    lastDiagnostic = timestamp;
                }
                if ("buttons".equals(currentMode)) {
                    int observed = label.startsWith("Button_") ? Integer.parseInt(label.substring(7)) : 0;
                    Integer next = buttonGate.accept(timestamp, observed, hands);
                    if (next != null && exclusive != null) {
                        Exception problem = null;
                        try { exclusive.select(next); }
                        catch (InterruptedException interrupted) { throw interrupted; }
                        catch (Exception error) { problem = error; }
                        String warning = exclusive.exclusive() ? ""
                                : " | " + exclusive.pendingPanelCount() + " painel(is) sem confirmar o apagamento";
                        status.info("[SELEÇÃO] Luz " + (next == 0 ? "nenhuma" : next) + warning);
                        if (problem != null) status.error(describe(problem));
                        drain(worker, observations);
                    } else if (next != null) {
                        status.error("Modo Por luz indisponível: configure smartthings.lights.");
                    }
                } else if ("general".equals(currentMode)) {
                    int observed = label.startsWith("Button_") ? Integer.parseInt(label.substring(7)) : 0;
                    if (generalNeedsNeutral) {
                        if (hands == 0) {
                            if (generalNeutralSince < 0) generalNeutralSince = timestamp;
                            if (timestamp - generalNeutralSince >= 400) {
                                generalNeedsNeutral = false;
                                toggleGate = new ToggleGate(maxLights);
                            }
                        } else generalNeutralSince = -1;
                    }
                    Integer selected = generalNeedsNeutral ? null : toggleGate.accept(timestamp, observed, hands);
                    if (selected != null && selected > 0 && tracked != null) {
                        try {
                            TrackedLights.Toggle result = tracked.toggle(selected);
                            status.info("[GERAL] Luz " + selected + (result.on() ? " ligada." : " desligada.")
                                    + (result.remembered() ? " (pelo último comando)" : "")
                                    + " Tire as mãos por 0,4 s para repetir.");
                        } finally {
                            drain(worker, observations);
                        }
                    }
                    GestureGate.Command action = gate.accept(timestamp, label, score, hands);
                    if (action != null) {
                        generalNeedsNeutral = true;
                        generalNeutralSince = -1;
                        toggleGate = new ToggleGate(maxLights);
                        setAll(action == GestureGate.Command.ON, tracked, lights, status, "[GERAL]", worker, observations);
                        status.info("[GERAL] Comando aceito. Tire as mãos por 0,4 s para escolher uma luz.");
                    }
                } else {
                    GestureGate.Command action = gate.accept(timestamp, label, score, hands);
                    if (action != null)
                        setAll(action == GestureGate.Command.ON, tracked, lights, status, "[TODAS LUZES]",
                                worker, observations);
                }
            } catch (NumberFormatException badLine) {
                System.err.println("Observação inválida recebida da câmera.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception commandError) {
                status.error(describe(commandError));
                drain(worker, observations);
            }
          }
          if (worker != null && worker.waitFor() != 0) throw new IllegalStateException("O reconhecimento terminou com erro.");
        } finally {
            try {
                if (exclusive != null) {
                    try { exclusive.close(); }
                    catch (Exception error) { System.err.println("[ERRO] " + describe(error)); }
                }
            } finally {
                status.close();
                if (worker != null) worker.destroy();
                observations.close();
            }
        }
    }

    private static void enterButtons(ExclusiveLights exclusive, Status status, Process worker,
                                     BufferedReader observations) throws Exception {
        try {
            exclusive.enter();
            status.info("[POR LUZ] Todas as luzes apagadas; escolha uma luz.");
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception error) {
            status.error(describe(error));
        } finally {
            drain(worker, observations);
        }
    }

    private static void setAll(boolean on, TrackedLights tracked, LightController lights, Status status,
                               String prefix, Process worker, BufferedReader observations) throws Exception {
        try {
            if (tracked != null) tracked.set(on);
            else lights.set(on);
            status.info(prefix + (on ? " Ligar todas: comando aceito." : " Apagar todas: comando aceito."));
        } finally {
            drain(worker, observations);
        }
    }

    /** Discards observations queued during a command, so stale frames do not trigger another one. */
    private static void drain(Process worker, BufferedReader observations) throws IOException {
        if (worker != null) while (observations.ready()) observations.readLine();
    }

    private static String describe(Exception error) {
        return error instanceof IllegalStateException && error.getMessage() != null ? error.getMessage()
                : "Falha de comunicação ao enviar o comando.";
    }

    /** Prints results and mirrors them to the camera window (worker stdin); a closed window is ignored. */
    static final class Status {
        private Writer toWorker;

        Status(Process worker) {
            toWorker = worker == null ? null
                    : new BufferedWriter(new OutputStreamWriter(worker.getOutputStream(), StandardCharsets.UTF_8));
        }

        void info(String text) {
            System.out.println(text);
            send("ok", text);
        }

        void error(String text) {
            System.err.println("[ERRO] " + text);
            send("error", text);
        }

        private synchronized void send(String level, String text) {
            if (toWorker == null) return;
            try {
                toWorker.write("STATUS\t" + level + "\t" + text.replaceAll("[\\t\\r\\n]+", " ") + "\n");
                toWorker.flush();
            } catch (IOException closed) {
                toWorker = null;
            }
        }

        synchronized void close() {
            if (toWorker == null) return;
            try { toWorker.close(); } catch (IOException ignored) { }
            toWorker = null;
        }
    }
}
