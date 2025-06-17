package org.example;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

import java.util.concurrent.TimeoutException;

/**
 * CryptoStreamProcessor:
 *   • Reads from Kafka topic "crypto-prices", where each value is:
 *       {"from":"BTC","to":"EUR","timestamp":"2025-06-02 19:40:10","rate":91198.95}
 *   • Parses into (from:String, to:String, timestamp:Timestamp, rate:Double)
 *   • Uses a 5‐minute tumbling window on 'timestamp'
 *   • For each window + currency pair, computes:
 *       high_rate = MAX(rate),
 *       low_rate  = MIN(rate)
 *   • Writes each result row into PostgreSQL table "crypto_high_low"
 */
public class CryptoStreamProcessor {
    private final SparkSession spark;
    private StreamingQuery query;

    public CryptoStreamProcessor() {
        this.spark = SparkSession.builder()
                .appName("CryptoStreamProcessor5min")
                .master("local[*]")
                .getOrCreate();
    }

    public void startProcessing() throws StreamingQueryException, TimeoutException {
        // 1) Define the JSON schema the Producer emits:
        StructType schema = DataTypes.createStructType(new StructField[]{
                DataTypes.createStructField("from", DataTypes.StringType,    false),
                DataTypes.createStructField("to", DataTypes.StringType,      false),
                DataTypes.createStructField("timestamp", DataTypes.TimestampType, false),
                DataTypes.createStructField("rate", DataTypes.DoubleType,    false)
        });

        // 2) Read raw Kafka stream
        Dataset<Row> kafkaRaw = spark.readStream()
                .format("kafka")
                .option("kafka.bootstrap.servers", "broker:9092")
                .option("subscribe", "crypto-prices")
                .option("startingOffsets", "latest")
                .load();

        // 3) Parse JSON in 'value' into columns (from, to, timestamp, rate)
        Dataset<Row> jsonParsed = kafkaRaw
                .selectExpr("CAST(value AS STRING) AS json_str")
                .select(functions.from_json(functions.col("json_str"), schema).alias("data"))
                .select("data.from", "data.to", "data.timestamp", "data.rate");

        // 4) Tumbling window of 5 minutes, with watermark for late data
        Dataset<Row> windowedAgg = jsonParsed
                .withWatermark("timestamp", "5 minutes")
                .groupBy(
                        functions.window(functions.col("timestamp"), "5 minutes"),
                        functions.col("from"),
                        functions.col("to")
                )
                .agg(
                        functions.max("rate").alias("high_rate"),
                        functions.min("rate").alias("low_rate"),
                        functions.avg("rate").alias("avg_rate")
                )
                .select(
                        functions.col("window.start").alias("window_start"),
                        functions.col("window.end").alias("window_end"),
                        functions.col("from").alias("from_currency"),
                        functions.col("to").alias("to_currency"),
                        functions.col("high_rate"),
                        functions.col("low_rate"),
                        functions.col("avg_rate")
                );

        // 5) Write each micro‐batch into PostgreSQL table "crypto_high_low"
        String jdbcUrl      = "jdbc:postgresql://postgres:5432/cryptodb";
        String jdbcUser     = "myuser";
        String jdbcPassword = "secretpass";
        String pgTable      = "crypto_high_low";

        this.query = windowedAgg
                .writeStream()
                .foreachBatch((batchDF, batchId) -> {
                    batchDF.write()
                            .format("jdbc")
                            .option("url", jdbcUrl)
                            .option("dbtable", pgTable)
                            .option("user", jdbcUser)
                            .option("password", jdbcPassword)
                            .option("driver",   "org.postgresql.Driver")
                            .mode("append")
                            .save();
                })
                .outputMode("append")  // each completed 5-min window is appended
                .start();

        System.out.println("CryptoStreamProcessor: started 5-minute tumbling window job → PostgreSQL");
        query.awaitTermination();
    }

    public void shutdown() throws TimeoutException {
        if (query != null) query.stop();
        if (spark != null) spark.stop();
    }

    public static void main(String[] args) throws StreamingQueryException, TimeoutException {
        CryptoStreamProcessor processor = new CryptoStreamProcessor();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                processor.shutdown();
            } catch (TimeoutException e) {
                e.printStackTrace();
            }
        }));
        processor.startProcessing();
    }
}