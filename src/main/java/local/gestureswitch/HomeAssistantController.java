package local.gestureswitch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Optional bridge after the switch is confirmed accessible in Home Assistant. */
final class HomeAssistantController implements LightController {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String base;
    private final String token;
    private final List<String> entities;

    HomeAssistantController(PrivateConfig config) {
        base = config.required("homeassistant", "url").replaceAll("/+$", "");
        token = config.required("homeassistant", "token");
        entities = Arrays.stream(config.required("homeassistant", "entity_ids").split(",", -1))
                .map(String::trim).toList();
        if (entities.size() != 3 || entities.stream().distinct().count() != 3 ||
                entities.stream().anyMatch(entity -> !entity.matches("(?:light|switch)\\.[a-z0-9_]+")))
            throw new IllegalArgumentException("homeassistant.entity_ids deve conter 3 entidades únicas light.* ou switch.*");
        URI url = URI.create(base);
        if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme())) || url.getHost() == null)
            throw new IllegalArgumentException("homeassistant.url deve ser uma URL HTTP(S) válida");
    }

    public void set(boolean on) throws Exception {
        String action = on ? "turn_on" : "turn_off";
        for (String entity : entities) {
            String domain = entity.substring(0, entity.indexOf('.'));
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/services/" + domain + "/" + action))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"entity_id\":\"" + entity + "\"}"))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2)
                throw new IllegalStateException("Home Assistant respondeu HTTP " + response.statusCode() + " para " + entity);
            System.out.println("[HOME ASSISTANT] Comando aceito para " + entity + ": " + (on ? "ACENDER" : "APAGAR"));
        }
    }

    public String name() { return "Home Assistant: " + String.join(", ", entities); }

}
