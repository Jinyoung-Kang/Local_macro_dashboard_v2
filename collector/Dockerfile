# 수집기 이미지 (Python 3.11 + FastAPI)
FROM python:3.11-slim

# pykrx·lxml 빌드와 TLS 검증에 필요한 최소 패키지만 설치합니다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential libxml2-dev libxslt1-dev ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && apt-get purge -y build-essential && apt-get autoremove -y

COPY app ./app

ENV PYTHONUNBUFFERED=1 \
    TZ=Asia/Seoul

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
