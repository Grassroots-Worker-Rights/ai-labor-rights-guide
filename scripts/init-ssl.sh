#!/usr/bin/env bash
# init-ssl.sh — First-run Let's Encrypt setup for the production stack.
#
# Issues ONE certificate (named after $DOMAIN) covering $DOMAIN and, if set, $STAGE_DOMAIN. Prod's
# nginx serves both sites with it. Run ONCE on the server, from /opt/apps/ai-labor-rights (the deploy
# workflow copies this script to scripts/ there):
#   bash scripts/init-ssl.sh
#
# Already have a prod-only certificate and just want to add the stage name? Don't run this script —
# see "Add the stage domain to the existing certificate" in docs/CICD.md.
#
# Subsequent renewals are handled automatically by the certbot service.
# Force-renew if ever needed:
#   docker compose -f docker-compose.prod.yml run --rm certbot renew --force-renewal
#
# Requires: docker, docker compose, curl

set -euo pipefail

if [ -f .env ]; then
  # shellcheck disable=SC2046
  export $(grep -v '^#' .env | grep -v '^\s*$' | xargs)
fi

DOMAIN="${DOMAIN:-}"
EMAIL="${EMAIL:-}"

if [ -z "$DOMAIN" ]; then
  echo "Error: DOMAIN is not set. Add DOMAIN=yourdomain.com to your .env file."
  exit 1
fi
if [ -z "$EMAIL" ]; then
  echo "Error: EMAIL is not set. Add EMAIL=admin@yourdomain.com to your .env file."
  exit 1
fi

STAGE_DOMAIN="${STAGE_DOMAIN:-}"
DOMAIN_ARGS="-d $DOMAIN${STAGE_DOMAIN:+ -d $STAGE_DOMAIN}"

COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env"

# Shared network between the prod and stage stacks (declared external in both compose files).
docker network inspect ai-labor-rights-edge > /dev/null 2>&1 || docker network create ai-labor-rights-edge

CERT_DIR="./certbot/conf"
LIVE_DIR="$CERT_DIR/live/$DOMAIN"
WWW_DIR="./certbot/www"

mkdir -p "$CERT_DIR" "$WWW_DIR"

# Step 1: Download Let's Encrypt TLS config files
if [ ! -f "$CERT_DIR/options-ssl-nginx.conf" ]; then
  echo "Downloading options-ssl-nginx.conf..."
  curl -fsSL \
    "https://raw.githubusercontent.com/certbot/certbot/master/certbot-nginx/certbot_nginx/_internal/tls_configs/options-ssl-nginx.conf" \
    -o "$CERT_DIR/options-ssl-nginx.conf"
fi
if [ ! -f "$CERT_DIR/ssl-dhparams.pem" ]; then
  echo "Downloading ssl-dhparams.pem..."
  curl -fsSL \
    "https://raw.githubusercontent.com/certbot/certbot/master/certbot/certbot/ssl-dhparams.pem" \
    -o "$CERT_DIR/ssl-dhparams.pem"
fi

# Step 2: Dummy self-signed cert so nginx can start
if [ ! -d "$LIVE_DIR" ]; then
  echo "Creating temporary self-signed certificate for $DOMAIN..."
  mkdir -p "$LIVE_DIR"
  $COMPOSE run --rm --entrypoint "
    openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
      -keyout /etc/letsencrypt/live/$DOMAIN/privkey.pem \
      -out    /etc/letsencrypt/live/$DOMAIN/fullchain.pem \
      -subj   '/CN=$DOMAIN'" certbot
fi

# Step 3: Start nginx with the dummy cert
echo "Starting nginx..."
$COMPOSE up --force-recreate -d nginx
sleep 5

# Step 4: Replace dummy with a real cert
echo "Removing temporary certificate..."
$COMPOSE run --rm --entrypoint "
  rm -rf /etc/letsencrypt/live/$DOMAIN \
         /etc/letsencrypt/archive/$DOMAIN \
         /etc/letsencrypt/renewal/$DOMAIN.conf" certbot

echo "Requesting Let's Encrypt certificate for: $DOMAIN_ARGS (email: $EMAIL)..."
$COMPOSE run --rm --entrypoint "
  certbot certonly \
    --webroot -w /var/www/certbot \
    --email $EMAIL \
    --agree-tos \
    --no-eff-email \
    --rsa-key-size 4096 \
    --cert-name $DOMAIN \
    $DOMAIN_ARGS" certbot

# Step 5: Reload nginx with the real cert
echo "Reloading nginx..."
$COMPOSE exec nginx nginx -s reload

echo ""
echo "SSL setup complete. Start the full stack:"
echo "  docker compose -f docker-compose.prod.yml up -d"
