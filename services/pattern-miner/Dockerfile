# syntax=docker/dockerfile:1
# TBD: minimal scaffold. Fill in once src/ exists (kickoff build stage).
FROM python:3.13-slim

WORKDIR /app

# requirements.txt to be added by the dev agent
# COPY requirements.txt .
# RUN pip install --no-cache-dir -r requirements.txt

COPY src/ ./src/

# EXPOSE 8000        # /health + /metrics
# CMD ["python", "-m", "src.main"]
