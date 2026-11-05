#!/system/bin/sh
# restore-thirdparty.sh - 回滚 fix-thirdparty.sh 的改动
ROOT=/data/chroot/ubuntu
if [ "$(id -u)" != "0" ]; then exec su -c "sh $0 $*"; fi
CHROOT="/usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8"
cat > "$ROOT/tmp/_restore3p.sh" <<'EOS'
#!/bin/bash
n=0
for f in $(find /usr/share/applications /home/Gold/.local/share/applications /root/.local/share/applications -name "*.desktop.orig" 2>/dev/null); do
  mv "$f" "${f%.orig}" && n=$((n+1))
done
rm -f /etc/chromium-browser/default
echo "回滚 $n 个 .desktop"
EOS
chroot "$ROOT" $CHROOT /bin/bash /tmp/_restore3p.sh 2>&1
