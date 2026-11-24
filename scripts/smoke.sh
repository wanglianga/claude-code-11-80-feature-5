#!/usr/bin/env bash
# 端到端冒烟：证明 → 迁移编排 → 双轨 → 闸门待验证 → 检查点补齐 → 规则二次修订过期重绑
#            → 发布新输出 → 回放越过切换水印 → 回退撤回 → 证据账分类核验
# 前置：应用已启动且演示数据为初始状态（如需重置见 README「重置演示数据」）。
set -euo pipefail

BASE=${BASE:-http://127.0.0.1:8080}
AUTH=${AUTH:-operator:operator123}
STREAM_CODE=${STREAM_CODE:-FY-MAIN}

req() { curl -sf -u "$AUTH" -H 'Content-Type: application/json' "$@"; }
now_minus() { date -u -d "$1 minutes ago" +%Y-%m-%dT%H:%M:%SZ; }
step() { echo; echo "=== $* ==="; }

SID=$(req "$BASE/api/streams" | jq -r ".[] | select(.streamCode==\"$STREAM_CODE\") | .id")
[ -n "$SID" ] || { echo "找不到演示流 $STREAM_CODE"; exit 1; }
echo "流 ID: $SID"

step "1. 状态语义证明器：守恒条件"
PROOF=$(req -X POST "$BASE/api/streams/$SID/proofs" -d '{}')
echo "$PROOF" | jq '{verdict, status, boundRulePackageVersion, conservationJson}'
echo "$PROOF" | jq -e '.conservationJson | contains("PUMP_ON_OFF_CONSERVATION")' >/dev/null

step "2. 迁移编排：p0/p3 适配，p1/p2 限定窗口回放"
PLANS=$(req -X POST "$BASE/api/streams/$SID/migration-plans")
echo "$PLANS" | jq '[.[] | {partitionNo, route, reason: (.reason[:40])}]'
echo "$PLANS" | jq -e '[.[] | .route] | sort == ["ADAPT","ADAPT","REPLAY","REPLAY"]' >/dev/null

step "3. 执行迁移计划"
req -X POST "$BASE/api/streams/$SID/migration-plans/execute-all" | jq '[.[] | {partitionNo, route, status}]'

step "4. 双轨校验：p2 新规则 LOW→HIGH 升级"
RUNS=$(req -X POST "$BASE/api/streams/$SID/dual-track-runs" -d '{}')
RUN2=$(echo "$RUNS" | jq -r '.[] | select(.partitionNo==2) | .id')
req "$BASE/api/dual-track-runs/$RUN2/diffs" | jq '[.[] | {roadId, oldRisk, newRisk, delta, escalation}]'
echo "$RUNS" | jq -e 'length == 4' >/dev/null

step "5. 闸门评估：p2 旧检查点未完成 → 维持旧输出并标记待验证"
EVAL1=$(req -X POST "$BASE/api/streams/$SID/gate/evaluate")
echo "$EVAL1" | jq '{canRelease, pendingVerification, holdReasons}'
echo "$EVAL1" | jq -e '.canRelease == false and (.pendingVerification | contains([2]))' >/dev/null

step "6. 旧作业补齐 p2 检查点 → 闸门放行条件满足"
req -X POST "$BASE/api/streams/$SID/checkpoints" -d "{
  \"partitionNo\": 2, \"consumeOffset\": 41,
  \"windowWatermark\": \"$(now_minus 5)\",
  \"stateSnapshotJson\": \"{\\\"gridId\\\":\\\"R-003\\\",\\\"windowStart\\\":\\\"$(now_minus 130)\\\",\\\"windowEnd\\\":\\\"$(now_minus 10)\\\",\\\"rain2hAcc\\\":14.9,\\\"upstreamInflowAcc\\\":600.0,\\\"deviceHealth\\\":0.9,\\\"pumpRuntimeMin\\\":0,\\\"pumpOnOffEvents\\\":[],\\\"riskLevel\\\":\\\"LOW\\\"}\",
  \"outputBatchId\": \"old-batch-p2-41\", \"rulePackageVersion\": \"v1.0.0\",
  \"stateSchema\": \"GRID_WINDOW_V1\", \"status\": \"COMPLETE\"}" | jq '{partitionNo, status}'
req -X POST "$BASE/api/streams/$SID/gate/evaluate" | jq '{canRelease, coverage}'

step "7. 双轨期间规则二次修订 v2.1.0：比较与证明过期"
REV=$(req -X POST "$BASE/api/streams/$SID/rule-packages" -d '{
  "version": "v2.1.0", "kind": "MULTI_FACTOR",
  "configJson": "{\"medium\":25,\"high\":55,\"wRain\":0.6,\"wTrend\":1.0,\"wLag\":0.15,\"wDepth\":0.2,\"credibilityFloor\":0.6}"}')
echo "$REV" | jq '{previousTargetVersion, reboundTargetVersion, expiredProofs, expiredDualTrackRuns, stalePlans}'
echo "$REV" | jq -e '.expiredProofs >= 1 and .expiredDualTrackRuns >= 1' >/dev/null
req -X POST "$BASE/api/streams/$SID/gate/evaluate" | jq '{canRelease, proofValid}'

step "8. 重新绑定 v2.1.0：证明 → 计划 → 执行 → 双轨"
req -X POST "$BASE/api/streams/$SID/proofs" -d '{}' | jq '{boundRulePackageVersion, status}'
PLANS2=$(req -X POST "$BASE/api/streams/$SID/migration-plans")
echo "$PLANS2" | jq '[.[] | {partitionNo, route}]'
req -X POST "$BASE/api/streams/$SID/migration-plans/execute-all" | jq '[.[] | {partitionNo, route, status}]'
req -X POST "$BASE/api/streams/$SID/dual-track-runs" -d '{}' | jq '[.[] | {partitionNo, newRuleVersion, status}]'

step "9. 闸门发布新输出"
req -X POST "$BASE/api/streams/$SID/gate/release" | jq '{released, publishedOutputs, switchOffsets}'

step "10. 回放越过切换水印：p1 新事件到达后复核回放"
req -X POST "$BASE/api/streams/$SID/events" -d "{\"partitionNo\":1,\"eventTime\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"type\":\"RAIN\",\"roadId\":\"R-002\",\"payloadJson\":\"{\\\"mm\\\":3.0}\"}" >/dev/null
req -X POST "$BASE/api/streams/$SID/events" -d "{\"partitionNo\":1,\"eventTime\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"type\":\"ROAD_REPORT\",\"roadId\":\"R-002\",\"payloadJson\":\"{\\\"depthCm\\\":30}\"}" >/dev/null
P1_PLAN=$(req "$BASE/api/streams/$SID/migration-plans" | jq -r '[.[] | select(.partitionNo==1)] | max_by(.id) | .id')
CROSS=$(req -X POST "$BASE/api/migration-plans/$P1_PLAN/execute" -d "{
  \"replayWindowStart\": \"$(now_minus 125)\", \"replayWindowEnd\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}")
echo "$CROSS" | jq '{route, status, resultJson}'
echo "$CROSS" | jq -e '.resultJson | contains("\"crossesSwitchWatermark\":true")' >/dev/null

step "11. 回退：撤回范围内新输出，旧快照只读"
req -X POST "$BASE/api/streams/$SID/rollbacks" -d '{
  "fromOffset": 0, "toOffset": 1000, "reason": "演练：新规则输出回退"}' \
  | jq '{retractedCount, readOnlySnapshotIdsJson, reason}'
