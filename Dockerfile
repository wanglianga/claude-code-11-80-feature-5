# 防汛平台状态迁移与规则升级服务
# 构建：docker build -t flood-state-migration .
# 运行：见 docker-compose.yml（一键启动 PostgreSQL + 应用）

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
# 使用阿里云 Maven 镜像加速依赖解析
COPY docker/settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
ENV SERVER_PORT=8080 \
    DB_URL=jdbc:postgresql://db:5432/ccdev \
    DB_USER=ccdev \
    DB_PASSWORD=ccdev
COPY --from=build /app/target/state-migration-service-1.0.0.jar /app/app.jar
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
  CMD curl -sf http://127.0.0.1:8080/v3/api-docs || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
