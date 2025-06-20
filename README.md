# crypto-realtime-processing-project

1. run mvn package to build the project
2. navigate to the docker folder and run the broker: docker-compose up -d broker
3. create the topic: docker-compose exec broker \
   kafka-topics \
   --bootstrap-server broker:9092 \
   --create \
   --topic crypto-prices \
   --partitions 1 \
   --replication-factor 1
4. run the producer in intellij with java 17, the class is src/main/java/university.project/CryptoProducer.java **running other way may not work**
5. run the docker-compose profile which runs postgres, spark jobs, grafana server: docker-compose --profile crypto-processor up --build -d
   - this will run the spark job which will read from the topic and write to postgres
   - Note: if the spark job fails to start due to a class not found error, you may need to 
   switch between CryptoStreamingProject1-1.0-SNAPSHOT.jar and CryptoStreamingProject1.jar in the crypto-processor/volume section of the docker-compose.yml file
   - the docker profile will also run the grafana server which will read from postgres and display the data in a dashboard available at http://localhost:3000
   - the default username and password for grafana is admin/admin
