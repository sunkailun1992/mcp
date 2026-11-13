FROM eclipse-temurin:21-jre
COPY build/libs/mcp.jar /app/mcp.jar
WORKDIR /app/
ENTRYPOINT ["java", "-jar", "/app/mcp.jar"]
