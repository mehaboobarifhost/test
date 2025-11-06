import okhttp3.*;
import okhttp3.JavaNetCookieJar;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;


public class AprimoLogin {

    // --- !! UPDATE THESE !! ---
    // You have already updated these, which is correct.
    private static final String TEST_USERNAME = "user name";
    private static final String TEST_PASSWORD = "pass word";

    private static final String BASE_URL = "";
    private static final String LOGIN_PATH = "/login/Account/Login";
    private static final String CLIENT_ID = "";
    private static final String REDIRECT_URI = "MarketingOps/oidc/signin-callback.html";

    private static CookieManager cookieManager;
    private static String lastCodeVerifier;
    private static String lastState;

    /**
     * This method performs the programmatic login and returns the
     * final callback URL containing the authorization code.
     *
     * @return The final URL to pass to driver.get()
     * @throws Exception if any step fails
     */
    public static String getLoginCallbackUrl() throws Exception {

        // Step 1: Setup persistent cookie jar
        cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        CookieJar cookieJar = new JavaNetCookieJar(cookieManager);

        OkHttpClient client = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .build();

        OkHttpClient noRedirectClient = client.newBuilder()
                .followRedirects(false)
                .build();

        // Step 2: Generate PKCE and build the two URLs we need
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String state = "your-own-random-state-" + System.currentTimeMillis();

        // --- NEW: Store these values for retrieval ---
        lastCodeVerifier = codeVerifier;
        lastState = state;
        // --- END NEW ---

        // 1. This is the OIDC URL we need to hit *after* we are logged in
        HttpUrl authorizeCallbackUrl = new HttpUrl.Builder()
                .scheme("https")
                .host("com")
                .encodedPath("/login/connect/authorize/callback")
                .addQueryParameter("client_id", CLIENT_ID)
                .addQueryParameter("redirect_uri", REDIRECT_URI)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("scope", "api ui openid api-internal legacy-api filestore-access")
                .addQueryParameter("state", state)
                .addQueryParameter("code_challenge", codeChallenge)
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
        Request getLoginRequest = new Request.Builder().url(authUrl).build();
        Response loginPageResponse = client.newCall(getLoginRequest).execute();
        String loginPageHtml = loginPageResponse.body().string();
        Document loginDoc = Jsoup.parse(loginPageHtml, authUrl.toString());

        // Step 4: Parse and POST Login Form
        Element loginForm = loginDoc.select("form").first();
        Element loginButton = loginForm.select("button[name=loginButton]").first();

        if (loginButton == null) {
            throw new RuntimeException("Could not find login button with name 'loginButton'");
        }

        String postUrl = loginButton.absUrl("formaction");
        System.out.println("Step 4: POSTing credentials to: " + postUrl);

        FormBody.Builder formBuilder = new FormBody.Builder();

        for (Element input : loginForm.select("input[type=hidden]")) {
            formBuilder.add(input.attr("name"), input.attr("value"));
        }

        formBuilder.add("Username", TEST_USERNAME);
        formBuilder.add("Password", TEST_PASSWORD);
        formBuilder.add("loginButton", "login");

        Request postLoginRequest = new Request.Builder()
                .url(postUrl)
                .post(formBuilder.build())
                .build();

        Response postLoginResponse = noRedirectClient.newCall(postLoginRequest).execute();
        postLoginResponse.body().close();

        if (!postLoginResponse.isRedirect()) {
            // Debugging: Print error page if login fails
            String errorPageHtml = postLoginResponse.body().string();
            System.err.println("--- LOGIN FAILED: GOT " + postLoginResponse.code() + " ---");
            System.err.println(errorPageHtml);
            System.err.println("--------------------------------");
            throw new RuntimeException("Login POST failed. Expected a 302 redirect, got: " + postLoginResponse.code());
        }
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

    /**
     * --- NEW METHOD ---
     * Helper to get the generated Code Verifier.
     */
    public static String getLastCodeVerifier() {
        if (lastCodeVerifier == null) {
            throw new IllegalStateException("getLoginCallbackUrl() must be called first.");
        }
        return lastCodeVerifier;
    }

    /**
     * --- NEW METHOD ---
     * Helper to get the generated State.
     */
    public static String getLastState() {
        if (lastState == null) {
            throw new IllegalStateException("getLoginCallbackUrl() must be called first.");
        }
        return lastState;
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
}
