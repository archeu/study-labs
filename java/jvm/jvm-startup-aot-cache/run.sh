#!/usr/bin/env bash
# 容器里的 JVM 起得多快：JDK 21 / 25 × 不做 / 懒加载 / CDS / AOT 缓存，每种在 1 核、512 MB 的容器里起 5 次取中位数。
# 用法：./run.sh（约 6 分钟）；./run.sh clean
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-sudo docker}
J21=eclipse-temurin:21.0.13_9-jre-noble
J25=eclipse-temurin:25.0.5_8-jre-noble
RUNS=${RUNS:-5}
OUT=out
if [ "${1:-}" = clean ]; then $DOCKER images --format '{{.Repository}}:{{.Tag}}' | grep '^lab/startup' | xargs -r $DOCKER rmi -f >/dev/null; rm -rf "$OUT"; echo cleaned; exit 0; fi
mkdir -p "$OUT"; : > "$OUT/results.txt"
log() { echo "$*" | tee -a "$OUT/results.txt"; }

b() { # $1=tag $2=target $3=jre
  local t0 t1; t0=$(date +%s.%N)
  $DOCKER build -q --target "$2" --build-arg JRE="$3" -t "lab/startup:$1" . >/dev/null
  t1=$(date +%s.%N); printf '%.0f' "$(echo "$t1 - $t0" | bc)"
}
log "== 构建（训练也在 docker build 里做）"
for spec in "21-plain plain $J21" "21-cds cds $J21" "25-plain plain $J25" "25-aot aot $J25"; do
  set -- $spec; s=$(b "$1" "$2" "$3")
  log "lab/startup:$1  构建 ${s} s，镜像 $($DOCKER image inspect -f '{{.Size}}' lab/startup:$1 | awk '{printf "%.0f", $1/1e6}') MB"
done
log "归档大小：app.jsa $($DOCKER run --rm --entrypoint ls lab/startup:21-cds -la app.jsa | awk '{printf "%.1f MB", $5/1e6}')，app.aot $($DOCKER run --rm --entrypoint ls lab/startup:25-aot -la app.aot | awk '{printf "%.1f MB", $5/1e6}')"

once() { # $1=image $2...=额外 env；输出 "process running for" 的秒数
  local img=$1; shift
  local id; id=$($DOCKER run -d --cpus=1 --memory=512m "$@" "$img")
  for i in $(seq 1 120); do
    l=$($DOCKER logs "$id" 2>&1 | grep -o 'process running for [0-9.]*' | awk '{print $4}' || true)
    [ -n "$l" ] && break; sleep 0.25
  done
  $DOCKER rm -f "$id" >/dev/null; echo "${l:-NA}"
}
median() { tr ' ' '\n' | grep -v '^$' | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}'; }

log ""
log "== 启动耗时：Spring Boot 日志里的 process running for（秒），1 核 512 MB，每种 $RUNS 次"
for spec in "21-plain|JDK 21，什么都不做|" "21-plain|JDK 21，懒加载 Bean|-e SPRING_MAIN_LAZY_INITIALIZATION=true" "21-cds|JDK 21，AppCDS 归档|" "25-plain|JDK 25，什么都不做|" "25-aot|JDK 25，AOT 缓存|"; do
  IFS='|' read -r tag name envs <<< "$spec"
  vals=""; for r in $(seq 1 $RUNS); do vals="$vals $(once lab/startup:$tag $envs)"; done
  log "$(printf '%-24s' "$name") 中位数 $(echo $vals | median) s   （$vals ）"
done

log ""
log "== 归档真的用上了吗：启动到上下文刷新完，有多少个类来自归档"
for spec in "21-plain|java -Xlog:class+load -Dspring.context.exit=onRefresh -jar order-svc.jar" "21-cds|java -Xlog:class+load -XX:SharedArchiveFile=app.jsa -Dspring.context.exit=onRefresh -jar order-svc.jar" "25-aot|java -Xlog:class+load -XX:AOTCache=app.aot -Dspring.context.exit=onRefresh -jar order-svc.jar"; do
  IFS='|' read -r tag cmd <<< "$spec"
  $DOCKER run --rm --entrypoint sh lab/startup:$tag -c "$cmd" > "$OUT/cl-$tag.txt" 2>&1 || true
  total=$(grep -c 'source:' "$OUT/cl-$tag.txt" || true); shared=$(grep -c 'source: shared objects file' "$OUT/cl-$tag.txt" || true)
  log "$tag：加载了 $total 个类，其中 $shared 个来自归档 / 缓存"
done

log ""
log "机器：$(uname -m)，$(nproc) 核，Docker $($DOCKER version -f '{{.Server.Version}}')；$J21，$J25；$(date -u +%F)"
echo "完整结果在 $OUT/results.txt；清理：./run.sh clean"
