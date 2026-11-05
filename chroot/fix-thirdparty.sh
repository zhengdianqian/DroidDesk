#!/system/bin/sh
# fix-thirdparty.sh - 修复"三方软件点不开"（v1.2）
# 根因: /data 以 nosuid 挂载 + 内核无 user namespace -> Chromium/Electron 沙箱无法启动
# 做法: 给 Chromium/Electron 系应用的 .desktop 追加 --no-sandbox（原文件备份为 *.orig），
#       并写 /etc/chromium-browser/default。全部可回滚（restore-thirdparty.sh）。
ROOT=/data/chroot/ubuntu
if [ "$(id -u)" != "0" ]; then exec su -c "sh $0 $*"; fi

CHROOT="/usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8"

mkdir -p "$ROOT/tmp"
cp /data/local/tmp/fix-thirdparty.sh "$ROOT/tmp/_fix3p_src.sh" 2>/dev/null
cat > "$ROOT/tmp/_inner_fix3p.sh" <<'EOS'
#!/bin/bash
set -u
changed=0
patch_file() {
  f="$1"
  grep -q "^Exec=" "$f" || return 0
  grep -q -- "--no-sandbox" "$f" && return 0
  bin=$(grep -m1 "^Exec=" "$f" | sed 's/^Exec=//' | awk '{print $1}' | tr -d '"')
  [ -x "$bin" ] || return 0
  d=$(dirname "$bin")
  base=$(basename "$f")
  hit=0
  # 1) Chromium/Electron 特征：同目录(或上一级)存在 chrome-sandbox / resources.pak / libcef.so
  for probe in "$d/chrome-sandbox" "$d/../chrome-sandbox" "$d/electron" "$d/resources.pak" \
               "$d/wmpf_resources.pak" "$d/libcef.so" "$d/icudtl.dat"; do
    [ -e "$probe" ] && hit=1
  done
  # 2) 已知 Chromium/Electron 系桌面项（二进制藏在 wrapper 脚本里的那些）
  case "$base" in
    *chromium*|*chrome*|*code*|*qq*|*wechat*|*wechatappex*|*feishu*|*dingtalk*|*trae*|*xiezuo*|*electron*)
      hit=1 ;;
  esac
  [ "$hit" = "1" ] || return 0
  [ -f "$f.orig" ] || cp "$f" "$f.orig"
  sed -i "s|^Exec=\(.*\)$|Exec=\1 --no-sandbox|" "$f"
  echo "  patched: $base  ($bin)"
  changed=$((changed+1))
}

for dir in /usr/share/applications /home/Gold/.local/share/applications /root/.local/share/applications; do
  [ -d "$dir" ] || continue
  for f in "$dir"/*.desktop; do
    [ -f "$f" ] || continue
    case "$f" in *.orig) continue ;; esac
    patch_file "$f"
  done
done

# Ubuntu 的 chromium 包装脚本会读这个文件
mkdir -p /etc/chromium-browser
if [ ! -f /etc/chromium-browser/default ]; then
  cat > /etc/chromium-browser/default <<'EOF'
# 由 fix-thirdparty.sh 生成：Android chroot 内无法使用 setuid/userns 沙箱
CHROMIUM_FLAGS="--no-sandbox"
EOF
  echo "  wrote /etc/chromium-browser/default"
  changed=$((changed+1))
fi

echo "共修改 $changed 处"
EOS

echo "=== 扫描并修复 Chromium/Electron 桌面项 ==="
chmod 755 "$ROOT/tmp/_inner_fix3p.sh"
chroot "$ROOT" $CHROOT /bin/bash /tmp/_inner_fix3p.sh 2>&1
cp "$ROOT/tmp/_inner_fix3p.sh" "$ROOT/usr/local/bin/fix-thirdparty.sh" 2>/dev/null
chmod 755 "$ROOT/usr/local/bin/fix-thirdparty.sh" 2>/dev/null
echo "=== 完成；回滚见 restore-thirdparty.sh ==="
