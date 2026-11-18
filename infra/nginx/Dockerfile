FROM nginx:1.30.6-alpine
RUN apk add --no-cache openssl
COPY --chmod=755 local-tls.sh /docker-entrypoint.d/15-local-tls.sh
COPY nginx.conf /etc/nginx/nginx.conf
