#!/usr/bin/env bash
# J14 几个实验共用的 kind 集群。用法：./cluster.sh up | down | load <镜像>...
# 需要 kind v0.33.0、kubectl v1.37（PATH 里，或者 KIND= / KUBECTL= 指过去）；docker 要 sudo 时 DOCKER_SUDO=sudo（默认）
set -euo pipefail
cd "$(dirname "$0")"
KIND=${KIND:-kind}; SUDO=${DOCKER_SUDO-sudo}
export KUBECONFIG=${KUBECONFIG:-$PWD/.kube/config}
case "${1:-}" in
  up)
    if $SUDO "$KIND" get clusters 2>/dev/null | grep -qx trade; then echo "cluster trade already up"; else
      mkdir -p .kube
      $SUDO "$KIND" create cluster --config kind-cluster.yaml --image kindest/node:v1.37.0 --kubeconfig "$KUBECONFIG" --wait 120s
      $SUDO chown "$(id -u):$(id -g)" "$KUBECONFIG"
    fi ;;
  load) shift; for img in "$@"; do $SUDO "$KIND" load docker-image --name trade "$img"; done ;;
  down) $SUDO "$KIND" delete cluster --name trade; rm -rf .kube ;;
  *) echo "usage: $0 up|down|load <image>..."; exit 1 ;;
esac
