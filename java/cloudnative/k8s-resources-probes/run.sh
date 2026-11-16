#!/usr/bin/env bash
# 装得下、活着、能干活：在 kind 集群里测 requests 调度、两种 OOM、慢启动探针、HPA。
# 集群用 ../k8s-core-objects/cluster.sh（J14 共用）。用法：./run.sh（约 8 分钟）；./run.sh clean
set -euo pipefail
cd "$(dirname "$0")"
CORE=../k8s-core-objects
export KUBECONFIG=${KUBECONFIG:-$(cd $CORE && pwd)/.kube/config}
K=${KUBECTL:-kubectl}
OUT=out
IMG=lab/order-svc-chaos:1
MS_VER=v0.9.1
if [ "${1:-}" = clean ]; then $K delete -f k8s/ --ignore-not-found --wait=false >/dev/null; rm -rf "$OUT"; echo cleaned; exit 0; fi
mkdir -p "$OUT"; : > "$OUT/results.txt"
log() { echo "$*" | tee -a "$OUT/results.txt"; }
now() { date +%s.%N; }
el() { printf '%.0f' "$(echo "$2 - $1" | bc)"; }
pip() { $K get pod "$1" -o jsonpath='{.status.podIP}'; }
last() { $K get pod "$1" -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason} exit={.status.containerStatuses[0].lastState.terminated.exitCode} restarts={.status.containerStatuses[0].restartCount} now={.status.phase}'; }

${DOCKER:-sudo docker} build -q -t $IMG . >/dev/null
(cd $CORE && ./cluster.sh up && ./cluster.sh load $IMG busybox:1.37.0 >/dev/null 2>&1)
$K delete -f k8s/ --ignore-not-found --wait >/dev/null 2>&1 || true
$K apply -f k8s/toolbox.yaml >/dev/null

log "== 1. 调度只看 requests：requests 写成 16Gi"
$K apply -f k8s/big-request.yaml >/dev/null; sleep 6
log "big-request：$($K get pod big-request -o jsonpath='{.status.phase}')；$($K get events --field-selector involvedObject.name=big-request,reason=FailedScheduling -o jsonpath='{.items[-1:].message}' | cut -c1-130)"
$K delete pod big-request --wait=false >/dev/null

log ""
log "== 2. 两种 OOM：堆上限 = limit（-Xmx384m） 对 堆上限 = limit 的 75%（MaxRAMPercentage=75），limit 都是 384Mi"
$K apply -f k8s/oom-xmx.yaml -f k8s/oom-pct.yaml >/dev/null
$K wait --for=condition=Ready pod/oom-xmx pod/oom-pct pod/toolbox --timeout=180s >/dev/null
log "QoS：oom-xmx=$($K get pod oom-xmx -o jsonpath='{.status.qosClass}')（内存 requests = limits，但 CPU 是 100m 对 1，所以不是 Guaranteed）"
for p in oom-xmx oom-pct; do
  log "-- $p：JVM 看到的 $($K exec toolbox -- wget -q -O- http://$(pip $p):8080/api/jvm)"
done
for p in oom-xmx oom-pct; do
  ip=$(pip $p); log "-- $p：每次多留住 32 MB"
  for i in $(seq 1 14); do
    r=$($K exec toolbox -- wget -q -T 10 -O- --post-data= "http://$ip:8080/api/debug/hog?mb=32" 2>&1 || true)
    log "   第 $i 次：$(echo "$r" | tr -d '\n' | cut -c1-90)"
    sleep 1
    rc=$($K get pod $p -o jsonpath='{.status.containerStatuses[0].restartCount}')
    [ "$rc" != 0 ] && { log "   → 容器重启了"; break; }
    echo "$r" | grep -q 'heldMb' || { log "   → 请求出错，停止"; break; }
  done
  sleep 3
  log "   结果：$(last $p)"
  if [ "$($K get pod $p -o jsonpath='{.status.containerStatuses[0].restartCount}')" != 0 ]; then
    log "   上一个容器的日志里 OutOfMemoryError 出现 $($K logs $p --previous 2>/dev/null | grep -c OutOfMemoryError || true) 次"
  else
    log "   日志里 OutOfMemoryError 出现 $($K logs $p | grep -c OutOfMemoryError || true) 次：$($K logs $p | grep -m1 -o 'java.lang.OutOfMemoryError[^"]*' | cut -c1-60)"
    log "   之后再调普通接口：$($K exec toolbox -- wget -q -T 5 -O- http://$ip:8080/api/orders/1 2>&1 | cut -c1-60)"
  fi
