#!/usr/bin/env bash
# 改一行代码，只推一层：三种 Dockerfile 的镜像大小、重建耗时、推送的层，外加三个坑。
# 用法：./run.sh            （默认用 sudo docker；本机用户在 docker 组里就 DOCKER=docker ./run.sh）
#       ./run.sh clean      （删掉本实验建的镜像、容器和本地仓库）
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-sudo docker}
REG=127.0.0.1:55000
OUT=out
mkdir -p "$OUT"

clean() {
  $DOCKER rm -f lab-registry lab-pid1-shell lab-pid1-exec lab-overlay >/dev/null 2>&1 || true
  $DOCKER images --format '{{.Repository}}:{{.Tag}}' | grep -E '^(lab/|127.0.0.1:55000/)' | xargs -r $DOCKER rmi -f >/dev/null 2>&1 || true
  rm -rf "$OUT"
}
if [ "${1:-}" = clean ]; then clean; echo cleaned; exit 0; fi

log() { echo "$*" | tee -a "$OUT/results.txt"; }
: > "$OUT/results.txt"

# 构建上下文复制一份到 out/ctx，改版本号只改副本
rm -rf "$OUT/ctx" && mkdir -p "$OUT/ctx"
cp -r pom.xml src docker .dockerignore "$OUT/ctx/"

build() { # $1=写法 $2=版本
  local t0 t1
  t0=$(date +%s.%N)
  $DOCKER build -q -f "$OUT/ctx/docker/Dockerfile.$1" -t "lab/order-svc-$1:$2" "$OUT/ctx" > /dev/null
  t1=$(date +%s.%N)
  printf '%.1f' "$(echo "$t1 - $t0" | bc)"
}
size_mb() { $DOCKER image inspect -f '{{.Size}}' "$1" | awk '{printf "%.0f", $1/1000/1000}'; }
layers() { $DOCKER image inspect -f '{{range .RootFS.Layers}}{{println .}}{{end}}' "$1" | grep -v '^$'; }

log "== 1. 第一次构建（1.8.3），镜像大小"
for w in single fatjar layered; do
  s=$(build $w 1.8.3)
  log "$w: 构建 ${s} s，镜像 $(size_mb lab/order-svc-$w:1.8.3) MB，$(layers lab/order-svc-$w:1.8.3 | wc -l) 层"
done
log "基础镜像 eclipse-temurin:21.0.13_9-jre-noble：$(size_mb eclipse-temurin:21.0.13_9-jre-noble) MB，$(layers eclipse-temurin:21.0.13_9-jre-noble | wc -l) 层"
log "-- layered 的 docker history（新加的几层）"
$DOCKER history --format '{{.Size}}\t{{.CreatedBy}}' lab/order-svc-layered:1.8.3 | head -8 | cut -c1-110 | tee -a "$OUT/results.txt"
log "-- fatjar 的 docker history（新加的几层）"
$DOCKER history --format '{{.Size}}\t{{.CreatedBy}}' lab/order-svc-fatjar:1.8.3 | head -3 | cut -c1-110 | tee -a "$OUT/results.txt"

log ""
log "== 2. 改一行代码（VERSION 1.8.3 → 1.8.4）再构建：哪些层变了"
sed -i 's/VERSION = "1.8.3"/VERSION = "1.8.4"/' "$OUT/ctx/src/main/java/labs/cloudnative/docker_image_layers/OrderApp.java"
for w in single fatjar layered; do
  s=$(build $w 1.8.4)
  old=$(layers lab/order-svc-$w:1.8.3); new=$(layers lab/order-svc-$w:1.8.4)
  changed=$(comm -13 <(echo "$old" | sort) <(echo "$new" | sort) | wc -l)
  log "$w: 重建 ${s} s，$(echo "$new" | wc -l) 层里变了 $changed 层"
done

log ""
log "== 3. 推到本地镜像仓库：第二次推送真正传了多少字节（按仓库里压缩后的层大小算）"
$DOCKER rm -f lab-registry >/dev/null 2>&1 || true
$DOCKER run -d --name lab-registry -p $REG:5000 registry:3.0.0 >/dev/null
for i in $(seq 1 30); do curl -sf http://$REG/v2/ >/dev/null && break; sleep 0.5; done
manifest_layers() { # $1=repo $2=tag → "digest size" 每行一层
  curl -sf -H 'Accept: application/vnd.docker.distribution.manifest.v2+json' -H 'Accept: application/vnd.oci.image.manifest.v1+json' \
    "http://$REG/v2/$1/manifests/$2" | python3 -c 'import sys,json; [print(l["digest"], l["size"]) for l in json.load(sys.stdin)["layers"]]'
}
for w in single fatjar layered; do
  for v in 1.8.3 1.8.4; do
    $DOCKER tag lab/order-svc-$w:$v $REG/order-svc-$w:$v
    $DOCKER push -q $REG/order-svc-$w:$v >/dev/null
  done
  a=$(manifest_layers order-svc-$w 1.8.3); b=$(manifest_layers order-svc-$w 1.8.4)
  total=$(echo "$b" | awk '{s+=$2} END{printf "%.1f", s/1e6}')
  pushed=$(echo "$b" | while read -r d s; do echo "$a" | grep -q "^$d " || echo "$s"; done | awk '{s+=$1; n++} END{printf "%d 层 %.2f MB", n, s/1e6}')
  log "$w: 1.8.4 压缩后共 ${total} MB，在 1.8.3 之后要新传 $pushed"
