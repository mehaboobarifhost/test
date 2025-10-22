import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Paths;

public class XrayImporter {

    // Use the current v2 API for Xray Cloud
    private static final String XRAY_API_BASE_URL = "https://xray.cloud.getxray.app/api/v2";

    public static void main(String[] args) {
        // --- 1. SET YOUR DETAILS HERE ---
        String clientId = "YOUR_CLIENT_ID";
        String clientSecret = "YOUR_CLIENT_SECRET";
        String projectKey = "YOUR_PROJECT_KEY";
        String featureFilePath = "path/to/your/file.feature"; // e.g., "C:/Users/user/tests/login.feature"

        // Create a single reusable HttpClient
        HttpClient client = HttpClient.newHttpClient();

        try {
            // --- 2. Authenticate and get the token ---
            System.out.println("Authenticating with Xray...");
            String authToken = getAuthToken(client, clientId, clientSecret);
            System.out.println("Successfully authenticated.");

            // --- 3. Import the feature file using the token ---
            System.out.println("Importing feature file: " + featureFilePath);
            importFeatureFile(client, authToken, projectKey, featureFilePath);

        } catch (IOException | InterruptedException e) {
            System.err.println("An error occurred: " + e.getMessage());
            e.printStackTrace();
            // Ensure the application exits with a non-zero status code on error
            System.exit(1); 
        }
    }

    /**
     * Authenticates with the Xray API to get a bearer token.
     */
    private static String getAuthToken(HttpClient client, String clientId, String clientSecret) throws IOException, InterruptedException {
        String authUrl = XRAY_API_BASE_URL + "/authenticate";
        String jsonBody = String.format("{\"client_id\": \"%s\", \"client_secret\": \"%s\"}", clientId, clientSecret);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(authUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to authenticate. Status: " + response.statusCode() + " Body: " + response.body());
        }

        // The response body is the token string with quotes, so we remove them.
        return response.body().replace("\"", "");
    }

    /**
     * Imports a single .feature file to Xray.
     */
    private static void importFeatureFile(HttpClient client, String token, String projectKey, String filePath) throws IOException, InterruptedException {
        String importUrl = String.format("%s/import/feature?projectKey=%s", XRAY_API_BASE_URL, projectKey);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(importUrl))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "text/plain") // Use text/plain for a single feature file
                .POST(HttpRequest.BodyPublishers.ofFile(Paths.get(filePath)))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("\n--- Import Result ---");
        System.out.println("Status Code: " + response.statusCode());
        System.out.println("Response Body: " + response.body());
        System.out.println("---------------------\n");

        if (response.statusCode() != 200) {
            throw new IOException("Failed to import feature file.");
        }
    }
}
