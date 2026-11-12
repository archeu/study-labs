#!/usr/bin/env bash
# 每个场景单独起 JVM 跑 N 次（默认 3），只摘关键行。用 JDK 17：JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
set -u
cd "$(dirname "$0")"
N=${N:-3}; OUT=${OUT:-target/ledger}; mkdir -p "$OUT"
CP=target/classes:$(cat "$OUT/cp.txt" 2>/dev/null || (cd ../.. && mvn -q -pl spring/spring-boot-bean-ledger dependency:build-classpath -Dmdep.outputFile=/dev/stdout))
echo "$CP" | cut -d: -f2- > "$OUT/cp.txt"
for s in ${SCENARIOS:-base aop-jdk own-ds no-ds lazy eager-mvc jackson jackson-own}; do
  for i in $(seq 1 "$N"); do
    "${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}/bin/java" -Dlab.scenario=$s -Dlab.out="$OUT" -cp "$CP" \
      labs.spring.spring_boot_bean_ledger.LedgerLab > "$OUT/$s-$i.txt" 2>&1
    echo "== $s #$i"; grep -E '^(启动|自动配置|第 . 个请求|已建好|启动失败|  连接池|  orderService|  jacksonObjectMapper|  objectMapper|  spring.aop)' "$OUT/$s-$i.txt" | cut -c1-170
  done
done
