# JoinDrink 容器映像（多階段建置）
#
#   docker build -t joindrink .
#   docker run --rm -p 8082:8082 -e DB_HOST=host.docker.internal -e REDIS_HOST=host.docker.internal joindrink
#
# 建置階段：完整 JDK + Maven 產出 jar。
# 執行階段：只有 JRE、jar 與 frontend/（WebConfig 以 file:frontend/… 提供靜態頁面），以非 root 身分執行。
# serviceAccountKey.json、application-local.yml 與本機 uploads/ 由 .dockerignore 排除，不會進入映像。
#
# 本檔只在 CI 建置（不推送）。資料庫與 Redis 需由外部提供，見 docs/DEPLOYMENT.md。

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system app \
 && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app \
 && mkdir -p /app/uploads \
 && chown -R app:app /app
COPY --from=build /src/target/*.jar /app/app.jar
COPY frontend /app/frontend
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
