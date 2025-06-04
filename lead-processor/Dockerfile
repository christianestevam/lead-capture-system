FROM openjdk:21-jdk-slim
WORKDIR /app
COPY build/libs/lead-processor-1.0.0-SNAPSHOT-plain.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]