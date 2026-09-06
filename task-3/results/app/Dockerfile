FROM python:3.12-alpine

WORKDIR /app

RUN apk add --no-cache tzdata \
    && addgroup -g 10001 -S app \
    && adduser -u 10001 -S -G app app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY export.py .
RUN chown -R app:app /app

USER 10001:10001
ENTRYPOINT ["python", "/app/export.py"]
