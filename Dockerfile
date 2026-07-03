FROM eclipse-temurin:17-jre
COPY build/libs/mcp-1.0.0.jar /app/mcp-1.0.0.jar
WORKDIR /app/
ENTRYPOINT ["java", "-jar", "/app/mcp-1.0.0.jar"]