done
log "-- layered 1.8.4 每层压缩后大小（自下而上）"
manifest_layers order-svc-layered 1.8.4 | awk '{printf "  %8.2f MB  %s\n", $2/1e6, substr($1,8,12)}' | tee -a "$OUT/results.txt"

log ""
log "== 4. 坑：后一层删文件，镜像不会变小；构建参数会留在历史里"
cat > "$OUT/ctx/docker/Dockerfile.pitfalls" <<'EOF'
FROM eclipse-temurin:21.0.13_9-jre-noble
ARG DB_PASSWORD
RUN head -c 50000000 /dev/urandom > /tmp/big.bin
RUN rm /tmp/big.bin
RUN echo "connecting with $DB_PASSWORD" > /dev/null
EOF
$DOCKER build -q --build-arg DB_PASSWORD=s3cr3t-pw -f "$OUT/ctx/docker/Dockerfile.pitfalls" -t lab/pitfalls:1 "$OUT/ctx" >/dev/null
log "基础镜像 $(size_mb eclipse-temurin:21.0.13_9-jre-noble) MB → 生成 50 MB 再删掉以后 $(size_mb lab/pitfalls:1) MB"
$DOCKER history --no-trunc --format '{{.Size}}\t{{.CreatedBy}}' lab/pitfalls:1 | head -3 | cut -c1-120 | tee -a "$OUT/results.txt"

log ""
log "== 5. 坑：ENTRYPOINT 写成 shell 形式，java 还是不是 1 号进程，docker stop 要多久"
cat > "$OUT/ctx/docker/Dockerfile.shellform" <<'EOF'
FROM lab/order-svc-layered:1.8.4
ENTRYPOINT java $JAVA_OPTS -jar order-svc.jar
EOF
$DOCKER build -q -f "$OUT/ctx/docker/Dockerfile.shellform" -t lab/order-svc-shellform:1 "$OUT/ctx" >/dev/null
for kind in shell exec; do
  img=lab/order-svc-layered:1.8.4; [ $kind = shell ] && img=lab/order-svc-shellform:1
  $DOCKER rm -f lab-pid1-$kind >/dev/null 2>&1 || true
  $DOCKER run -d --name lab-pid1-$kind $img >/dev/null
  for i in $(seq 1 60); do $DOCKER logs lab-pid1-$kind 2>&1 | grep -q 'Started OrderApp' && break; sleep 0.5; done
  pid1=$($DOCKER exec lab-pid1-$kind cat /proc/1/cmdline | tr '\0' ' ' | cut -c1-60)
  t0=$(date +%s.%N); $DOCKER stop lab-pid1-$kind >/dev/null; t1=$(date +%s.%N)
  code=$($DOCKER inspect -f '{{.State.ExitCode}}' lab-pid1-$kind)
  log "$kind 形式：1 号进程是「$pid1」，docker stop 用了 $(printf '%.1f' "$(echo "$t1 - $t0" | bc)") s，退出码 $code"
  $DOCKER rm -f lab-pid1-$kind >/dev/null
done

log ""
log "== 6. 容器 = 只读的镜像层 + 一层可写层（overlay2）"
$DOCKER rm -f lab-overlay >/dev/null 2>&1 || true
$DOCKER run -d --name lab-overlay lab/order-svc-layered:1.8.4 >/dev/null
sleep 2
lower=$($DOCKER inspect -f '{{.GraphDriver.Data.LowerDir}}' lab-overlay | tr ':' '\n' | wc -l)
log "存储驱动 $($DOCKER inspect -f '{{.GraphDriver.Name}}' lab-overlay)，LowerDir 有 $lower 个目录（镜像层 + 1 个 init 层），UpperDir 是这个容器自己的可写层"
$DOCKER exec -u 0 lab-overlay sh -c 'echo x >> /etc/hostname.bak; echo "# changed" >> /etc/bash.bashrc; rm /etc/legal 2>/dev/null || rm -f /etc/issue'
log "-- docker diff（A 新增、C 改过、D 删除；改过的文件先被整个复制到可写层）"
$DOCKER diff lab-overlay | grep -E '/etc' | tee -a "$OUT/results.txt"
$DOCKER rm -f lab-overlay lab-registry >/dev/null

log ""
log "机器：$(uname -m)，$(nproc) 核，Docker $($DOCKER version -f '{{.Server.Version}}')，$(date -u +%F)"
echo "完整结果在 $OUT/results.txt；清理：./run.sh clean"
