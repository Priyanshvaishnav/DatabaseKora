# ==============================================================================
# Stage 1: Build stage
# ==============================================================================
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# 1. Cache dependencies by copying POMs first
COPY pom.xml ./
COPY kora-core/pom.xml ./kora-core/
COPY kora-server/pom.xml ./kora-server/
COPY kora-cli/pom.xml ./kora-cli/
COPY kora-jdbc/pom.xml ./kora-jdbc/
COPY kora-replication/pom.xml ./kora-replication/
COPY kora-observability/pom.xml ./kora-observability/
COPY kora-ai/pom.xml ./kora-ai/
COPY kora-tests/pom.xml ./kora-tests/
COPY benchmarks/pom.xml ./benchmarks/

RUN mvn dependency:go-offline -B || true

# 2. Copy source trees and package fat jars
COPY kora-core/src ./kora-core/src
COPY kora-server/src ./kora-server/src
COPY kora-cli/src ./kora-cli/src
COPY kora-jdbc/src ./kora-jdbc/src
COPY kora-replication/src ./kora-replication/src
COPY kora-observability/src ./kora-observability/src
COPY kora-ai/src ./kora-ai/src
COPY kora-tests/src ./kora-tests/src
COPY benchmarks/src ./benchmarks/src

RUN mvn clean package -DskipTests -B

# ==============================================================================
# Stage 2: Minimal, secure runtime image
# ==============================================================================
FROM eclipse-temurin:21-jre-jammy

LABEL org.opencontainers.image.title="KoraDB" \
      org.opencontainers.image.description="Relational database engine built from scratch in Java 21" \
      org.opencontainers.image.version="2.0.1" \
      org.opencontainers.image.authors="KoraDB Maintainers" \
      org.opencontainers.image.licenses="MIT"

# Create non-root dedicated user and group
RUN groupadd -r -g 10001 koradb && \
    useradd -r -u 10001 -g koradb -m -d /var/lib/koradb koradb

# Install utilities for container health checks
RUN apt-get update && \
    apt-get install -y --no-install-recommends wget procps ca-certificates && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy runnable fat jars from builder
COPY --from=builder /build/kora-server/target/koradb-server.jar /app/koradb-server.jar
COPY --from=builder /build/kora-cli/target/koradb-cli.jar /app/koradb-cli.jar

# Setup persistent volume directory owned by koradb user
RUN mkdir -p /var/lib/koradb/data && \
    chown -R koradb:koradb /app /var/lib/koradb

# Copy and setup entrypoint script
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

# Default environment configuration (12-Factor)
ENV KORA_HOST=0.0.0.0 \
    KORA_PORT=8765 \
    KORA_DATA_DIR=/var/lib/koradb/data \
    KORA_ROLE=PRIMARY \
    KORA_REPLICATION_PORT=8766 \
    KORA_METRICS_ENABLED=true \
    KORA_METRICS_PORT=9100 \
    KORA_HTTP_ENABLED=true \
    KORA_HTTP_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseZGC -XX:+ZGenerational"

USER koradb

EXPOSE 8765 8766 9100 8080

VOLUME ["/var/lib/koradb/data"]

HEALTHCHECK --interval=10s --timeout=5s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1:9100/health || exit 1

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["server"]
