# 백엔드 이미지 (Java 21 + Spring Boot)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 의존성 레이어를 먼저 캐시합니다(소스만 바뀌면 재다운로드하지 않습니다).
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# wget은 헬스체크에 씁니다.
RUN apk add --no-cache wget tzdata
ENV TZ=Asia/Seoul

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
