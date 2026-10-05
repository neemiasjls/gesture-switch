package local.gestureswitch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Controls selected SmartThings switch components without changing their pairing. */
final class SmartThingsController implements GroupLights {
    private static final String API = "https://api.smartthings.com/v1/devices/";
    private static final Pattern SWITCH_VALUE = Pattern.compile("\\\"value\\\"\\s*:\\s*\\\"(on|off)\\\"");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String token;
    private final List<Target> targets;
    private final List<Target> buttons;
    private final List<Target> lights;

    SmartThingsController(PrivateConfig config) {
        token = config.required("smartthings", "token");
        targets = parseTargets(config.required("smartthings", "targets"), "targets");
        String configuredButtons = config.get("smartthings", "buttons", "");
        buttons = configuredButtons.isBlank() ? List.of() : parseTargets(configuredButtons, "buttons");
        String configuredLights = config.get("smartthings", "lights", "");
        lights = configuredLights.isBlank() ? List.of() : parseTargets(configuredLights, "lights");
        if (!buttons.containsAll(lights))
            throw new IllegalArgumentException("smartthings.lights deve usar alvos de smartthings.buttons");
    }

    private static List<Target> parseTargets(String configured, String field) {
        List<Target> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : configured.split(",")) {
            String[] parts = raw.trim().split("\\|", -1);
            if (parts.length != 2 || !safeId(parts[0]) || !safeId(parts[1]))
                throw new IllegalArgumentException("Use smartthings." + field
                        + "=DEVICE_ID|COMPONENT_ID,... no arquivo privado");
            String key = parts[0] + "|" + parts[1];
            if (!seen.add(key)) throw new IllegalArgumentException("Alvo SmartThings duplicado no arquivo privado");
            result.add(new Target(parts[0], parts[1]));
        }
        return List.copyOf(result);
    }

    private static boolean safeId(String value) {
        return !value.isBlank() && value.matches("[A-Za-z0-9_-]+");
    }

    @Override
    public int generalCount() { return targets.size(); }

    @Override
    public void setGeneral(boolean on, List<Integer> positions) throws Exception {
        String description = positions.size() == targets.size() ? "canais" : "painéis pendentes";
        send(targets, positions, on, description);
    }

    void setButton(int number, boolean on) throws Exception {
        if (buttons.size() != 10)
            throw new IllegalStateException("Configure os 10 smartthings.buttons no arquivo privado");
        if (number < 1 || number > buttons.size())
            throw new IllegalArgumentException("Botão fora da sequência 1-10");
        send(List.of(buttons.get(number - 1)), List.of(0), on, "botão " + number);
    }

    boolean hasButtons() { return buttons.size() == 10; }

    boolean hasLights() { return !lights.isEmpty() && lights.size() <= 10; }

    @Override
    public int lightCount() { return lights.size(); }

    @Override
    public void setLight(int number, boolean on) throws Exception {
        if (!hasLights()) throw new IllegalStateException("Configure smartthings.lights no arquivo privado");
        if (number < 1 || number > lights.size())
            throw new IllegalArgumentException("Luz fora da sequência configurada");
        send(List.of(lights.get(number - 1)), List.of(0), on, "luz " + number);
    }

    @Override
    public boolean isLightOn(int number) throws Exception {
        if (!hasLights() || number < 1 || number > lights.size())
            throw new IllegalArgumentException("Luz fora da sequência configurada");
        Target target = lights.get(number - 1);
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + target.deviceId
                        + "/components/" + target.component + "/capabilities/switch/status"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Matcher value = SWITCH_VALUE.matcher(response.body());
        if (response.statusCode() != 200)
            throw new IllegalStateException("Falha ao ler a luz " + number + ": " + httpFailure(response.statusCode()));
        if (!value.find())
            throw new IllegalStateException("Não foi possível ler o estado da luz " + number);
        return "on".equals(value.group(1));
    }

    boolean toggleLight(int number) throws Exception {
        boolean turnOn = !isLightOn(number);
        setLight(number, turnOn);
        return turnOn;
    }

    /** Sends to base[positions]; failures report the 0-based positions, never the IDs. */
    private void send(List<Target> base, List<Integer> positions, boolean on, String description) throws Exception {
        String command = on ? "on" : "off";
        int accepted = 0;
        List<Integer> failed = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (int position : positions) {
            if (position < 0 || position >= base.size())
                throw new IllegalArgumentException("Posição de alvo SmartThings inválida");
            Target target = base.get(position);
            String body = "{\"commands\":[{\"component\":\"" + target.component +
                    "\",\"capability\":\"switch\",\"command\":\"" + command +
                    "\",\"arguments\":[]}]}";
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(API + target.deviceId + "/commands"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"ACCEPTED\"")) accepted++;
                else {
                    failed.add(position);
                    reasons.add(httpFailure(response.statusCode()));
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            } catch (Exception error) {
                failed.add(position);
                reasons.add("falha de conexão ou tempo de espera esgotado");
            }
        }
        System.out.println("[SMARTTHINGS] Comando " + command + " aceito para " + accepted + "/" + positions.size()
                + " " + description + ". Confirme o estado no app ou nas luzes.");
        if (!failed.isEmpty()) {
            List<Integer> shown = failed.stream().map(position -> position + 1).toList();
            throw new TargetFailure(failed, "Falha nos alvos SmartThings de posição " + shown
                    + ": " + String.join("; ", new java.util.LinkedHashSet<>(reasons)) + ".");
        }
    }

    private static String httpFailure(int status) {
        return switch (status) {
            case 401 -> "token inválido ou expirado; atualize .secrets/config.ini";
            case 403 -> "acesso negado; confira as permissões do token";
            case 404 -> "canal não encontrado; confira o vínculo e a configuração privada";
            case 429 -> "limite de solicitações atingido; aguarde antes de repetir o gesto";
            default -> "resposta HTTP " + status + " do SmartThings";
        };
    }

    @Override
    public String name() { return "SmartThings"; }

    private record Target(String deviceId, String component) {}
}
