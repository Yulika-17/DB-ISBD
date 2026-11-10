FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu AS builder
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN ./mvnw package -DskipTests

FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu
WORKDIR /app
COPY --from=builder /workspace/target/lab-db-*.jar app.jar
ENV SERVER_PORT=2222
ENV VECDB_DATA_DIRECTORY=/app/data
VOLUME /app/data
EXPOSE 2222
ENTRYPOINT ["java", "-jar", "app.jar"]
