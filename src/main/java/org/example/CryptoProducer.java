package org.example;

import utils.CryptoApiClient;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * CryptoProducer:
 *   • Every 60 seconds, calls fetchLatestExchangeRateJson for each symbol → EUR
 *   • Produces that JSON onto Kafka topic "crypto-prices"
 *   • Key of each record = "<SYMBOL>_EUR"
 */
public class CryptoProducer {
    private static final String KAFKA_BOOTSTRAP = "localhost:29092";
    private static final String TOPIC = "crypto-prices";
    // Display symbol → API symbol mapping (XRB → NANO, others map to themselves)
    private static final Map<String, String> SYMBOL_API_MAP = createSymbolMap();

    private static Map<String, String> createSymbolMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("BTC", "BTC");
        map.put("ETH", "ETH");
        map.put("XRB", "NANO");   // XRB renamed to NANO on API
        map.put("BNB", "BNB");
        map.put("SOL", "SOL");
        map.put("DOGE", "DOGE");
        map.put("TRX", "TRX");
        map.put("ADA", "ADA");
        return map;
    }

    private final KafkaProducer<String, String> producer;
    private final CryptoApiClient apiClient = new CryptoApiClient();
    private volatile boolean keepProducing = true;

    public CryptoProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        this.producer = new KafkaProducer<>(props);
    }

    /**
     * In a loop: every 60s → fetch flat JSON (from/to/rate/timestamp) for each symbol → produce to Kafka.
     */
    public void startProducing() throws Exception {
        System.out.println("CryptoProducer: starting (publishing every 60s to '" + TOPIC + "') ...");
        while (keepProducing) {
            for (Map.Entry<String, String> entry : SYMBOL_API_MAP.entrySet()) {
                String displaySymbol = entry.getKey();
                String apiSymbol = entry.getValue();
                String key = displaySymbol + "_EUR";
                try {
                    String json = apiClient.fetchLatestExchangeRateJson(apiSymbol, "EUR");
                    ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key, json);
                    producer.send(record, (metadata, ex) -> {
                        if (ex != null) {
                            ex.printStackTrace();
                        } else {
                            System.out.printf(
                                    "Produced record: symbol=%s topic=%s partition=%d offset=%d%n",
                                    key, metadata.topic(), metadata.partition(), metadata.offset()
                            );
                        }
                    });
                } catch (Exception e) {
                    System.err.println("Error fetching/sending for " + displaySymbol + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
            Thread.sleep(Duration.ofSeconds(60).toMillis());
        }
        producer.close();
    }

    public void shutdown() {
        keepProducing = false;
        System.out.println("CryptoProducer: shutting down …");
    }

    public static void main(String[] args) throws Exception {
        CryptoProducer p = new CryptoProducer();
        Runtime.getRuntime().addShutdownHook(new Thread(p::shutdown));
        p.startProducing();
    }
}