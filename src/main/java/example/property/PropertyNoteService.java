package example.property;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.EmbeddingCreateParams;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PropertyNoteService {
  private final String collection;
  private final String baseUrl;
  private final String key;
  private final OpenAIClient ai;
  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  public PropertyNoteService(@Value("${property.collection}") String collection,
      @Value("${infrai.base-url}") String baseUrl, @Value("${infrai.api-key}") String key) {
    this.collection = collection;
    this.baseUrl = baseUrl;
    this.key = key;
    this.ai = OpenAIOkHttpClient.builder().apiKey(key).baseUrl(baseUrl).build();
  }

  public record Note(String id, String tenantId, String kind, String transcript) {}

  public static String category(String transcript) {
    String lower = transcript.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("leak") || lower.contains("water") || lower.contains("flood")) return "maintenance";
    if (lower.contains("inspection") || lower.contains("inspect")) return "inspection-reminder";
    return "tenant-document";
  }

  private List<Float> embedding(String text) {
    return ai.embeddings().create(EmbeddingCreateParams.builder().model("auto").input(text).build())
        .data().get(0).embedding();
  }

  private JsonNode vector(String path, Map<String, ?> body) throws Exception {
    String payload = json.writeValueAsString(body);
    for (int attempt = 0; attempt < 4; attempt++) {
      HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
          .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode envelope = json.readTree(response.body());
      if (response.statusCode() == 429) {
        String retryAfter = response.headers().firstValue("Retry-After").orElse("");
        long delay;
        try { delay = Math.min(30_000L, Long.parseLong(retryAfter) * 1000L); }
        catch (NumberFormatException ignored) { delay = 500L << attempt; }
        if (attempt < 3) { Thread.sleep(delay); continue; }
      }
      if (!envelope.path("ok").asBoolean(false)) {
        JsonNode error = envelope.path("error");
        throw new RemoteRejection(response.statusCode(), error.path("code").asText("REMOTE_REJECTION"),
            error.path("message").asText("Request rejected"));
      }
      if (response.statusCode() >= 500) throw new IllegalStateException("Remote request failed");
      return envelope.path("data");
    }
    throw new IllegalStateException("Retry exhausted");
  }

  @PostMapping("/notes")
  public Map<String, String> index(@RequestBody Note note) throws Exception {
    if (note.id() == null || note.id().isBlank() || note.transcript() == null || note.transcript().isBlank()
        || note.tenantId() == null || note.tenantId().isBlank()) throw new IllegalArgumentException("id, tenantId and transcript are required");
    String category = category(note.transcript());
    vector("/vector/upsert", Map.of("collection", collection, "vectors", List.of(Map.of(
        "id", note.id(), "embedding", embedding(note.transcript()), "metadata", Map.of(
            "tenantId", note.tenantId(), "kind", note.kind() == null ? "voice-note" : note.kind(),
            "category", category, "transcript", note.transcript())))));
    return Map.of("id", note.id(), "category", category);
  }

  @GetMapping("/notes/search")
  public JsonNode search(@RequestParam String tenantId, @RequestParam String query) throws Exception {
    if (tenantId.isBlank() || query.isBlank()) throw new IllegalArgumentException("tenantId and query are required");
    return vector("/vector/query", Map.of("collection", collection, "embedding", embedding(query),
        "top_k", 5, "filter", Map.of("tenantId", tenantId), "include_metadata", true));
  }

  @ExceptionHandler(RemoteRejection.class)
  public ResponseEntity<Map<String, String>> rejected(RemoteRejection error) {
    int status = error.status >= 400 && error.status < 500 ? error.status : 502;
    return ResponseEntity.status(status).body(Map.of("code", error.code, "message", error.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", error.getMessage()));
  }

  private static class RemoteRejection extends RuntimeException {
    final int status;
    final String code;
    RemoteRejection(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }
  }
}
