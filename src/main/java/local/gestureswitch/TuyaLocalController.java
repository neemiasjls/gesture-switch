package local.gestureswitch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Starts the short-lived local Tuya helper only after explicit opt-in. */
final class TuyaLocalController implements LightController {
    private final Path config;

    TuyaLocalController(PrivateConfig privateConfig) {
        config = privateConfig.path();
        if (!Files.isRegularFile(config))
            throw new IllegalArgumentException("Arquivo de configuração local não encontrado: " + config);
    }

    public void set(boolean on) throws Exception {
        String python = System.getenv().getOrDefault("GESTURE_PYTHON", ".venv\\Scripts\\python.exe");
        Process process = new ProcessBuilder(List.of(python, "-u", "vision/tuya_local.py", "--config",
                config.toString(), "--set", on ? "on" : "off"))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start();
        if (process.waitFor() != 0)
            throw new IOException("Falha ao comandar os três interruptores; confira o estado no app.");
    }

    public String name() { return "Tuya local (3 interruptores da sala)"; }
}
