FROM python:3.14-alpine
WORKDIR /app
COPY proxy.py .
USER 65534:65534
CMD ["python", "-IB", "proxy.py"]
