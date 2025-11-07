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
