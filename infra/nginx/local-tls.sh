#!/bin/sh
set -eu
cd /etc/nginx/local-tls
if [ -s localhost.key ] && openssl x509 -checkend 86400 -noout -in localhost.crt; then
    exit 0
fi
umask 077
openssl req -x509 -nodes -newkey rsa:2048 -sha256 -days 90 \
    -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
    -keyout localhost.key -out localhost.crt
