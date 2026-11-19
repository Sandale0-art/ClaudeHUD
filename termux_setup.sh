#!/data/data/com.termux/files/usr/bin/bash
# ─────────────────────────────────────────────────────────────
#  Claude HUD — Termux setup + push to GitHub
#  Запусти: bash termux_setup.sh
# ─────────────────────────────────────────────────────────────

set -e
GREEN='\033[0;32m'; ORANGE='\033[0;33m'; RESET='\033[0m'

echo -e "${ORANGE}╔════════════════════════════════╗${RESET}"
echo -e "${ORANGE}║      Claude HUD  Setup         ║${RESET}"
echo -e "${ORANGE}╚════════════════════════════════╝${RESET}"
echo ""

# 1. Install deps
echo -e "${GREEN}[1/5] Установка зависимостей...${RESET}"
pkg update -y -q
pkg install -y -q git openssh

# 2. Git config
echo -e "${GREEN}[2/5] Настройка git...${RESET}"
read -p "  Твой GitHub username: " GH_USER
read -p "  Твой email: "           GH_EMAIL
git config --global user.name  "$GH_USER"
git config --global user.email "$GH_EMAIL"

# 3. SSH key
if [ ! -f ~/.ssh/id_ed25519 ]; then
  echo -e "${GREEN}[3/5] Генерация SSH-ключа...${RESET}"
  ssh-keygen -t ed25519 -C "$GH_EMAIL" -f ~/.ssh/id_ed25519 -N ""
  echo ""
  echo -e "${ORANGE}  Скопируй этот ключ в GitHub → Settings → SSH keys:${RESET}"
  echo "  ───────────────────────────────────────"
  cat ~/.ssh/id_ed25519.pub
  echo "  ───────────────────────────────────────"
  echo ""
  read -p "  Нажми Enter после добавления ключа в GitHub..."
else
  echo -e "${GREEN}[3/5] SSH-ключ уже есть, пропускаем${RESET}"
fi

# 4. Create repo via API
echo -e "${GREEN}[4/5] Создание репозитория ClaudeHUD...${RESET}"
read -p "  GitHub токен (repo scope): " GH_TOKEN
curl -s -X POST \
  -H "Authorization: token $GH_TOKEN" \
  -H "Accept: application/vnd.github.v3+json" \
  https://api.github.com/user/repos \
  -d '{"name":"ClaudeHUD","description":"Claude-branded Android system HUD","private":false}' \
  | grep '"full_name"' | head -1

# 5. Push
echo -e "${GREEN}[5/5] Push в GitHub...${RESET}"
cd "$(dirname "$0")"
git init -q
git add .
git commit -m "🤖 Initial Claude HUD project"
git branch -M main
git remote add origin "git@github.com:${GH_USER}/ClaudeHUD.git"
git push -u origin main

echo ""
echo -e "${ORANGE}╔══════════════════════════════════════════╗${RESET}"
echo -e "${ORANGE}║  ✓ Push выполнен!                        ║${RESET}"
echo -e "${ORANGE}║  Открой GitHub → Actions → Build APK     ║${RESET}"
echo -e "${ORANGE}║  APK будет в Artifacts через ~5 минут    ║${RESET}"
echo -e "${ORANGE}╚══════════════════════════════════════════╝${RESET}"
