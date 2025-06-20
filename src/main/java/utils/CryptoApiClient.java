// src/main/java/utils/CryptoApiClient.java

package utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Calls CoinMarketCap’s /v1/cryptocurrency/quotes/latest endpoint for a given from/to symbol pair,
 * extracts exactly these fields:
 *   • "from"      → the symbol you passed in (e.g. "BTC")
 *   • "to"        → the symbol you passed in (e.g. "EUR")
 *   • "timestamp" → the ISO timestamp from "last_updated" (e.g. "2025-06-04T12:34:56.000Z")
 *   • "rate"      → the numeric price (e.g. 60000.12)
 *
 * Builds a flat JSON string:
 *   {
 *     "from":   "BTC",
 *     "to":     "EUR",
 *     "timestamp": "2025-06-04T12:34:56.000Z",
 *     "rate":   60000.12
 *   }
 *
 * NOTE: Replace "YOUR_CMC_API_KEY" below with your real CoinMarketCap API key.
 */
public class CryptoApiClient {
    // ── Replace "YOUR_CMC_API_KEY" with your actual CMC API key ──
    private static final String CMC_API_KEY = "7a13940f-76e8-4373-8065-61661f454a63";

    // Base URL format for CMC's quotes/latest endpoint:
    //   https://pro-api.coinmarketcap.com/v1/cryptocurrency/quotes/latest?symbol=<from>&convert=<to>
    private static final String BASE_URL_FORMAT =
            "https://pro-api.coinmarketcap.com/v2/cryptocurrency/quotes/latest" +
                    "?symbol=%s" +
                    "&convert=%s";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Fetches the latest BTC→EUR (or any from/to) exchange rate from CoinMarketCap,
     * then returns a flat JSON string:
     *   {
     *     "from":   "BTC",
     *     "to":     "EUR",
     *     "timestamp": "2025-06-04T12:34:56.000Z",
     *     "rate":   60000.12
     *   }
     *
     * @param fromCurrency e.g. "BTC"
     * @param toCurrency   e.g. "EUR"
     * @return JSON string with exactly four keys: from, to, timestamp, rate
     * @throws Exception on network or parsing errors
     */
    public String fetchLatestExchangeRateJson(String fromCurrency, String toCurrency) throws Exception {
        // 1) Build the URL and open connection
        String urlStr = String.format(BASE_URL_FORMAT,
                urlEncode(fromCurrency),
                urlEncode(toCurrency));
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        // 2) Add the required header for CMC
        conn.setRequestProperty("Accepts", "application/json");
        conn.setRequestProperty("X-CMC_PRO_API_KEY", CMC_API_KEY);

        // 3) Read entire response into a StringBuilder
        int statusCode = conn.getResponseCode();
        BufferedReader in;
        if (statusCode >= 200 && statusCode < 300) {
            in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } else {
            // Read the error stream to see any CMC error message
            in = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
        }
        StringBuilder responseBuilder = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            responseBuilder.append(line);
        }
        in.close();
        String fullJson = responseBuilder.toString();

        // 4) Parse top-level JSON
        JsonNode rootNode = mapper.readTree(fullJson);

        // Check for any error in "status.error_code"
        JsonNode statusNode = rootNode.get("status");
        if (statusNode == null || statusNode.get("error_code").asInt() != 0) {
            String errMsg = statusNode != null
                    ? statusNode.get("error_message").asText("")
                    : "Unknown CMC response format";
            throw new RuntimeException("CMC API error: " + errMsg);
        }

        // 5) Navigate into "data" → "<FROM_SYMBOL>" → "quote" → "<TO_SYMBOL>"
        JsonNode dataNode = rootNode.get("data");
        if (dataNode == null || !dataNode.has(fromCurrency)) {
            throw new RuntimeException("Unexpected JSON format: 'data." + fromCurrency + "' not found");
        }
        JsonNode coinNode = dataNode.get(fromCurrency);
        JsonNode quoteNode = coinNode.get(0).get("quote");
        if (quoteNode == null || !quoteNode.has(toCurrency)) {
            throw new RuntimeException("Unexpected JSON format: 'quote." + toCurrency + "' not found");
        }
        JsonNode targetQuote = quoteNode.get(toCurrency);

        // 6) Extract exactly the fields we need
        double price         = targetQuote.get("price").asDouble();
        String lastUpdated   = targetQuote.get("last_updated").asText();

        // 7) Build a small, flat JSON object
        JsonNode out = mapper.createObjectNode()
                .put("from", fromCurrency)
                .put("to", toCurrency)
                .put("timestamp", lastUpdated)
                .put("rate", price);

        return mapper.writeValueAsString(out);
    }

    // Utility: URL-encode symbols (although for typical symbols like "BTC" or "EUR" it's optional)
    private static String urlEncode(String s) {
        return s.replace(" ", "%20");
    }
}