req "$BASE/api/streams/$SID/gate" | jq '{status}'

step "12. 证据账分类统计（旧输出/新候选/新输出/已撤回）"
req "$BASE/api/streams/$SID/evidence" | jq 'group_by(.kind) | map({kind: .[0].kind, count: length})'
req "$BASE/api/streams/$SID" | jq '{status, stateSchema, rulePackageVersion}'

step "13. 预警结果账：冻结条目 + 暴露拆分（规则升级跨越同一积水事件）"
req "$BASE/api/streams/$SID/outcome-ledger" | jq -c '.[] | {roadId, riskLevel, exposureGroup, exposureSplit}'
req "$BASE/api/streams/$SID/outcome-ledger/exposure" | jq '{crossSplitGroups: [.groups[] | select(.crossEventSplit) | .exposureGroup]}'

step "14. 结果覆盖与标签状态：受干预/延迟/未观察分类"
COV=$(req "$BASE/api/streams/$SID/outcome-coverage")
echo "$COV" | jq '{entries, byObservabilityClass, unidentifiableShare, coverageSelectionBias}'

step "15. 校准评估（删失干预路线）：等级命中/提前量/校准区间/右删失范围"
EVAL=$(req -X POST "$BASE/api/streams/$SID/evaluations" -d '{"route":"CENSORED_INTERVENTION"}')
echo "$EVAL" | jq '{ledgerSize, strictSampleSize, censoredSampleSize, unobservableCount, rightCensoredCount, interventionAffectedCount, unidentifiableShare, rightCensoredRange}'
echo "$EVAL" | jq '.overall | {scored, levelHits, levelHitRate, truePositives, falsePositives, leadTimeAvgMinutes, sensitivityHitLower, sensitivityHitUpper}'
echo "$EVAL" | jq '[.strata[] | {basin, roadType, observationStatus, scored, levelHitRate}]'
echo "$EVAL" | jq '.notes'

step "16. 严格评估（仅独立核验水深）"
req -X POST "$BASE/api/streams/$SID/evaluations" -d '{"route":"STRICT_VERIFIED"}' \
  | jq '{route, strictSampleSize, unidentifiableShare}'

step "16b. 迟到视频标签：按当时（asOf=2天前，视频尚未到达）保留右删失版本"
EVAL_THEN=$(req -X POST "$BASE/api/streams/$SID/evaluations" -d "{\"route\":\"CENSORED_INTERVENTION\",\"asOf\":\"$(date -u -d '2 days ago' +%Y-%m-%dT%H:%M:%SZ)\"}")
echo "$EVAL_THEN" | jq '{asOf, rightCensoredCount, rightCensoredRange, evaluationRunId}'
THEN_RUN=$(echo "$EVAL_THEN" | jq -r '.evaluationRunId')
echo "$EVAL_THEN" | jq -e '.rightCensoredCount >= 1' >/dev/null && echo "当时版本：视频标签按右删失处理（当时不可识别）"
step "16c. 视频到达后重评：按事件时间纳入，并溯源到当时右删失版本"
req -X POST "$BASE/api/streams/$SID/evaluations" -d "{\"route\":\"CENSORED_INTERVENTION\",\"replayOfRunId\":$THEN_RUN}" \
  | jq '{asOf, replayOfRunId, rightCensoredCount, censoredSampleSize}'

step "17. 上线门禁：覆盖选择偏差阻止全流域结论，否则按覆盖+安全下限放行"
GATE=$(req -X POST "$BASE/api/streams/$SID/release-gates" -d '{"minCoverage":0.25,"minStrictSamples":1,"safetyFloor":0.5}')
echo "$GATE" | jq '{decision, measuredCoverage, strictSamples, measuredSafety, coverageBias, allowedRoadIds, blockedRoadIds, reasons}'
echo "$GATE" | jq -e '.coverageBias == true' >/dev/null && echo "覆盖选择偏差已标记（B-SOUTH 仅低风险 R-004 核验）"

echo
echo "SMOKE_OK"
