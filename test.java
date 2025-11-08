import okhttp3.*;
import okhttp3.JavaNetCookieJar;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.InetSocketAddress; // Import for proxy
import java.net.Proxy; // Import for proxy
import java.net.URI; // <-- IMPORT THIS
import java.net.URISyntaxException; // <-- IMPORT THIS
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

// --- IMPORTS FOR SSL BYPASS ---
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
// --- END IMPORTS ---


public class AprimoLogin {

    // --- !! UPDATE THESE !! ---
    private static final String TEST_USERNAME = "";
    private static final String TEST_PASSWORD = "";
    // --- !! UPDATE THESE !! ---

    private static final String BASE_URL = "https://company-sb1.aprimo.com";
    private static final String LOGIN_PATH = "/login/Account/Login";
    private static final String TOKEN_PATH = "/login/connect/token"; // <-- Token Endpoint
    private static final String CLIENT_ID = "MarketingOps";
    private static final String REDIRECT_URI = "https://company-sb1.aprimo.com/MarketingOps/oidc/signin-callback.html";

    // --- Static CookieManager to hold cookies for transfer ---
    private static CookieManager cookieManager;

    // --- NEW: Static fields to hold verifier and state ---
    private static String lastCodeVerifier;
    private static String lastState;


    /**
     * --- NEW METHOD ---
     * Gets the final API Access Token by performing the full OIDC flow.
     *
     * @return The `access_token` string.
     * @throws Exception if any step fails
     */
    public static String getApiAccessToken() throws Exception {
        System.out.println("--- Starting Full API Token Flow ---");

        // Step 1: Run the browser login flow to get the auth code
        String finalUrl = getLoginCallbackUrl();

        // Step 2: Extract the code from the final URL
        String code = extractParamFromUrl(finalUrl, "code");
        String state = extractParamFromUrl(finalUrl, "state");

        if (code == null) {
            throw new RuntimeException("Could not extract 'code' from final URL.");
        }

        // Step 3: Validate the state to prevent- CSRF
        if (!lastState.equals(state)) {
            throw new RuntimeException("OIDC state mismatch. Possible security issue.");
        }
        System.out.println("Auth code extracted. Exchanging for token...");

        // Step 4: Build a new client (or re-use) for the token exchange.
        // We'll build a new one to show the proxy/SSL setup is needed here too.
        OkHttpClient.Builder clientBuilder = createUnsafeOkHttpClientBuilder();

        // --- Proxy setup (copy from getLoginCallbackUrl if needed) ---
        // final String PROXY_HOST = "your.office.proxy.com";
        // final int PROXY_PORT = 8080;
        // Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(PROXY_HOST, PROXY_PORT));
        // clientBuilder.proxy(proxy);
        // ... (add authenticator if needed) ...
        // --- End Proxy Setup ---

        OkHttpClient tokenClient = clientBuilder.build();

        // Step 5: Build the token request form
        FormBody tokenForm = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", CLIENT_ID)
                .add("code_verifier", lastCodeVerifier) // <-- This is the verifier from the first step
                .build();

        String tokenUrl = BASE_URL + TOKEN_PATH;
        Request tokenRequest = new Request.Builder()
                .url(tokenUrl)
                .post(tokenForm)
                .build();

        // Step 6: Execute the request
        Response tokenResponse = tokenClient.newCall(tokenRequest).execute();
        String jsonBody = tokenResponse.body().string();

        if (!tokenResponse.isSuccessful()) {
            System.err.println("Token exchange failed with code: " + tokenResponse.code());
            System.err.println(jsonBody);
            throw new RuntimeException("Failed to exchange code for token.");
        }

        // Step 7: Parse the JSON response and get the token
        String accessToken = extractJsonValue(jsonBody, "access_token");
        if (accessToken == null) {
            System.err.println("Could not find 'access_token' in response.");
            System.err.println(jsonBody);
            throw new RuntimeException("Access token not found in response.");
        }

        System.out.println("--- Access Token Acquired! ---");
        return accessToken;
    }


