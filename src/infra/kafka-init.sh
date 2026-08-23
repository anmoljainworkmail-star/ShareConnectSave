#!/bin/bash
# kafka-init.sh (T009) — creates every Kafka topic the platform's event-driven
# choreography depends on, then exits.
#
# Pattern: Infrastructure as Code — topic topology (names, partition counts,
# replication factors) is a file in version control, reviewed in a PR like
# any other change, instead of a command an operator remembers to type once
# against a live cluster and never writes down. Anyone can read this file and
# know exactly what topics exist without connecting to Kafka.
#
# Pattern: Idempotent Operations — `--if-not-exists` makes re-running this
# script a no-op instead of an error. Without it, a second `docker compose up`
# on a machine that already has these topics would exit non-zero on the very
# first `--create` call, and Compose would report the whole job as failed even
# though the topics are already exactly as intended. Idempotency is what lets
# infra scripts be re-run safely instead of requiring "did I already do this?"
# bookkeeping by whoever runs them.
set -euo pipefail

# kafka:29092 — the broker's INTERNAL listener, not kafka:9092 (PLAINTEXT).
# Kafka's client protocol connects to --bootstrap-server only to fetch initial
# metadata; every real request after that goes to whatever address the broker
# *advertises* in that metadata. PLAINTEXT advertises "localhost:9092" (kept
# that way for the T004 acceptance criterion — Kafka reachable from a host
# terminal). Bootstrapping this container through kafka:9092 would connect
# fine, then get redirected to "localhost:9092" — which inside this container
# means itself, not the broker — and every --create call would hang until it
# timed out. INTERNAL advertises "kafka:29092", which resolves back to the
# broker from any container on the Docker network. See the kafka service's
# KAFKA_CFG_ADVERTISED_LISTENERS comment in docker-compose.yml for the full
# story.
BOOTSTRAP_SERVER="kafka:29092"

# Event names are past tense (facts that already happened), not commands —
# that's what makes this Event-Driven Architecture rather than Kafka being
# used as a disguised RPC layer. See the kafka-outbox skill for why that
# distinction matters (producers never expect or wait for a reply).
TOPICS=(
  "user.verified"
  "connection.accepted"
  "connection.expired"
  "chat.closed"
  "rating.submitted"
  "trust.score.updated"
  "report.filed"
)

# 3 partitions / replication factor 1 for every topic (dev-only single broker
# can't replicate past 1; partition count of 3 is enough parallelism for a
# handful of dev consumers without over-provisioning). Production would raise
# both — partitions to match consumer-group size, replication to survive a
# broker loss — but that's a per-environment concern, not something this
# script should hardcode differently per topic today.
PARTITIONS=3
REPLICATION_FACTOR=1

for topic in "${TOPICS[@]}"; do
  echo "Ensuring Kafka topic exists: ${topic}"
  kafka-topics.sh \
    --bootstrap-server "${BOOTSTRAP_SERVER}" \
    --create \
    --if-not-exists \
    --topic "${topic}" \
    --partitions "${PARTITIONS}" \
    --replication-factor "${REPLICATION_FACTOR}"
done

echo "Kafka topic initialization complete — ${#TOPICS[@]} topics ensured."
