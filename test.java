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
