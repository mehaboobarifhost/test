import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import java.io.StringWriter;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.net.UnknownHostException;

public class ApiBypassLogin {

    // --- Configuration Constants ---
    // NOTE: You must replace these placeholder values with your actual system details.
    // Base URL is the root of the application.
    private static final String BASE_URL = "https://test"; 
    
    // THE CORRECT ENDPOINT: Using the /login/connect/token endpoint from your network log
    private static final String LOGIN_API_ENDPOINT = BASE_URL + "/login/connect/token";
    
    // API Credentials (often different from UI username)
    private static final String USERNAME = "your_api_username";
    private static final String PASSWORD = "your_api_password";
    
    // IDENTITY SERVER/OAuth 2.0 Parameters (CRITICAL: Find these in your 'token' request payload)
    private static final String GRANT_TYPE = "password"; // Usually 'password' for direct login with credentials
    private static final String CLIENT_ID = "your_client_id_from_payload"; // E.g., 'Aprimo_WebApp'
    private static final String SCOPE = "openid offline_access profile Aprimo"; // E.g., "openid offline_access profile Aprimo"
    
    private static final String START_PAGE_URL = BASE_URL + "/ui/main/dashboard"; // A page after login

    public static void main(String[] args) {
        // Step 1: Perform API Login and get cookies
        Map<String, String> sessionCookies = performApiLoginAndGetCookies();

        if (!sessionCookies.isEmpty()) {
            System.out.println("API Login Successful. Captured " + sessionCookies.size() + " session cookie(s).");
            // Step 2: Inject cookies and start UI tests
            startUITestsWithCookies(sessionCookies);
        } else {
            System.err.println("API Login failed or no essential cookies were returned. Aborting UI tests.");
        }
    }

    /**
     * Uses Rest Assured to post credentials as form-urlencoded data and extract session cookies.
     * @return A map of cookie name to cookie value.
     */
    public static Map<String, String> performApiLoginAndGetCookies() {
        // Set up the required form parameters
        Map<String, String> formParams = new HashMap<>();
        formParams.put("grant_type", GRANT_TYPE);
        formParams.put("client_id", CLIENT_ID);
        formParams.put("scope", SCOPE);
        formParams.put("username", USERNAME);
        formParams.put("password", PASSWORD);
        
        // Use a StringWriter to capture RestAssured logs for better error reporting
        StringWriter writer = new StringWriter();
        PrintStream captor = new PrintStream(new ByteArrayOutputStream());

        try {
            // Rest Assured setup and POST call
            Response response = RestAssured.given()
                    // CRITICAL: Use application/x-www-form-urlencoded and formParams
                    .contentType("application/x-www-form-urlencoded") 
                    .formParams(formParams)
                    .filter(new RequestLoggingFilter(captor)) // Capture request log
                    .filter(new ResponseLoggingFilter(captor)) // Capture response log
                    .when()
                    .post(LOGIN_API_ENDPOINT)
                    .then()
                    .extract()
                    .response();
            
            // Log captured details
            System.out.println("--- Rest Assured Request/Response Log ---");
            System.out.println(captor.toString());
            System.out.println("-----------------------------------------");


            // Validate success status (e.g., 200 OK or 201 Created)
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                // IMPORTANT: The token endpoint returns a JSON body with the access token.
                // The cookies you need for the UI will likely be set in a subsequent call 
                // using the access token (e.g., an OpenID Connect flow). 
                // However, let's capture any cookies returned here first.
                Map<String, String> allCookies = response.getCookies();
                
                // Filter to include only essential session cookies
                Map<String, String> essentialCookies = new HashMap<>();
                
                for (Map.Entry<String, String> entry : allCookies.entrySet()) {
                    String cookieName = entry.getKey();
                    // Check for common Aprimo/Identity Server session cookie patterns
                    if (cookieName.startsWith("IDSRV.") || cookieName.contains("session") || cookieName.contains("aebaea") || cookieName.contains("loginUserName")) {
                        essentialCookies.put(cookieName, entry.getValue());
                    }
                }
                
                if (essentialCookies.isEmpty()) {
                    System.out.println("SUCCESS: API call returned token (Status 200), but no essential cookies were found in this response.");
                    System.out.println("If UI bypass fails, you may need a follow-up call (e.g., to a /auth endpoint) using the access token from the JSON body.");
                    // In a real scenario, you'd extract the token: 
                    // String accessToken = response.jsonPath().getString("access_token");
                }

                return essentialCookies;
            } else {
                System.err.println("API Login failed with status code: " + response.getStatusCode());
                System.err.println("Response Body: " + response.getBody().asString());
                return new HashMap<>();
            }
        } catch (Exception e) {
            // Explicitly catch networking errors
            if (e.getCause() instanceof UnknownHostException) {
                System.err.println("--- NETWORK ERROR: HOST UNREACHABLE ---");
                System.err.println("ERROR: 'No such host is known' for URL: " + LOGIN_API_ENDPOINT);
                System.err.println("ACTION REQUIRED: Please verify you are connected to the correct VPN/Internal Network.");
                System.err.println("If the issue persists, check the DNS settings on the machine running this script.");
                System.err.println("-----------------------------------------");
            } else {
                System.err.println("An unexpected API communication error occurred: " + e.getMessage());
                e.printStackTrace();
            }
            return new HashMap<>();
        }
    }

    /**
     * Initializes Selenium WebDriver, injects the cookies, and navigates to the target page.
     * @param sessionCookies The map of cookies obtained from the API login.
     */
    public static void startUITestsWithCookies(Map<String, String> sessionCookies) {
        // Use WebDriverManager or set system property for ChromeDriver path
        // System.setProperty("webdriver.chrome.driver", "/path/to/chromedriver"); 
        WebDriver driver = new ChromeDriver();
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
        
        // 1. Navigate to the base domain first. 
        String domain = getDomainFromUrl(BASE_URL);
        driver.get(BASE_URL); 

        // 2. Inject the extracted cookies
        for (Map.Entry<String, String> entry : sessionCookies.entrySet()) {
            Cookie seleniumCookie = new Cookie.Builder(entry.getKey(), entry.getValue())
                    .domain(domain)
                    .path("/") // Often needed for session cookies
                    // Set secure and httpOnly properties if known, e.g.,
                    // .isSecure(true) 
                    // .isHttpOnly(true)
                    .build();
            driver.manage().addCookie(seleniumCookie);
            System.out.println("Injected cookie: " + entry.getKey());
        }

        // 3. Navigate to the target page using the session
        System.out.println("Navigating directly to: " + START_PAGE_URL);
        driver.get(START_PAGE_URL);

        // --- Start of your UI Test Logic ---
        // Now you can start interacting with elements immediately, e.g.,
        // driver.findElement(By.id("searchBox")).sendKeys("New Asset");
        // driver.findElement(By.id("searchButton")).click();
        // --- End of UI Test Logic ---
        
        // Keep the browser open for manual verification
        System.out.println("Successfully bypassed login. The dashboard should now be visible.");
        // driver.quit(); // Uncomment this line when running in your test framework
    }

    /**
     * Simple utility to extract domain from a URL for cookie setting.
     */
    private static String getDomainFromUrl(String url) {
        try {
            java.net.URL aURL = new java.net.URL(url);
            String host = aURL.getHost();
            // Important: Remove 'https://' and potentially 'www.' for the correct domain format
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host;
        } catch (java.net.MalformedURLException e) {
            System.err.println("Malformed URL: " + url);
            return "";
        }
    }
}
