.PHONY: test lint up down bench build clean

## Run all tests (requires Docker for Testcontainers)
test:
	./mvnw -B verify --no-transfer-progress

## Run only unit tests (no Docker needed)
test-unit:
	./mvnw -B test -Dtest="TransferDomainTest,BalancingPropertyTest" --no-transfer-progress

## Build the jar
build:
	./mvnw -B package -DskipTests --no-transfer-progress

## Start local services (postgres + kafka + app)
up:
	docker compose up -d --build
	docker compose ps

## Stop local services
down:
	docker compose down

## Run k6 load test against running app (requires up)
bench:
	k6 run k6/bench.js

## Check for secrets before final commit
secret-scan:
	gitleaks detect --source . --no-banner
	git grep -nE "D:/|C:/Users|gsk_|AIza|nvapi-|sk-" -- . ':!*.md' || true

## Clean build artifacts
clean:
	./mvnw -B clean
