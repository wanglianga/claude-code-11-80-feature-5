#!/usr/bin/env bash
# 重置演示数据：清空业务表并重启应用（应用启动时重新写入演示种子数据）。
# 用法：scripts/reset-db.sh [jar 路径]
set -euo pipefail

DB_HOST=${DB_HOST:-127.0.0.1}
DB_NAME=${DB_NAME:-ccdev}
DB_USER=${DB_USER:-ccdev}
DB_PASSWORD=${DB_PASSWORD:-ccdev}
JAR=${1:-target/state-migration-service-1.0.0.jar}
PSQL=/opt/postgresql-18/bin/psql

echo "清空业务表..."
PGPASSWORD=$DB_PASSWORD $PSQL -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -c \
  "TRUNCATE stream_definition, rule_package, checkpoint_manifest, state_proof,
            migration_plan, dual_track_run, dual_track_diff, switch_gate,
            rollback_record, evidence_entry, stream_event,
            outcome_ledger_entry, field_observation, intervention_record,
            evaluation_run, release_gate RESTART IDENTITY CASCADE"

echo "重启应用..."
if [ -f /tmp/app.pid ] && kill -0 "$(cat /tmp/app.pid)" 2>/dev/null; then
  kill "$(cat /tmp/app.pid)" || true
  sleep 3
fi
nohup "${JAVA_BIN:-/opt/jdk-25/bin/java}" -jar "$JAR" > /tmp/app.log 2>&1 & echo $! > /tmp/app.pid
for i in $(seq 1 40); do
  if curl -sf -u "${AUTH:-operator:operator123}" "${BASE:-http://127.0.0.1:8080}/api/streams" >/dev/null 2>&1; then
    echo "应用已就绪"; exit 0
  fi
  sleep 2
done
echo "应用启动超时，请查看 /tmp/app.log"; exit 1
