FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B -q
COPY src ./src
RUN mvn package -DskipTests -B -q

# Use Debian-based JRE so JavaCV/FFmpeg native libs (glibc) work; Alpine/musl often fails.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
RUN mkdir -p /app/uploads
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
