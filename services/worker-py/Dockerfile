FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/src

WORKDIR /app

COPY pyproject.toml ./
COPY src ./src

RUN pip install --no-cache-dir . \
    && useradd --system --uid 10001 --no-create-home orbitworker

USER 10001:10001

ENTRYPOINT ["python", "-m", "orbit_worker.main"]
