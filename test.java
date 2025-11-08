import okhttp3.*;
import okhttp3.JavaNetCookieJar;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * Performs a programmatic OIDC login for Aprimo by simulating the
 * browser flow to retrieve either session cookies or an API access token.
 *
 * This class is stateful; a new instance should be created for each login.
 */
public class AprimoLogin {

    private static final String BASE_URL = "https://company-sb1.aprimo.com";
    private static final String LOGIN_PATH = "/login/Account/Login";
    private static final String TOKEN_PATH = "/login/connect/token";
    private static final String CLIENT_ID = "MarketingOps";
    private static final String REDIRECT_URI = "https://company-sb1.aprimo.com/MarketingOps/oidc/signin-callback.html";

    // --- Instance fields for state ---
    private final String username;
    private final String password;
    private final OkHttpClient client;
    private final OkHttpClient noRedirectClient;
    private final CookieManager cookieManager;

    private String lastCodeVerifier;
    private String lastState;

    /**
     * Configuration object for corporate proxy settings.
     */
    public static class ProxyConfig {
        final String host;
        final int port;
        final String username;
        final String password;

        /**
         * @param host     Proxy host (e.g., "proxy.mycompany.com")
         * @param port     Proxy port (e.g., 8080)
         * @param username Proxy username (or null if not needed)
         * @param password Proxy password (or null if not needed)
         */
        public ProxyConfig(String host, int port, String username, String password) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
        }
    }

    /**
     * Creates a new Aprimo login session.
     * @param username The username to log in with.
     * @param password The password for the user.
     */
    public AprimoLogin(String username, String password) {
        this(username, password, null); // Call main constructor with no proxy
    }

    /**
     * Creates a new Aprimo login session with proxy configuration.
     * @param username The username to log in with.
     * @param password The password for the user.
     * @param proxyConfig A ProxyConfig object, or null to disable proxy.
     */
    public AprimoLogin(String username, String password, ProxyConfig proxyConfig) {
        this.username = username;
        this.password = password;

        this.cookieManager = new CookieManager();
        this.cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        CookieJar cookieJar = new JavaNetCookieJar(this.cookieManager);

        // Build the primary, unsafe, cookie-enabled client
        this.client = buildOkHttpClient(cookieJar, proxyConfig);

        // Build the non-redirecting client from the primary one
        this.noRedirectClient = this.client.newBuilder()
                .followRedirects(false)
                .build();
    }

    /**
     * Gets the final API Access Token by performing the full OIDC flow.
     *
     * @return The `access_token` string.
     * @throws Exception if any step fails
     */
    public String getApiAccessToken() throws Exception {
        System.out.println("--- Starting Full API Token Flow ---");

        String finalUrl = this.getLoginCallbackUrl();
        String code = extractParamFromUrl(finalUrl, "code");
        String state = extractParamFromUrl(finalUrl, "state");

        if (code == null) {
            throw new RuntimeException("Could not extract 'code' from final URL.");
        }

        if (this.lastState == null || !this.lastState.equals(state)) {
            throw new RuntimeException("OIDC state mismatch. Possible security issue.");
        }
        System.out.println("Auth code extracted. Exchanging for token...");

        // Use the same unsafe client (which has proxy/SSL settings)
        // We don't need the cookies, so we can use a new-built client.
        OkHttpClient tokenClient = this.client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build();

        FormBody tokenForm = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", CLIENT_ID)
                .add("code_verifier", this.lastCodeVerifier)
                .build();

        String tokenUrl = BASE_URL + TOKEN_PATH;
        Request tokenRequest = new Request.Builder()
                .url(tokenUrl)
                .post(tokenForm)
                .build();

        Response tokenResponse = tokenClient.newCall(tokenRequest).execute();
        String jsonBody = tokenResponse.body().string();

        if (!tokenResponse.isSuccessful()) {
            System.err.println("Token exchange failed with code: " + tokenResponse.code());
            System.err.println(jsonBody);
            throw new RuntimeException("Failed to exchange code for token.");
        }

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
    public String getLoginCallbackUrl() throws Exception {

        this.lastCodeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(lastCodeVerifier);
        this.lastState = "your-own-random-state-" + System.currentTimeMillis();

        HttpUrl authorizeCallbackUrl = new HttpUrl.Builder()
                .scheme("https")
                .host("company-sb1.aprimo.com")
                .encodedPath("/login/connect/authorize/callback")
                .addQueryParameter("client_id", CLIENT_ID)
                .addQueryParameter("redirect_uri", REDIRECT_URI)
                .addQueryParameter("response_type", "code")
                .addQueryParameter("scope", "api ui openid api-internal legacy-api filestore-access")
                .addQueryParameter("state", lastState)
                .addQueryParameter("code_challenge", codeChallenge)
                .addQueryParameter("code_challenge_method", "S256")
                .build();

        String returnUrlValue = authorizeCallbackUrl.encodedPath() + "?" + authorizeCallbackUrl.encodedQuery();

        HttpUrl authUrl = HttpUrl.parse(BASE_URL + LOGIN_PATH)
                .newBuilder()
                .addQueryParameter("ReturnUrl", returnUrlValue)
                .addQueryParameter("acr_values", "loginEntry:0")
                .build();

        System.out.println("Step 2: GET Login Page at: " + authUrl);

        Request getLoginRequest = new Request.Builder().url(authUrl).build();
        Response loginPageResponse = this.client.newCall(getLoginRequest).execute();
        String loginPageHtml = loginPageResponse.body().string();
        Document loginDoc = Jsoup.parse(loginPageHtml, authUrl.toString());

        Element loginForm = loginDoc.select("form").first();
        Element loginButton = loginDoc.select("button[name=loginButton]").first();

        if (loginButton == null) {
            throw new RuntimeException("Could not find login button with name 'loginButton'");
        }

        String postUrl = loginButton.absUrl("formaction");
        System.out.println("Step 4: POSTing credentials to: " + postUrl);

        FormBody.Builder formBuilder = new FormBody.Builder();
        for (Element input : loginForm.select("input[type=hidden]")) {
            formBuilder.add(input.attr("name"), input.attr("value"));
        }
        formBuilder.add("Username", this.username); // Use instance field
        formBuilder.add("Password", this.password); // Use instance field
        formBuilder.add("loginButton", "login");

        Request postLoginRequest = new Request.Builder()
                .url(postUrl)
                .post(formBuilder.build())
                .build();

        Response postLoginResponse = this.noRedirectClient.newCall(postLoginRequest).execute();
        
        if (!postLoginResponse.isRedirect()) {
            String errorPageHtml = "Could not read error page body.";
            try {
                errorPageHtml = postLoginResponse.body().string();
            } catch (Exception e) { /* Ignore */ }
            System.err.println("--- LOGIN FAILED: GOT " + postLoginResponse.code() + " ---");
            System.err.println(errorPageHtml);
            System.err.println("--------------------------------");
            throw new RuntimeException("Login POST failed. Check credentials. Expected 302, got: " + postLoginResponse.code());
        }
        postLoginResponse.body().close();
        System.out.println("Step 5: Login POST successful. Session cookies are set.");

        System.out.println("Step 6: Making direct GET request to authorize URL: " + authorizeCallbackUrl);
        Request authorizeRequest = new Request.Builder()
                .url(authorizeCallbackUrl)
                .get()
                .build();

        Response finalRedirectResponse = this.noRedirectClient.newCall(authorizeRequest).execute();
        String callbackUrlWithCode = finalRedirectResponse.header("Location");
        finalRedirectResponse.body().close();

        System.out.println("Step 7: Got final redirect: " + callbackUrlWithCode);

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
    public CookieStore getCookieStore() {
        return this.cookieManager.getCookieStore();
    }


    // --- PKCE Helper Methods ---
    private String generateCodeVerifier() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[64];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateCodeChallenge(String verifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Creates an OkHttpClient that bypasses SSL certificate checks
     * and optionally configures a proxy.
     *
     * @param cookieJar The CookieJar to attach.
     * @param proxyConfig The proxy configuration (or null).
     * @return A configured OkHttpClient.
     */
    private OkHttpClient buildOkHttpClient(CookieJar cookieJar, ProxyConfig proxyConfig) {
        try {
            // Create a trust manager that does not validate certificate chains
            final TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[]{}; }
                }
            };
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0]);
            builder.hostnameVerifier((hostname, session) -> true);
            builder.cookieJar(cookieJar);

            // --- !! PROXY CONFIGURATION !! ---
            // To use, pass a new ProxyConfig object to the AprimoLogin constructor.
            if (proxyConfig != null) {
                System.out.println("Configuring client with proxy: " + proxyConfig.host);
                Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyConfig.host, proxyConfig.port));
                builder.proxy(proxy);

                if (proxyConfig.username != null && proxyConfig.password != null) {
                    System.out.println("Configuring client with proxy authenticator.");
                    Authenticator proxyAuthenticator = (route, response) -> {
                        String credential = Credentials.basic(proxyConfig.username, proxyConfig.password);
                        return response.request().newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build();
                    };
                    builder.proxyAuthenticator(proxyAuthenticator);
                }
            }
            // --- !! END PROXY CONFIG !! ---

            return builder.build();

        } catch (Exception e) {
            throw new RuntimeException("Failed to create unsafe OkHttpClient builder", e);
        }
    }


    /**
     * Extracts a specific query parameter (like 'code') from a full URL.
     */
    private String extractParamFromUrl(String url, String paramName) {
        try {
            URI uri = new URI(url);
            String query = uri.getQuery();
            if (query == null || query.isEmpty()) {
                return null;
            }
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length > 1 && pair[0].equals(paramName)) {
                    return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                }
            }
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return null; // Not found
    }

    /**
     * Extremely simple JSON parser to avoid adding a new library.
     */
    private String extractJsonValue(String json, String key) {
        try {
            String keyToFind = "\"" + key + "\":\"";
            int keyIndex = json.indexOf(keyToFind);
            if (keyIndex == -1) return null;
            int valueStartIndex = keyIndex + keyToFind.length();
            int valueEndIndex = json.indexOf("\"", valueStartIndex);
            if (valueEndIndex == -1) return null;
            return json.substring(valueStartIndex, valueEndIndex);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }


    /**
     * Main method to test the *full* login flow.
     */
    public static void main(String[] args) {
        // --- !! 1. DEFINE CREDENTIALS AND PROXY (if needed) !! ---
        String username = "arif_ao";
        String password = "testing@12345";

        // To use proxy, uncomment this and pass it to the constructor:
        // AprimoLogin.ProxyConfig proxyConfig = new AprimoLogin.ProxyConfig(
        //         "your.office.proxy.com", 8080, "proxy-user", "proxy-pass");
        // AprimoLogin loginSession = new AprimoLogin(username, password, proxyConfig);
        
        // No proxy:
        AprimoLogin loginSession = new AprimoLogin(username, password);

        try {
            // --- 2. Test the API token flow ---
            String accessToken = loginSession.getApiAccessToken();
            System.out.println("\n--- SUCCESSFULLY ACQUIRED API TOKEN ---");
            System.out.println(accessToken);

        } catch (Exception e) {
            System.err.println("\n--- LOGIN FAILED ---");
            e.printStackTrace();
        }
    }
}
