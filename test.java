package com.mycompany.framework.api;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

public class AprimoApiClient {

    private RequestSpecification requestSpec;

    public AprimoApiClient() {
        // 1. Get Base URI and Token from a config file (ConfigReader.java)
        String baseUri = "https://your-tenant.aprimo.com/api/v1";
        String apiToken = "Bearer ...your-oauth-or-api-token..."; // This should be fetched securely

        // 2. Create a reusable RequestSpecification (follows DRY)
        requestSpec = RestAssured.given()
                .baseUri(baseUri)
                .header("Authorization", apiToken)
                .header("Accept", "application/json") // Default headers
                .contentType(ContentType.JSON);
    }

    /**
     * Gets a specific asset from Aprimo DAM.
     * @param assetId The ID of the asset to retrieve.
     * @return The full RestAssured Response object.
     */
    public Response getAssetById(String assetId) {
        return requestSpec
                .when()
                .get("/assets/{id}", assetId); // Using path parameter
    }

    /**
     * Creates a new asset (example with a body).
     * @param assetPayload A POJO representing the asset to create.
     * @return The full RestAssured Response object.
     */
    public Response createAsset(Object assetPayload) {
        return requestSpec
                .body(assetPayload) // Automatically serializes the POJO to JSON
                .when()
                .post("/assets");
    }
    
    // ... add more methods for POST, PUT, DELETE as needed
    // e.g., searchAssets(String query), updateAsset(String assetId, Object payload)
}





=================

    package com.mycompany.framework.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true) // Ignores fields you don't care about
public class Asset {

    @JsonProperty("id")
    private String id;

    @JsonProperty("title")
    private String title;
    
    @JsonProperty("fileName")
    private String fileName;

    // ... getters and setters
    
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
}


=================

    Feature: Aprimo Asset Retrieval
  As an authenticated user
  I want to retrieve asset details
  So that I can verify their data

  Scenario: Retrieve a specific asset by ID
    Given I am an authenticated user with API access
    When I request the asset with ID "12345"
    Then the API response status should be 200
    And the response should contain an asset with title "MyTestImage"


    ===================
    package com.mycompany.framework.steps;

import com.mycompany.framework.api.AprimoApiClient;
import com.mycompany.framework.models.Asset; // Your POJO
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import static org.junit.Assert.assertEquals; // Using JUnit assertions

public class AssetStepDefinitions {

    private AprimoApiClient apiClient;
    private Response response; // Store the response for later assertions
    private Asset asset;       // Store the deserialized POJO

    @Given("I am an authenticated user with API access")
    public void i_am_authenticated() {
        // The client constructor handles authentication
        apiClient = new AprimoApiClient();
    }

    @When("I request the asset with ID {string}")
    public void i_request_asset_by_id(String assetId) {
        response = apiClient.getAssetById(assetId);
    }

    @Then("the API response status should be {int}")
    public void the_api_response_status_should_be(int expectedStatusCode) {
        assertEquals(expectedStatusCode, response.getStatusCode());
    }

    @Then("the response should contain an asset with title {string}")
    public void the_response_should_contain_asset_title(String expectedTitle) {
        // Deserialize the JSON response into your Asset POJO
        asset = response.as(Asset.class);
        
        // Now assertions are clean and type-safe!
        assertEquals(expectedTitle, asset.getTitle());
    }
}
