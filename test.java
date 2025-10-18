import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ApiBypassLogin {

    // --- Configuration Constants ---
    // NOTE: You must replace these placeholder values with your actual system details.
    // Base URL is the root of the application.
    private static final String BASE_URL = "https://testing.ttestin"; 
    
    // NOTE: The URL below is derived from your network logs (Request URL). 
    // If this URL returns a 302 (redirect), you need to find the actual API endpoint 
    // that accepts simple JSON credentials (username/password) without redirects.
    private static final String LOGIN_API_ENDPOINT = BASE_URL + "/Login/Account/Login";
    
    private static final String USERNAME = "your_username";
    private static final String PASSWORD = "your_password";
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
     * Uses Rest Assured to post credentials and extract session cookies.
     * @return A map of cookie name to cookie value.
     */
    public static Map<String, String> performApiLoginAndGetCookies() {
        // Set up the request body (often JSON for modern APIs)
        Map<String, String> credentials = new HashMap<>();
        credentials.put("username", USERNAME);
        credentials.put("password", PASSWORD);
        
        // Rest Assured setup and POST call
        // NOTE: If the API endpoint is a form submission, you might need to use 
        // contentType("application/x-www-form-urlencoded") and pass parameters 
        // using .formParams(credentials) instead of .body(credentials).
        Response response = RestAssured.given()
                .contentType("application/json") // Try JSON first
                .body(credentials)
                .log().method().log().uri() // Log the request details for debugging
                .when()
                .post(LOGIN_API_ENDPOINT)
                .then()
                .log().status() // Log the response status
                .extract()
                .response();

        // Validate success status (e.g., 200 OK or 201 Created)
        if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
            // Extract all cookies from the response.
            // Based on your screenshot, the critical ones are IDSRV.SESSION and IDSRV.CTODJW...
            Map<String, String> allCookies = response.getCookies();
            
            // Filter to include only essential session cookies (optional but good practice)
            Map<String, String> essentialCookies = new HashMap<>();
            
            for (Map.Entry<String, String> entry : allCookies.entrySet()) {
                String cookieName = entry.getKey();
                // Check for common Aprimo/Identity Server session cookie patterns
                if (cookieName.startsWith("IDSRV.") || cookieName.contains("session") || cookieName.contains("aebaea") || cookieName.contains("loginUserName")) {
                    essentialCookies.put(cookieName, entry.getValue());
                }
            }
            return essentialCookies;
        } else {
            System.err.println("API Login failed with status code: " + response.getStatusCode());
            System.err.println("Response Body: " + response.getBody().asString());
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
        // Cookies can only be set for the current domain, so we must load a page first.
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
