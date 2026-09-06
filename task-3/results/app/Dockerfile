FROM python:3.12-alpine

WORKDIR /app

RUN addgroup -S app && adduser -S -G app app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY export.py .
RUN chown -R app:app /app

USER app
ENTRYPOINT ["python", "/app/export.py"]