    /**
     * This method performs the programmatic login and returns the
     * final callback URL containing the authorization code.
     *
     * @return The final URL to pass to driver.get()
     * @throws Exception if any step fails
     */
    public static String getLoginCallbackUrl() throws Exception {

        // --- !! PROXY CONFIGURATION FOR OFFICE NETWORKS !! ---
        // Find these values in your office browser's network settings or from IT
        //
        // 1. SET YOUR PROXY HOST AND PORT
        // final String PROXY_HOST = "your.office.proxy.com"; // <--- 1. EDIT THIS
        // final int PROXY_PORT = 8080; // <--- 2. EDIT THIS

        // 2. UNCOMMENT THIS LINE to enable the proxy
        // Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(PROXY_HOST, PROXY_PORT));
        
        // 3. If your proxy needs a username/password, uncomment this as well:
        // Authenticator proxyAuthenticator = new Authenticator() {
        //     @Override public Request authenticate(Route route, Response response) throws java.io.IOException {
        //         String credential = Credentials.basic("your-proxy-username", "your-proxy-password"); // <--- 4. EDIT THIS
        //         return response.request().newBuilder()
        //                 .header("Proxy-Authorization", credential)
        //                 .build();
        //     }
        // };
        // --- !! END PROXY CONFIG !! ---


        // Step 1: Setup persistent cookie jar
        cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        CookieJar cookieJar = new JavaNetCookieJar(cookieManager);

        // This client will hold our cookies and bypass SSL validation
        OkHttpClient.Builder clientBuilder = createUnsafeOkHttpClientBuilder();
        
        OkHttpClient client = clientBuilder
                .cookieJar(cookieJar)
                // 4. UNCOMMENT THIS to apply the proxy
                // .proxy(proxy)
                // 5. UNCOMMENT THIS if you need proxy authentication
                // .proxyAuthenticator(proxyAuthenticator)
                .build();

        // This client will be used to catch 302 redirects
        OkHttpClient noRedirectClient = client.newBuilder()
                .followRedirects(false)
                .build();

        // Step 2: Generate PKCE and build the two URLs we need
        // --- MODIFIED: Store verifier and state in static fields ---
        lastCodeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(lastCodeVerifier);
        lastState = "your-own-random-state-" + System.currentTimeMillis();
        // --- END MODIFICATION ---

        // 1. This is the OIDC URL we need to hit *after* we are logged in
        HttpUrl authorizeCallbackUrl = new HttpUrl.Builder()
                .scheme("https")
                .host("company-sb1.aprimo.com")
                .encodedPath("/login/connect/authorize/callback")
                .addQueryParameter("client_id", CLIENT_ID)
                .addQueryParameter("redirect_uri", REDIRECT_URI)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("scope", "api ui openid api-internal legacy-api filestore-access")
                .addQueryParameter("state", lastState) // Use static field
                .addQueryParameter("code_challenge", codeChallenge) // Use challenge from static verifier
                .addQueryParameter("code_challenge_method", "S256")
                .build();

        // 2. This is the path/query we pass to the login page
        String returnUrlValue = authorizeCallbackUrl.encodedPath() + "?" + authorizeCallbackUrl.encodedQuery();

        // 3. This is the Login Page URL
        HttpUrl authUrl = HttpUrl.parse(BASE_URL + LOGIN_PATH)
                .newBuilder()
                .addQueryParameter("ReturnUrl", returnUrlValue)
                .addQueryParameter("acr_values", "loginEntry:0")
                .build();

        System.out.println("Step 2: GET Login Page at: " + authUrl);

        // Step 3: GET the login page to get form tokens
        // THIS IS THE LINE THAT IS FAILING
        Request getLoginRequest = new Request.Builder().url(authUrl).build();
        Response loginPageResponse = client.newCall(getLoginRequest).execute();
        String loginPageHtml = loginPageResponse.body().string();
        Document loginDoc = Jsoup.parse(loginPageHtml, authUrl.toString());

        // Step 4: Parse and POST Login Form
        Element loginForm = loginDoc.select("form").first();
        Element loginButton = loginDoc.select("button[name=loginButton]").first();

        if (loginButton == null) {
            throw new RuntimeException("Could not find login button with name 'loginButton'");
        }

        String postUrl = loginButton.absUrl("formaction");
        System.out.println("Step 4: POSTing credentials to: " + postUrl);

        FormBody.Builder formBuilder = new FormBody.Builder();

        // Add all hidden fields from the form
        for (Element input : loginForm.select("input[type=hidden]")) {
            formBuilder.add(input.attr("name"), input.attr("value"));
        }

        // Add credentials
        formBuilder.add("Username", TEST_USERNAME);
        formBuilder.add("Password", TEST_PASSWORD);
        formBuilder.add("loginButton", "login");

        Request postLoginRequest = new Request.Builder()
                .url(postUrl)
                .post(formBuilder.build())
                .build();

        Response postLoginResponse = noRedirectClient.newCall(postLoginRequest).execute();
        

        if (!postLoginResponse.isRedirect()) {
            String errorPageHtml = "Could not read error page body.";
            // Try to read the body, but be careful
            try {
                errorPageHtml = postLoginResponse.body().string();
            } catch (Exception e) {
                // Ignore, body might be empty
            }
            System.err.println("--- LOGIN FAILED: GOT " + postLoginResponse.code() + " ---");
            System.err.println(errorPageHtml);
            System.err.println("--------------------------------");
            throw new RuntimeException("Login POST failed. Expected a 302 redirect, got: " + postLoginResponse.code());
        }
        postLoginResponse.body().close();
        System.out.println("Step 5: Login POST successful. Session cookies are set.");

        // Step 6: Go directly to the authorize URL.
        System.out.println("Step 6: Making direct GET request to authorize URL: " + authorizeCallbackUrl);
        Request authorizeRequest = new Request.Builder()
                .url(authorizeCallbackUrl)
                .get()
                .build();

        Response finalRedirectResponse = noRedirectClient.newCall(authorizeRequest).execute();
        String callbackUrlWithCode = finalRedirectResponse.header("Location");
        finalRedirectResponse.body().close();

        System.out.println("Step 7: Got final redirect: " + callbackUrlWithCode);

        // Step 8: Final check and return
        if (callbackUrlWithCode == null || !callbackUrlWithCode.contains("code=")) {
            throw new RuntimeException("Login flow failed. Final URL did not contain 'code=': " + callbackUrlWithCode);
        }

        String finalUrl = authorizeCallbackUrl.resolve(callbackUrlWithCode).toString();

        System.out.println("Final URL for handoff: " + finalUrl);
        System.out.println("\nSUCCESS! Programmatic login complete.");
        return finalUrl;
    }

