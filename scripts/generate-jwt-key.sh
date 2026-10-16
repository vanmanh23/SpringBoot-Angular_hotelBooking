#!/usr/bin/env bash
# Prints a new RSA-2048 private key as single-line base64 PKCS#8 DER, the format of AUTH_JWT_PRIVATE_KEY.
# Usage: echo "AUTH_JWT_PRIVATE_KEY=$(scripts/generate-jwt-key.sh)" >> .env
# Keep the output secret. Rotating it invalidates all issued access tokens (users simply sign in again).
set -euo pipefail
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null \
  | openssl pkcs8 -topk8 -nocrypt -outform DER \
  | base64 | tr -d '\n'
echo
