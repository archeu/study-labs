#!/usr/bin/env bash
# 换版本不断流、出事能秒回：在 kind 里比较滚动（有无 preStop）、蓝绿切换和回切、按副本数的金丝雀。
# 镜像用 ../docker-image-layers 构建的 lab/order-svc-layered:1.8.3 / 1.8.4，集群用 ../k8s-core-objects/cluster.sh。
# 用法：./run.sh（约 6 分钟）；./run.sh clean
set -euo pipefail
cd "$(dirname "$0")"
CORE=../k8s-core-objects
export KUBECONFIG=${KUBECONFIG:-$(cd $CORE && pwd)/.kube/config}
K=${KUBECTL:-kubectl}
OUT=out
if [ "${1:-}" = clean ]; then $K delete -f k8s/ --ignore-not-found --wait=false >/dev/null; rm -rf "$OUT"; echo cleaned; exit 0; fi
mkdir -p "$OUT"; : > "$OUT/results.txt"
log() { echo "$*" | tee -a "$OUT/results.txt"; }
now() { date +%s.%N; }
el() { printf '%.1f' "$(echo "$2 - $1" | bc)"; }
count() { # $1 起 $2 止（epoch 秒）：各版本和 fail 的次数
  $K logs client | awk -v a="${1%.*}" -v b="${2%.*}" '$1>=a && $1<=b {n[$2]++; t++} END{printf "共 %d 次：1.8.3=%d 1.8.4=%d fail=%d", t, n["1.8.3"], n["1.8.4"], n["fail"]}'
}
client() { # $1=URL
  $K delete pod client --ignore-not-found --wait >/dev/null 2>&1 || true
  sed "s#http://order/api/orders/1#$1#" k8s/client.yaml | $K apply -f - >/dev/null
  $K wait --for=condition=Ready pod/client --timeout=60s >/dev/null; sleep 3
}
switched_at() { # 从 $1 起，客户端第一次连续 20 次都拿到 $2 的时刻（相对秒）
  $K logs client | awk -v a="${1%.*}" -v v="$2" '$1>=a { if ($2==v) {c++; if (c==1) s=$1; if (c==20) {print s-a; exit}} else c=0 }'
}

(cd $CORE && ./cluster.sh up && ./cluster.sh load lab/order-svc-layered:1.8.3 lab/order-svc-layered:1.8.4 busybox:1.37.0 >/dev/null 2>&1)
$K delete -f k8s/ --ignore-not-found --wait >/dev/null 2>&1 || true

log "== 1. 滚动发布：没有 preStop 对 有 preStop（4 副本，maxSurge 1 / maxUnavailable 0）"
$K apply -f k8s/rolling.yaml >/dev/null; $K rollout status deploy/order --timeout=300s >/dev/null
client http://order/api/orders/1
t0=$(now); $K set image deploy/order app=lab/order-svc-layered:1.8.4 >/dev/null; $K rollout status deploy/order --timeout=300s >/dev/null; t1=$(now); sleep 2
log "没有 preStop：1.8.3 → 1.8.4 用了 $(el "$t0" "$t1") s；$(count "$t0" "$(now)")"
$K apply -f k8s/rolling-prestop.yaml >/dev/null; $K rollout status deploy/order --timeout=300s >/dev/null; sleep 8
t0=$(now); $K set image deploy/order app=lab/order-svc-layered:1.8.4 >/dev/null; $K rollout status deploy/order --timeout=300s >/dev/null; t1=$(now); sleep 8
log "有 preStop（sleep 5）：1.8.3 → 1.8.4 用了 $(el "$t0" "$t1") s；$(count "$t0" "$(now)")"
t0=$(now); $K rollout undo deploy/order >/dev/null 2>&1; $K rollout status deploy/order --timeout=300s >/dev/null; t1=$(now); sleep 8
log "回滚（rollout undo，有 preStop）：用了 $(el "$t0" "$t1") s；$(count "$t0" "$(now)")"
$K delete -f k8s/rolling.yaml --wait=false >/dev/null

log ""
log "== 2. 蓝绿：两套各 4 个都起好，改 Service 的 selector 一下切过去，再切回来"
$K apply -f k8s/bluegreen.yaml >/dev/null
$K rollout status deploy/order-blue --timeout=300s >/dev/null; $K rollout status deploy/order-green --timeout=300s >/dev/null
log "同时在跑的 Pod：$($K get pods -l app=order-bg --no-headers | wc -l) 个（平时的两倍）"
client http://order-bg/api/orders/1
t0=$(now); $K patch svc order-bg -p '{"spec":{"selector":{"app":"order-bg","color":"green"}}}' >/dev/null; sleep 10
log "切到绿：patch 后 $(switched_at "$t0" 1.8.4) s 起连续拿到 1.8.4；10 秒里 $(count "$t0" "$(now)")"
t0=$(now); $K patch svc order-bg -p '{"spec":{"selector":{"app":"order-bg","color":"blue"}}}' >/dev/null; sleep 10
log "切回蓝（回滚）：patch 后 $(switched_at "$t0" 1.8.3) s 起连续拿到 1.8.3；10 秒里 $(count "$t0" "$(now)")"
$K delete -f k8s/bluegreen.yaml --wait=false >/dev/null

log ""
log "== 3. 金丝雀（按副本数）：4 个 1.8.3 + 1 个 1.8.4，同一个 Service"
$K apply -f k8s/canary.yaml >/dev/null
$K rollout status deploy/order-stable --timeout=300s >/dev/null; $K rollout status deploy/order-canary --timeout=300s >/dev/null
client http://order-c/api/orders/1
t0=$(now); sleep 30
log "30 秒里 $(count "$t0" "$(now)")"
$K logs client | awk -v a="${t0%.*}" '$1>=a {n[$2]++; t++} END{printf "新版占比 %.1f%%（按副本数应为 20%%）\n", n["1.8.4"]*100/t}' | tee -a "$OUT/results.txt"
$K delete -f k8s/canary.yaml --wait=false >/dev/null; $K delete pod client --wait=false >/dev/null

log ""
log "机器：$(uname -m)，$(nproc) 核；Kubernetes $($K version -o json | python3 -c 'import sys,json;print(json.load(sys.stdin)["serverVersion"]["gitVersion"])')；$(date -u +%F)"
echo "完整结果在 $OUT/results.txt；清理：./run.sh clean"
