#!/bin/bash
# 五种版本组合各编译、启动一次，看谁能起来。用法：./run.sh demo（约 3～5 分钟，第一次要下载 Boot 4 的依赖）
# 需要 Nacos：../nacos-registry-config/run.sh up
set -u
cd "$(dirname "$0")"
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}
mkdir -p data
combo() {  # combo <名字> <Boot> <Spring Cloud> <SCA>
  echo "== $1：Boot $2 + Spring Cloud $3 + Spring Cloud Alibaba $4"
  # 用 -f 只构建这一个模块：-D 改的版本属性不会影响仓库里别的模块
  if ! (mvn -q -f pom.xml clean package -DskipTests \
        -Dspring-boot.version=$2 -Dspring-cloud.version=$3 -Dspring-cloud-alibaba.version=$4) > data/build-$1.log 2>&1; then
    echo "  编译失败："; grep -m3 'ERROR.*\.java\|ERROR.*cannot\|ERROR.*找不到\|BUILD FAILURE' data/build-$1.log | sed 's/^/    /' | cut -c1-220; return
  fi
  $JAVA_HOME/bin/java -jar target/cloud-spring-cloud-vs-alibaba-1.0.jar > data/run-$1.log 2>&1
  if grep -q '^RESULT' data/run-$1.log; then grep '^RESULT' data/run-$1.log | sed 's/^RESULT/  /'
  else
    echo "  启动失败："
    grep -A6 'APPLICATION FAILED TO START' data/run-$1.log | grep -v '^\*\|^$\|APPLICATION FAILED' | head -6 | sed 's/^/    /' | cut -c1-240
    grep -m2 'Exception\|Error:' data/run-$1.log | sed 's/^/    /' | cut -c1-240
  fi
}
demo() {
  combo A 3.5.16 2025.0.3 2025.0.0.0
  combo B 4.0.9  2025.0.3 2025.0.0.0
  combo C 4.0.9  2025.1.4 2025.1.0.0
  combo D 4.1.2  2025.1.4 2025.1.0.0
  combo E 3.5.16 2025.1.4 2025.1.0.0
  # 恢复默认组合的构建产物
  mvn -q -f pom.xml clean package -DskipTests > /dev/null 2>&1
}
"${1:-demo}"