    /**
     * Helper to get the CookieStore after login is complete.
     */
    public static CookieStore getCookieStore() {
        if (cookieManager == null) {
            throw new IllegalStateException("getLoginCallbackUrl() must be called first.");
        }
        return cookieManager.getCookieStore();
    }


    // --- PKCE Helper Methods ---

    public static String generateCodeVerifier() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[64];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String generateCodeChallenge(String verifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    // --- NEW METHOD: SSL BYPASS ---

    /**
     * Creates an OkHttpClient.Builder that bypasses SSL certificate checks.
     * WARNING: This is insecure and should only be used for testing in a
     * trusted corporate environment.
     */
    private static OkHttpClient.Builder createUnsafeOkHttpClientBuilder() {
        try {
            // Create a trust manager that does not validate certificate chains
            final TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[]{};
                    }
                }
            };

            // Install the all-trusting trust manager
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            // Create an ssl socket factory with our all-trusting manager
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0]);
            builder.hostnameVerifier((hostname, session) -> true); // Don't verify hostname

            return builder;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create unsafe OkHttpClient builder", e);
        }
    }

    // --- END NEW METHOD ---


    /**
     * --- NEW HELPER METHOD ---
     * Extracts a specific query parameter (like 'code') from a full URL.
     *
     * @param url The full URL string
     * @param paramName The name of the parameter to extract (e.g., "code")
     * @return The value of the parameter, or null if not found.
     */
    public static String extractParamFromUrl(String url, String paramName) {
        try {
            URI uri = new URI(url);
            String query = uri.getQuery();
            if (query == null || query.isEmpty()) {
                return null;
            }

            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length > 1 && pair[0].equals(paramName)) {
                    // Return the value, URL-decoded (though often not needed for codes)
                    return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                }
            }
        } catch (URISyntaxException e) {
            e.printStackTrace(); // Handle the error appropriately
        }
        return null; // Not found
    }

    /**
     * --- NEW HELPER METHOD ---
     * Extremely simple JSON parser to avoid adding a new library.
     *
     * @param json The JSON response string
     * @param key The key to find (e.g., "access_token")
     * @return The value, or null if not found.
     */
    private static String extractJsonValue(String json, String key) {
        try {
            String keyToFind = "\"" + key + "\":\"";
            int keyIndex = json.indexOf(keyToFind);
            if (keyIndex == -1) {
                return null;
            }
            int valueStartIndex = keyIndex + keyToFind.length();
            int valueEndIndex = json.indexOf("\"", valueStartIndex);
            if (valueEndIndex == -1) {
                return null;
            }
            return json.substring(valueStartIndex, valueEndIndex);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }


    /**
     * Main method to test the *full* login flow with cookie injection.
     */
    public static void main(String[] args) {
        if (TEST_USERNAME.equals("your-test-username") || TEST_PASSWORD.equals("your-test-password")) {
            System.err.println("Please update TEST_USERNAME and TEST_PASSWORD at the top of the file.");
            return;
        }

        try {
            // 1. --- NEW: Test the full API token flow ---
            String accessToken = getApiAccessToken();
            System.out.println("\n--- SUCCESSFULLY ACQUIRED API TOKEN ---");
            System.out.println(accessToken);

            System.out.println("\n--- WebDriver Handoff Example (still works) ---");
            // 2. You can also still run the UI test flow.
            //    This will re-run the login, but that's okay.
            //    If you need both, you'd refactor this.
            
            // 1. Run the API login
            // String finalUrl = getLoginCallbackUrl(); // Not needed if we just got the token

            // ... (WebDriver handoff example code) ...

        } catch (Exception e) {
            System.err.println("\n--- LOGIN FAILED ---");
            e.printStackTrace();
        }
    }
}
