FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY . .

RUN chmod +x ./mvnw
RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${PORT:-8080}"]