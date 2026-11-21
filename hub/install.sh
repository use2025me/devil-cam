#!/usr/bin/env bash
# Devil Cam Hub installer for Raspberry Pi (Raspberry Pi OS, 64-bit).
#
# What it sets up:
#   - go2rtc   : turns the recorder's camera video into a format the Echo Show accepts (HLS)
#   - Caddy    : only lets in requests that carry your secret key
#   - cloudflared : a secure Cloudflare tunnel, so the Echo Show can reach the hub
#                   without opening any ports on your router
#
# Run it on the Pi with:
#   curl -fsSL https://raw.githubusercontent.com/use2025me/devil-cam/main/hub/install.sh | sudo bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Please run with sudo:  curl -fsSL <link> | sudo bash"
  exit 1
fi

ARCH="$(uname -m)"
case "$ARCH" in
  aarch64|arm64) GO2RTC_ASSET="go2rtc_linux_arm64"; CF_ASSET="cloudflared-linux-arm64" ;;
  armv7l|armv6l) GO2RTC_ASSET="go2rtc_linux_arm";   CF_ASSET="cloudflared-linux-arm" ;;
  x86_64)        GO2RTC_ASSET="go2rtc_linux_amd64"; CF_ASSET="cloudflared-linux-amd64" ;;
  *) echo "Sorry, this kind of computer ($ARCH) isn't supported."; exit 1 ;;
esac

ask() { # ask "Question" default -> answer
  local q="$1" def="${2:-}" a
  if [ -n "$def" ]; then read -r -p "$q [$def]: " a </dev/tty; echo "${a:-$def}";
  else read -r -p "$q: " a </dev/tty; echo "$a"; fi
}
ask_secret() {
  local q="$1" a
  read -r -s -p "$q: " a </dev/tty; echo >&2; echo "$a"
}

echo
echo "=== Devil Cam Hub setup ==="
echo "You'll need: your recorder's address, the Devil Cam login (e.g. devilcam),"
echo "and the Cloudflare tunnel token (see the guide)."
echo

REC_IP="$(ask "Recorder address" "192.168.68.57")"
REC_USER="$(ask "Recorder username" "devilcam")"
REC_PASS="$(ask_secret "Recorder password")"
CAMS="$(ask "Which cameras to show on Alexa (numbers, separated by spaces)" "1")"
CF_TOKEN="$(ask_secret "Cloudflare tunnel token (paste it, then press Enter)")"

echo
echo "Installing… this takes a few minutes."
apt-get update -qq
apt-get install -y -qq caddy ffmpeg python3 curl >/dev/null

# --- go2rtc ---
mkdir -p /opt/devilcam
curl -fsSL -o /opt/devilcam/go2rtc "https://github.com/AlexxIT/go2rtc/releases/latest/download/${GO2RTC_ASSET}"
chmod +x /opt/devilcam/go2rtc

ENC_USER="$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$REC_USER")"
ENC_PASS="$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$REC_PASS")"

{
  echo "# Devil Cam Hub cameras (made by install.sh)"
  echo "streams:"
  for n in $CAMS; do
    # Main stream, video only (the Echo Show doesn't need the recorder's audio format).
    echo "  \"Camera ${n}\": rtsp://${ENC_USER}:${ENC_PASS}@${REC_IP}:554/Streaming/Channels/${n}01#media=video"
  done
  echo "api:"
  echo "  listen: \"127.0.0.1:1984\""
  echo "rtsp:"
  echo "  listen: \"\""
  echo "webrtc:"
  echo "  listen: \"\""
  echo "log:"
  echo "  level: warn"
} > /opt/devilcam/go2rtc.yaml
chmod 600 /opt/devilcam/go2rtc.yaml

cat > /etc/systemd/system/devilcam-go2rtc.service <<'EOF'
[Unit]
Description=Devil Cam Hub video converter (go2rtc)
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/opt/devilcam/go2rtc -config /opt/devilcam/go2rtc.yaml
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

# --- Caddy: only requests with the secret key get through ---
SECRET_FILE=/opt/devilcam/secret
if [ ! -s "$SECRET_FILE" ]; then
  python3 -c 'import secrets;print(secrets.token_urlsafe(24))' > "$SECRET_FILE"
  chmod 600 "$SECRET_FILE"
fi
SECRET="$(cat "$SECRET_FILE")"

cat > /etc/caddy/Caddyfile <<EOF
# Devil Cam Hub: the Cloudflare tunnel delivers requests here.
:8080 {
	handle_path /${SECRET}/* {
		reverse_proxy 127.0.0.1:1984
	}
	respond "Devil Cam Hub" 404
}
EOF

# --- Cloudflare tunnel ---
curl -fsSL -o /usr/local/bin/cloudflared "https://github.com/cloudflare/cloudflared/releases/latest/download/${CF_ASSET}"
chmod +x /usr/local/bin/cloudflared
if systemctl list-unit-files | grep -q '^cloudflared.service'; then
  cloudflared service uninstall >/dev/null 2>&1 || true
fi
cloudflared service install "$CF_TOKEN"

systemctl daemon-reload
systemctl enable --now devilcam-go2rtc
systemctl restart caddy
systemctl enable caddy >/dev/null 2>&1 || true

sleep 3
echo
if curl -fs "http://127.0.0.1:8080/${SECRET}/api/streams" >/dev/null; then
  echo "✅ Devil Cam Hub is running."
else
  echo "⚠️  The hub started, but the camera check didn't answer yet. Give it a minute and run:"
  echo "    sudo systemctl status devilcam-go2rtc"
fi
echo
echo "Copy these two values into the Alexa skill setup (step 6 of the guide):"
echo
echo "  HUB_URL    = https://<the address you set in Cloudflare, e.g. cam.devilmediagroup.com>"
echo "  HUB_SECRET = ${SECRET}"
echo
echo "Keep HUB_SECRET private: it's the key to your camera feed."
