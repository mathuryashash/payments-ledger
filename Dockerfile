FROM eclipse-temurin:21-jre AS final

# Non-root user for security
RUN groupadd --system ledger && useradd --system --gid ledger ledger

WORKDIR /app

# Copy layered jar (spring-boot:repackage produces layers.idx)
COPY --chown=ledger:ledger target/payments-ledger-*.jar app.jar

USER ledger

EXPOSE 18200

# Virtual threads and GC tuning for containers
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
