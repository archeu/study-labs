# 多阶段：第一阶段用 uv 按锁文件装依赖，第二阶段只拷虚拟环境和代码
FROM python:3.13.18-slim-bookworm AS build
COPY --from=ghcr.io/astral-sh/uv:0.13.10 /uv /bin/uv
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
COPY app/pyproject.toml app/uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY app/ ./

FROM python:3.13.18-slim-bookworm
WORKDIR /app
COPY --from=build /app /app
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
# 不用 root；密钥不进镜像，运行时从 K8s Secret 注入成环境变量
USER 10001
# exec 形式：python 就是 1 号进程，能直接收到 SIGTERM
CMD ["python", "worker.py"]
