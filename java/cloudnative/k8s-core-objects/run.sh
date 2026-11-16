#!/usr/bin/env bash
# 只说想要什么：在 kind 集群里看 Deployment / ReplicaSet / Service 的控制循环。
# 先在 ../docker-image-layers 跑过 ./run.sh（要用它构建的 lab/order-svc-layered:1.8.3 和 1.8.4）。
# 用法：./run.sh       （约 4 分钟；集群不存在会先建，约 2 分钟）
#       ./run.sh clean （删掉本实验的对象，集群留着给后面几页用；./cluster.sh down 才删集群）
set -euo pipefail
cd "$(dirname "$0")"
export KUBECONFIG=${KUBECONFIG:-$PWD/.kube/config}
K=${KUBECTL:-kubectl}
OUT=out
if [ "${1:-}" = clean ]; then $K delete -f k8s/ --ignore-not-found >/dev/null; rm -rf "$OUT"; echo cleaned; exit 0; fi
mkdir -p "$OUT"; : > "$OUT/results.txt"
log() { echo "$*" | tee -a "$OUT/results.txt"; }
now() { date +%s.%N; }
el() { printf '%.1f' "$(echo "$2 - $1" | bc)"; }
pods() { $K get pods -l app=order-svc -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'; }
ready_pods() { $K get pods -l app=order-svc -o jsonpath='{range .items[*]}{.metadata.name} {.metadata.deletionTimestamp} {.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' | awk 'NF==2 && $2=="True"{print $1}'; }
ready_eps() { $K get endpointslices -l kubernetes.io/service-name=order-svc -o jsonpath='{range .items[*].endpoints[*]}{.conditions.ready}{"\n"}{end}' | grep -c true || true; }
version() { for i in 1 2 3; do r=$($K exec client -- wget -q -T 2 -O- http://order-svc/api/orders/1 2>/dev/null) && { echo "$r" | sed 's/.*servedBy":"\([^"]*\)".*/\1/'; return; }; sleep 1; done; echo "?"; }
fails_between() { $K logs client | awk -v a="${1%.*}" -v b="${2%.*}" '$1>=a && $1<=b {n[$2]++} END{printf "%d 次成功，%d 次失败", n["ok"], n["fail"]}'; }

for v in 1.8.3 1.8.4; do
  sudo docker image inspect lab/order-svc-layered:$v >/dev/null 2>&1 || { echo "缺镜像 lab/order-svc-layered:$v：先在 ../docker-image-layers 跑 ./run.sh"; exit 1; }
done
./cluster.sh up
./cluster.sh load lab/order-svc-layered:1.8.3 lab/order-svc-layered:1.8.4 busybox:1.37.0 >/dev/null 2>&1
$K delete -f k8s/ --ignore-not-found --wait >/dev/null 2>&1 || true

log "== 1. kubectl apply：只交一份“想要 4 个 1.8.3”，看控制器把它变成现实"
t0=$(now); $K apply -f k8s/order-svc.yaml >/dev/null
$K rollout status deploy/order-svc --timeout=300s >/dev/null; t1=$(now)
log "从 apply 到 4/4 Ready：$(el "$t0" "$t1") s（4 个 JVM 同时在 2 核上启动）"
$K get pods -l app=order-svc -o wide --no-headers | awk '{printf "  %-34s %s\n", $1, $7}' | tee -a "$OUT/results.txt"
log "  对象链：$($K get deploy order-svc -o jsonpath='{.metadata.name}') → ReplicaSet $($K get rs -l app=order-svc -o jsonpath='{.items[0].metadata.name}') → 4 个 Pod；Service 的 EndpointSlice 里有 $(ready_eps) 个就绪地址"
$K apply -f k8s/client.yaml >/dev/null
$K wait --for=condition=Ready pod/client --timeout=120s >/dev/null
sleep 3
log "  client 经 Service 拿到的版本：$(version)"

log ""
log "== 2. 删掉一个 Pod：ReplicaSet 发现只剩 3 个，补一个新的"
before=$(pods); victim=$(echo "$before" | head -1)
t0=$(now); $K delete pod "$victim" --wait=false >/dev/null
created=""; until [ -n "$created" ]; do created=$(pods | grep -vxF "$before" | head -1 || true); [ -n "$created" ] || sleep 0.1; done
t1=$(now)
until ready_pods | grep -qx "$created"; do sleep 0.2; done; t2=$(now)
log "删除 $victim → $(el "$t0" "$t1") s 后新 Pod $created 被创建 → $(el "$t0" "$t2") s 后它 Ready"
$K get events --field-selector involvedObject.kind=ReplicaSet --sort-by=.lastTimestamp -o custom-columns=REASON:.reason,MSG:.message --no-headers | tail -1 | sed 's/^/  事件：/' | tee -a "$OUT/results.txt"
sleep 2; log "  这段时间 client：$(fails_between "$t0" "$(now)")"

log ""
log "== 3. 把一个 Pod 的标签改掉：它就不归 Deployment 管，也不接 Service 的流量了"
p=$(ready_pods | head -1)
$K label pod "$p" app=debug --overwrite >/dev/null
sleep 3
log "改标签的 $p 状态：$($K get pod "$p" -o jsonpath='{.status.phase}')；Service 就绪地址：$(ready_eps) 个；app=order-svc 的 Pod：$(pods | wc -l) 个（ReplicaSet 又补了一个）"
log "  它还挂着 ownerReferences 吗：$($K get pod "$p" -o jsonpath='{.metadata.ownerReferences[0].kind}' || true)（空 = 已经被 ReplicaSet 放弃）"
$K delete pod "$p" --wait=false >/dev/null
$K rollout status deploy/order-svc --timeout=120s >/dev/null

sample() { # 每秒记一次：各 ReplicaSet 的就绪/期望 + Service 就绪地址数
  while true; do
    printf '%s  %s  endpoints=%s\n' "$(date +%T)" \
      "$($K get rs -l app=order-svc -o jsonpath='{range .items[*]}{.spec.template.spec.containers[0].image}={.status.readyReplicas}/{.spec.replicas} {end}' | sed 's#lab/order-svc-layered:##g')" \
      "$(ready_eps)"
    sleep 1
  done
}

log ""
log "== 4. 改镜像 tag 1.8.3 → 1.8.4：新 ReplicaSet 一个个扩，老的一个个缩"
sleep 3
sample > "$OUT/rollout.txt" 2>/dev/null & SP=$!
t0=$(now); $K set image deploy/order-svc app=lab/order-svc-layered:1.8.4 >/dev/null
$K rollout status deploy/order-svc --timeout=300s >/dev/null; t1=$(now)
sleep 2; kill $SP; wait $SP 2>/dev/null || true
log "滚动用了 $(el "$t0" "$t1") s；这段时间 client：$(fails_between "$t0" "$t1")；现在的版本：$(version)"
log "-- 每秒一行：镜像=就绪/期望（前面是新 ReplicaSet 还是老的，按名字排）"
awk 'NR%3==1' "$OUT/rollout.txt" | tee -a "$OUT/results.txt"
log "  ReplicaSet：$($K get rs -l app=order-svc -o jsonpath='{range .items[*]}{.metadata.name}={.spec.replicas} {end}')（老的缩到 0，没删）"

log ""
log "== 5. kubectl rollout undo：把老 ReplicaSet 扩回来"
sleep 3
t0=$(now); $K rollout undo deploy/order-svc 2>&1 >/dev/null | sed "s/^/  kubectl 提示：/" | cut -c1-140 | tee -a "$OUT/results.txt"
$K rollout status deploy/order-svc --timeout=300s >/dev/null; t1=$(now)
log "回滚用了 $(el "$t0" "$t1") s；这段时间 client：$(fails_between "$t0" "$t1")；现在的版本：$(version)"
$K rollout history deploy/order-svc | tail -n +2 | sed 's/^/  /' | tee -a "$OUT/results.txt"

log ""
log "== 6. 有人手动 scale 到 2，再 apply 一次 YAML：以声明为准"
$K scale deploy/order-svc --replicas=2 >/dev/null; sleep 1
log "scale 之后 spec.replicas=$($K get deploy order-svc -o jsonpath='{.spec.replicas}')"
$K apply -f k8s/order-svc.yaml >/dev/null
log "apply 之后 spec.replicas=$($K get deploy order-svc -o jsonpath='{.spec.replicas}')，镜像=$($K get deploy order-svc -o jsonpath='{.spec.template.spec.containers[0].image}')（YAML 里写的是 1.8.3）"
$K rollout status deploy/order-svc --timeout=300s >/dev/null

log ""
log "机器：$(uname -m)，$(nproc) 核；kind $(${KIND:-kind} version | awk '{print $2}')，Kubernetes $($K version -o json | python3 -c 'import sys,json;print(json.load(sys.stdin)["serverVersion"]["gitVersion"])')；$(date -u +%F)"
echo "完整结果在 $OUT/results.txt；清理：./run.sh clean"
