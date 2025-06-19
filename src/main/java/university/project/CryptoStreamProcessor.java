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

public class CryptoStreamProcessor {
    private final SparkSession spark;
    private StreamingQuery query1min;
    private StreamingQuery query5min;
    private StreamingQuery query10min;

    public CryptoStreamProcessor() {
        this.spark = SparkSession.builder()
                .appName("CryptoStreamProcessorMultiWindow")
                .master("local[*]")
                .getOrCreate();
    }

    public void startProcessing() throws StreamingQueryException, TimeoutException {
        // 1) Define the JSON schema the Producer emits:
        StructType schema = DataTypes.createStructType(new StructField[]{
                DataTypes.createStructField("from",      DataTypes.StringType,    false),
                DataTypes.createStructField("to",        DataTypes.StringType,    false),
                DataTypes.createStructField("timestamp", DataTypes.TimestampType, false),
                DataTypes.createStructField("rate",      DataTypes.DoubleType,    false)
        });

        // 2) Read raw Kafka stream
        Dataset<Row> kafkaRaw = spark.readStream()
                .format("kafka")
                .option("kafka.bootstrap.servers", "broker:9092")
                .option("subscribe", "crypto-prices")
                .option("startingOffsets", "latest")
                .load();

        // 3) Parse JSON in 'value' into columns
        Dataset<Row> jsonParsed = kafkaRaw
                .selectExpr("CAST(value AS STRING) AS json_str")
                .select(functions.from_json(functions.col("json_str"), schema).alias("data"))
                .select("data.from", "data.to", "data.timestamp", "data.rate");

        // ------------------------------------------------
        // 4a) 1-minute tumbling window → crypto_rate_1min
        Dataset<Row> agg1 = jsonParsed
                .withWatermark("timestamp", "1 minute")
                .groupBy(
                        functions.window(functions.col("timestamp"), "1 minute"),
                        functions.col("from"),
                        functions.col("to")
                )
                .agg(
                        functions.avg("rate").alias("rate")
                )
                .select(
                        functions.col("window.start").alias("window_start"),
                        functions.col("window.end").alias("window_end"),
                        functions.col("from").alias("from_currency"),
                        functions.col("to").alias("to_currency"),
                        functions.col("rate")
                );

        query1min = agg1.writeStream()
                .foreachBatch((batchDF, batchId) -> {
                    batchDF.write()
                            .format("jdbc")
                            .option("url",      "jdbc:postgresql://postgres:5432/cryptodb")
                            .option("dbtable",  "crypto_rate_1min")
                            .option("user",     "myuser")
                            .option("password", "secretpass")
                            .option("driver",   "org.postgresql.Driver")
                            .mode("append")
                            .save();
                })
                .outputMode("append")
                .start();

        // ------------------------------------------------
        // 4b) 5-minute tumbling window → crypto_high_low  (your existing code)
        Dataset<Row> windowedAgg5 = jsonParsed
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

        query5min = windowedAgg5.writeStream()
                .foreachBatch((batchDF, batchId) -> {
                    batchDF.write()
                            .format("jdbc")
                            .option("url",      "jdbc:postgresql://postgres:5432/cryptodb")
                            .option("dbtable",  "crypto_rate_5min")
                            .option("user",     "myuser")
                            .option("password", "secretpass")
                            .option("driver",   "org.postgresql.Driver")
                            .mode("append")
                            .save();
                })
                .outputMode("append")
                .start();

        // ------------------------------------------------
        // 4c) 10-minute tumbling window → crypto_10min_high_low
        Dataset<Row> agg10 = jsonParsed
                .withWatermark("timestamp", "10 minutes")
                .groupBy(
                        functions.window(functions.col("timestamp"), "10 minutes"),
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

        query10min = agg10.writeStream()
                .foreachBatch((batchDF, batchId) -> {
                    batchDF.write()
                            .format("jdbc")
                            .option("url",      "jdbc:postgresql://postgres:5432/cryptodb")
                            .option("dbtable",  "crypto_rate_10min")
                            .option("user",     "myuser")
                            .option("password", "secretpass")
                            .option("driver",   "org.postgresql.Driver")
                            .mode("append")
                            .save();
                })
                .outputMode("append")
                .start();

        System.out.println("Started 1-min, 5-min & 10-min tumbling window jobs → PostgreSQL");

        // 5) Await termination of all three
        spark.streams().awaitAnyTermination();
    }

    public void shutdown() throws TimeoutException {
        if (query1min != null)  query1min.stop();
        if (query5min != null)  query5min.stop();
        if (query10min != null) query10min.stop();
        if (spark != null)      spark.stop();
    }

    public static void main(String[] args) throws StreamingQueryException, TimeoutException {
        CryptoStreamProcessor processor = new CryptoStreamProcessor();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { processor.shutdown(); }
            catch (TimeoutException e) { e.printStackTrace(); }
        }));
        processor.startProcessing();
    }
}