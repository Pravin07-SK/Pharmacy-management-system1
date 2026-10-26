
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class SupabaseTest {
    public static void main(String[] args) throws Exception {
        String url =
            "https://iaznhmwvnirlbfhzbtll.supabase.co/rest/v1/medicines?select=*";

        // Replace this placeholder with your Supabase
        // publishable key from Project Settings -> API Keys.
        String apiKey = "sb_publishable_ysxV07RQDwGvx_-zv4o74g_inWHhrZR";

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("apikey", apiKey)
            .header("Authorization", "Bearer " + apiKey)
            .GET()
            .build();

        HttpResponse<String> response = client.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );

        System.out.println("HTTP Status: " + response.statusCode());
        System.out.println("Supabase response: " + response.body());
    }
}
