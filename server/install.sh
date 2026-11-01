#!/usr/bin/env bash
# Установка сервера Claude Обход (служба claude-relay) на VDS (Ubuntu/Debian). Запускать от root.
#   bash install.sh relay.example.com          — домен, порт 443
#   bash install.sh 203.0.113.7 8443           — по IP и на другом порту
#     (другой порт нужен, если 443 уже занят, например VPN на том же VDS)
set -euo pipefail

DOMAIN="${1:-}"
PORT="${2:-443}"
if [ -z "$DOMAIN" ]; then
  echo "Использование: bash install.sh relay.example.com [порт]"
  exit 1
fi
if ! printf '%s' "$PORT" | grep -Eq '^[0-9]{1,5}$' || [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
  echo "Порт должен быть числом 1–65535, а не «$PORT»"
  exit 1
fi

if [ "$(id -u)" != "0" ]; then
  echo "Запустите от root: sudo bash install.sh $DOMAIN"
  exit 1
fi

# работаем относительно самого скрипта
cd "$(dirname "$0")"

echo "→ пакеты"
apt-get update -qq
apt-get install -y -qq curl ca-certificates certbot openssl

echo "→ Node.js 20"
if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 20 ]; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y -qq nodejs
fi

echo "→ каталог"
install -d -m 755 /opt/claude-relay
cp -r ./src ./package.json ./config.example.json /opt/claude-relay/
cd /opt/claude-relay

echo "→ сертификат"
IS_IP=0
if printf '%s' "$DOMAIN" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$'; then IS_IP=1; fi

if [ "$IS_IP" = "1" ]; then
  echo "  IP без домена → самоподписанный сертификат"
  echo "  (в приложении включите «Разрешить самоподписанный сертификат»)"
  install -d -m 700 /etc/claude-relay/tls
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -subj "/CN=$DOMAIN" -addext "subjectAltName=IP:$DOMAIN" \
    -keyout /etc/claude-relay/tls/key.pem \
    -out /etc/claude-relay/tls/cert.pem 2>/dev/null
  chmod 600 /etc/claude-relay/tls/key.pem
else
  # certbot --standalone требует свободный 80: останавливаем nginx/apache, если они есть
  for svc in nginx apache2 httpd caddy; do
    if systemctl is-active --quiet "$svc" 2>/dev/null; then
      echo "  останавливаю $svc (освобождаю 80/443)"
      systemctl stop "$svc"
      systemctl disable "$svc" >/dev/null 2>&1 || true
    fi
  done

  if [ ! -d "/etc/letsencrypt/live/$DOMAIN" ]; then
    if ! certbot certonly --standalone -d "$DOMAIN" --non-interactive --agree-tos \
      --register-unsafely-without-email; then
      echo
      echo "  Не удалось получить сертификат. Проверьте:"
      echo "   • A-запись $DOMAIN указывает на этот сервер;"
      echo "   • порт 80 открыт (firewall/security group)."
      echo "  Либо запустите установку по IP: sudo bash install.sh $(curl -s4 ifconfig.me || echo 1.2.3.4)"
      exit 1
    fi
  fi
fi

echo "→ конфиг"
if [ ! -f config.json ]; then
  # RELAY_TOKEN=... — свой заранее заданный токен (например, уже вшитый в личную сборку APK)
  TOKEN="${RELAY_TOKEN:-$(openssl rand -hex 24)}"
  if [ "${#TOKEN}" -lt 16 ] || ! printf '%s' "$TOKEN" | grep -Eq '^[A-Za-z0-9._~-]+$'; then
    echo "RELAY_TOKEN: нужно от 16 символов, только латиница/цифры/._~-"
    exit 1
  fi
  if [ "$IS_IP" = "1" ]; then
    cat > config.json <<JSON
{
  "listen": "0.0.0.0:$PORT",
  "publicUrl": "https://$DOMAIN",
  "tls": {
    "mode": "manual",
    "domain": "$DOMAIN",
    "certFile": "/etc/claude-relay/tls/cert.pem",
    "keyFile": "/etc/claude-relay/tls/key.pem"
  },
  "auth": { "tokens": ["$TOKEN"] },
  "dns": ["9.9.9.11", "1.1.1.1"]
}
JSON
  else
    sed -e "s|relay.example.com|$DOMAIN|g" \
        -e "s|ВСТАВЬТЕ_СВОЙ_ТОКЕН_СЮДА_МИН_16_СИМВОЛОВ|$TOKEN|" \
        -e "s|\"0.0.0.0:443\"|\"0.0.0.0:$PORT\"|" \
        config.example.json > config.json
  fi
  chmod 600 config.json
else
  echo "  config.json уже есть — не трогаю"
fi

echo "→ systemd"
cat > /etc/systemd/system/claude-relay.service <<UNIT
[Unit]
Description=claude-relay
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/claude-relay
ExecStart=/usr/bin/node src/index.mjs start
Restart=always
RestartSec=3
Environment=NODE_ENV=production

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now claude-relay
sleep 2
systemctl --no-pager --lines=10 status claude-relay || true

if [ "$IS_IP" != "1" ]; then
  echo "→ автопродление сертификата"
  cat > /etc/systemd/system/certbot-renew.service <<'UNIT'
[Unit]
Description=Renew Let's Encrypt and restart claude-relay

[Service]
Type=oneshot
ExecStart=/usr/bin/certbot renew --quiet --deploy-hook "systemctl restart claude-relay"
UNIT

  cat > /etc/systemd/system/certbot-renew.timer <<'UNIT'
[Unit]
Description=Daily certbot renew

[Timer]
OnCalendar=*-*-* 03:15:00
RandomizedDelaySec=3600

[Install]
WantedBy=timers.target
UNIT

  systemctl daemon-reload
  systemctl enable --now certbot-renew.timer
fi

LISTEN_PORT="$(node -p 'String(require("/opt/claude-relay/config.json").listen).split(":").pop()')"
SUFFIX=""; [ "$LISTEN_PORT" != "443" ] && SUFFIX=":$LISTEN_PORT"

echo
echo "Готово. Проверка:"
echo "  curl -sk https://$DOMAIN$SUFFIX/healthz"
echo "  cd /opt/claude-relay && node src/index.mjs doctor"
echo
echo "════════════════════════════════════════════════════════════"
echo "  ССЫЛКА ДЛЯ ПРИЛОЖЕНИЯ (раздайте её тем, кому даёте доступ):"
echo
node src/index.mjs link | sed 's/^/  /'
echo
echo "  Человек ставит приложение Claude Обход и просто вставляет ссылку."
if [ "$IS_IP" = "1" ]; then
  echo "  Сертификат самоподписанный — в приложении включить"
  echo "  «Разрешить самоподписанный сертификат»."
fi
echo "  Показать ссылку ещё раз: cd /opt/claude-relay && node src/index.mjs link"
echo "════════════════════════════════════════════════════════════"
