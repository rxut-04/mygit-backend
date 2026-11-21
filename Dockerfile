# Stage 1: Build application with Maven
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy project definition and source
COPY pom.xml .
COPY src ./src

# Package production executable jar
RUN mvn clean package -DskipTests

# Stage 2: Lightweight JRE 21 Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy built artifact
COPY --from=build /app/target/backend-0.0.1-SNAPSHOT.jar app.jar

# Render dynamically binds $PORT
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