done
$K delete -f k8s/oom-xmx.yaml -f k8s/oom-pct.yaml --wait=false >/dev/null

log ""
log "== 3. 启动要 40 秒：只配 liveness 对 加上 startupProbe（各观察 100 秒）"
t0=$(now); $K apply -f k8s/slow-liveness.yaml -f k8s/slow-startup.yaml >/dev/null
ready_at=""
for i in $(seq 1 50); do
  sleep 2
  if [ -z "$ready_at" ] && [ "$($K get pod slow-startup -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')" = True ]; then ready_at=$(el "$t0" "$(now)"); fi
done
log "slow-liveness：$(last slow-liveness)，当前等待原因：$($K get pod slow-liveness -o jsonpath='{.status.containerStatuses[0].state.waiting.reason}')"
log "  事件：$($K get events --field-selector involvedObject.name=slow-liveness,reason=Unhealthy -o jsonpath='{.items[-1:].message}' | cut -c1-110)"
log "slow-startup：restarts=$($K get pod slow-startup -o jsonpath='{.status.containerStatuses[0].restartCount}')，apply 后 ${ready_at:-?} s Ready"
$K delete -f k8s/slow-liveness.yaml -f k8s/slow-startup.yaml --wait=false >/dev/null

log ""
log "== 4. HPA：CPU 目标 50%（相对 requests 200m），1～5 个副本"
if ! $K -n kube-system get deploy metrics-server >/dev/null 2>&1; then
  $K apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/$MS_VER/components.yaml >/dev/null
  $K -n kube-system patch deploy metrics-server --type=json -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' >/dev/null
fi
$K -n kube-system rollout status deploy/metrics-server --timeout=180s >/dev/null
$K apply -f k8s/hpa.yaml >/dev/null; $K rollout status deploy/order-hpa --timeout=180s >/dev/null
until $K top pod -l app=order-hpa >/dev/null 2>&1; do sleep 5; done
sleep 20
$K apply -f k8s/load.yaml >/dev/null
t0=$(now)
log "-- 每 15 秒一行：秒数  当前 CPU/目标  副本数（加压 150 秒，然后撤掉）"
for i in $(seq 1 20); do
  [ $i = 11 ] && { $K delete pod load --wait=false >/dev/null; log "   ($(el "$t0" "$(now)") s 撤掉压测)"; }
  log "   $(el "$t0" "$(now)") s  $($K get hpa order-hpa -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}')%/50%  replicas=$($K get hpa order-hpa -o jsonpath='{.status.currentReplicas}')→$($K get hpa order-hpa -o jsonpath='{.status.desiredReplicas}')"
  sleep 15
done
$K get events --field-selector involvedObject.kind=HorizontalPodAutoscaler -o custom-columns=MSG:.message --no-headers | tail -4 | sed 's/^/   事件：/' | tee -a "$OUT/results.txt"
$K delete -f k8s/hpa.yaml -f k8s/toolbox.yaml --wait=false >/dev/null

log ""
log "机器：$(uname -m)，$(nproc) 核；Kubernetes $($K version -o json | python3 -c 'import sys,json;print(json.load(sys.stdin)["serverVersion"]["gitVersion"])')，metrics-server $MS_VER；$(date -u +%F)"
echo "完整结果在 $OUT/results.txt；清理：./run.sh clean"
