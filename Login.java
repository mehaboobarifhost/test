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


public class testerLogin {

    // --- !! UPDATE THESE !! ---
    private static final String TEST_USERNAME = "";
    private static final String TEST_PASSWORD = "";
    // --- !! UPDATE THESE !! ---

    private static final String BASE_URL = "https://xyz-sb1.tester.com";
    private static final String LOGIN_PATH = "/login/Account/Login";
    private static final String CLIENT_ID = "MarketingOps";
    private static final String REDIRECT_URI = "https://xyz-sb1.tester.com/MarketingOps/oidc/signin-callback.html";

    // --- Static CookieManager to hold cookies for transfer ---
    private static CookieManager cookieManager;

    /**
     * This method performs the programmatic login and returns the
     * final callback URL containing the authorization code.
     *
     * @return The final URL to pass to driver.get()
     * @throws Exception if any step fails
     */
    public static String getLoginCallbackUrl() throws Exception {

        // --- !! PROXY CONFIGURATION FOR OFFICE NETWORKS !! ---
        //
        // 1. SET YOUR PROXY HOST AND PORT (from IntelliJ or IT)
        final String PROXY_HOST = "your.office.proxy.com"; // <--- 1. EDIT THIS
        final int PROXY_PORT = 8080; // <--- 2. EDIT THIS

        // 3. UNCOMMENT THIS LINE to enable the proxy
        // Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(PROXY_HOST, PROXY_PORT));
        
        // 4. If your proxy needs a username/password, uncomment this as well:
        // Authenticator proxyAuthenticator = new Authenticator() {
        //     @Override public Request authenticate(Route route, Response response) throws java.io.IOException {
        //         String credential = Credentials.basic("your-proxy-username", "your-proxy-password");
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
                // 5. UNCOMMENT THIS to apply the proxy
                // .proxy(proxy)
                // 6. UNCOMMENT THIS if you need proxy authentication
                // .proxyAuthenticator(proxyAuthenticator)
                .build();

        // This client will be used to catch 302 redirects
        OkHttpClient noRedirectClient = client.newBuilder()
                .followRedirects(false)
                .build();

        // Step 2: Generate PKCE and build the two URLs we need
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        String state = "your-own-random-state-" + System.currentTimeMillis();

        // 1. This is the OIDC URL we need to hit *after* we are logged in
        HttpUrl authorizeCallbackUrl = new HttpUrl.Builder()
                .scheme("https")
                .host("xyz-sb1.tester.com")
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
     * Main method to test the *full* login flow with cookie injection.
     */
    public static void main(String[] args) {
        if (TEST_USERNAME.equals("your-test-username") || TEST_PASSWORD.equals("your-test-password")) {
            System.err.println("Please update TEST_USERNAME and TEST_PASSWORD at the top of the file.");
            return;
        }

        try {
            // 1. Run the API login
            String finalUrl = getLoginCallbackUrl();

            System.out.println("\n--- WebDriver Handoff Example ---");
            System.out.println("API login complete. Starting WebDriver...");

            // 2. Start Selenium Driver (Example using Chrome)
            // WebDriver driver = new ChromeDriver();

            System.out.println("Driver started. Injecting cookies...");

            // 3. Go to the base domain (REQUIRED before adding cookies)
            // driver.get(BASE_URL);

            // 4. Get cookies from OkHttp
            CookieStore cookieStore = getCookieStore();

            // 5. Convert and inject cookies
            for (HttpCookie httpCookie : cookieStore.getCookies()) {

                // Calculate expiry date
                Date expiryDate = null;
                if (httpCookie.getMaxAge() != -1) {
                    expiryDate = new Date(System.currentTimeMillis() + httpCookie.getMaxAge() * 1000);
                }

                // --- CORRECTED: Use the full Cookie constructor ---
                org.openqa.selenium.Cookie seleniumCookie = new org.openqa.selenium.Cookie(
                        httpCookie.getName(),
                        httpCookie.getValue(),
                        httpCookie.getDomain(),
                        httpCookie.getPath(),
                        expiryDate,
                        httpCookie.getSecure(),
                        httpCookie.isHttpOnly()
                );

                // 6. Add cookie to Selenium
                // driver.manage().addCookie(seleniumCookie);
                System.out.println("Injecting cookie: " + seleniumCookie.getName());
            }

            System.out.println("Cookies injected. Navigating to final URL...");

            // 7. NOW navigate to the final URL
            // driver.get(finalUrl);

            System.out.println("Browser is now logged in!");

        } catch (Exception e) {
            System.err.println("\n--- LOGIN FAILED ---");
            e.printStackTrace();
        }
    }
}
