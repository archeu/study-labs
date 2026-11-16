#!/usr/bin/env bash
# 137、143 和那份没加密的密码：在 kind 里看 1 号进程和信号、ConfigMap 更新多久生效、Secret 存在哪、怎么进一个没有工具的容器。
# 集群用 ../k8s-core-objects/cluster.sh。用法：./run.sh（约 4 分钟）；./run.sh clean
set -euo pipefail                      # 出错就停、未定义变量报错、管道里任何一步失败都算失败
cd "$(dirname "$0")"
CORE=../k8s-core-objects
export KUBECONFIG=${KUBECONFIG:-$(cd $CORE && pwd)/.kube/config}
K=${KUBECTL:-kubectl}
OUT=out
cleanup() { $K delete -f k8s/ --ignore-not-found --wait=false >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then cleanup; $K delete pod order-debug --ignore-not-found --wait=false >/dev/null 2>&1 || true; rm -rf "$OUT"; echo cleaned; exit 0; fi
trap 'echo "中断了，清理实验对象"; cleanup' INT TERM   # 脚本被 Ctrl-C 或 SIGTERM 时也收尾
mkdir -p "$OUT"; : > "$OUT/results.txt"
log() { echo "$*" | tee -a "$OUT/results.txt"; }
now() { date +%s.%N; }
el() { printf '%.1f' "$(echo "$2 - $1" | bc)"; }

(cd $CORE && ./cluster.sh up && ./cluster.sh load lab/order-svc-layered:1.8.3 busybox:1.37.0 >/dev/null 2>&1)
cleanup; sleep 2

log "== 1. 谁是 1 号进程，kubectl delete pod 要等多久（terminationGracePeriodSeconds 默认 30）"
$K apply -f k8s/pid1.yaml >/dev/null
for p in pid1-shell pid1-exec; do until $K logs $p 2>/dev/null | grep -q 'Started OrderApp'; do sleep 1; done; done
$K wait --for=condition=Ready pod/pid1-trap --timeout=60s >/dev/null
for p in pid1-shell pid1-exec pid1-trap; do
  pid1=$($K exec $p -- cat /proc/1/cmdline | tr '\0' ' ' | cut -c1-48)
  t0=$(now); $K delete pod $p --wait=true >/dev/null; t1=$(now)
  log "$p：1 号进程「$pid1」，删除用了 $(el "$t0" "$t1") s"
done

log ""
log "== 2. 改 ConfigMap：环境变量、目录挂载、subPath 挂载，各自什么时候看到新值"
$K apply -f k8s/config.yaml >/dev/null; $K wait --for=condition=Ready pod/config-watch --timeout=60s >/dev/null; sleep 3
t0=$(now); $K patch configmap order-config -p '{"data":{"flag":"on"}}' >/dev/null
for i in $(seq 1 150); do
  $K logs config-watch --tail=1 | grep -q 'dir=on' && break; sleep 1
done
sleep 5
first=$($K logs config-watch | awk -v a="${t0%.*}" '$1>=a && $3=="dir=on" {print $1-a; exit}')
log "patch 之后约 ${first:-?} s，目录挂载的文件变成 on；现在：$($K logs config-watch --tail=1 | cut -d' ' -f2-)"
log "  （环境变量和 subPath 要等 Pod 重建才会变）"
$K delete -f k8s/config.yaml --wait=false >/dev/null

log ""
log "== 3. Secret 存在哪、长什么样"
$K apply -f k8s/secret.yaml >/dev/null; $K wait --for=condition=Ready pod/secret-user --timeout=60s >/dev/null
log "kubectl get secret 看到的 data.password：$($K get secret order-db -o jsonpath='{.data.password}')  → base64 -d：$($K get secret order-db -o jsonpath='{.data.password}' | base64 -d)"
log "容器里挂载的类型：$($K exec secret-user -- sh -c 'mount | grep /etc/secrets' | awk '{print $1, $3, $5}')"
log "容器里的环境变量：$($K exec secret-user -- sh -c 'env | grep DB_PASSWORD')"
ETCD=$($K -n kube-system get pod -l component=etcd -o jsonpath='{.items[0].metadata.name}')
raw=$($K -n kube-system exec "$ETCD" -- etcdctl --endpoints=https://127.0.0.1:2379 \
  --cacert=/etc/kubernetes/pki/etcd/ca.crt --cert=/etc/kubernetes/pki/etcd/server.crt --key=/etc/kubernetes/pki/etcd/server.key \
  get /registry/secrets/default/order-db 2>/dev/null | tr -c '[:print:]' '.' || true)
if echo "$raw" | grep -q 's3cr3t-pw'; then log "etcd 里直接搜到了明文 s3cr3t-pw（没配静态加密）"; else log "etcd 里没搜到明文：$(echo "$raw" | grep -o 'k8s:enc:[a-z]*' | head -1)"; fi
$K delete -f k8s/secret.yaml --wait=false >/dev/null

log ""
log "== 4. JRE 镜像里没有 jcmd、jstack、ss：用临时容器（kubectl debug）带着工具进去看"
$K run order-debug --image=lab/order-svc-layered:1.8.3 --restart=Never >/dev/null
$K wait --for=condition=Ready pod/order-debug --timeout=120s >/dev/null
log "应用容器里的工具：$($K exec order-debug -- sh -c 'for t in ps curl jcmd jstack ss; do printf "%s=%s " $t "$(command -v $t >/dev/null && echo 有 || echo 无)"; done')"
$K debug order-debug --image=busybox:1.37.0 --target=order-debug -c dbg -- sh -c 'sleep 2; echo "== ps"; ps -o pid,user,comm | head -5; echo "== 1 号进程的 cgroup 内存上限（这个 Pod 没设 limit）"; cat /proc/1/root/sys/fs/cgroup/memory.max 2>/dev/null || cat /sys/fs/cgroup/memory.max' >/dev/null
sleep 8
$K logs order-debug -c dbg | sed 's/^/   /' | tee -a "$OUT/results.txt"
$K delete pod order-debug --wait=false >/dev/null

log ""
log "机器：$(uname -m)，$(nproc) 核；Kubernetes $($K version -o json | python3 -c 'import sys,json;print(json.load(sys.stdin)["serverVersion"]["gitVersion"])')；$(date -u +%F)"
echo "完整结果在 $OUT/results.txt；清理：./run.sh clean"
