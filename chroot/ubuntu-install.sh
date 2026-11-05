#!/system/bin/sh
# ubuntu-install.sh - Ubuntu chroot 首次安装/校验 (v1.1)
# 用法: su -c 'sh /data/local/tmp/ubuntu-install.sh'
# 作用: 校验 rootfs、创建落位目录、安装桌面启动脚本、检查 GPU/网络、给出体检结果
#       不写 system 分区、不动 KSU/LSP、不重启
ROOT=/data/chroot/ubuntu
SRC=/data/local/tmp

if [ "$(id -u)" != "0" ]; then
  exec su -c "sh $0 $*"
fi
log() { echo "[install] $*"; }
die() { echo "[install][ERROR] $*" >&2; exit 1; }

# 1) rootfs 校验
[ -d "$ROOT" ] || die "缺少 $ROOT，请先用 PC 脚本推送并解包 rootfs"
for f in usr/bin/bash etc/passwd etc/apt var/lib/dpkg/status usr/lib/aarch64-linux-gnu; do
  [ -e "$ROOT/$f" ] || die "rootfs 不完整，缺少 $f"
done
log "rootfs 校验通过: $ROOT"
log "占用: $(du -sh "$ROOT" 2>/dev/null | cut -f1)  可用: $(df -h /data | tail -1 | awk '{print $4}')"

# 2) 落位目录
mkdir -p "$ROOT/proc" "$ROOT/sys" "$ROOT/dev" "$ROOT/tmp" "$ROOT/run" "$ROOT/sdcard" "$ROOT/tmp/.X11-unix" \
         /data/local/tmp/tx11tmp
chmod 1777 "$ROOT/tmp" 2>/dev/null

# 3) 安装/更新桌面脚本
for s in start-desktop-x11.sh start-desktop.sh; do
  if [ -f "$SRC/$s" ]; then
    cp -f "$SRC/$s" "$ROOT/usr/local/bin/$s"
    chmod 755 "$ROOT/usr/local/bin/$s"
    log "已安装 /usr/local/bin/$s"
  fi
done
# KWin 需要 X11 socket 目录
mkdir -p "$ROOT/tmp/.X11-unix"; chmod 1777 "$ROOT/tmp/.X11-unix"

# 4) apt 源（保持 rootfs 自带；若指向 archive.ubuntu.com 则换清华源）
SL="$ROOT/etc/apt/sources.list"
if [ -f "$SL" ] && grep -q "archive.ubuntu.com" "$SL" 2>/dev/null; then
  sed -i 's|http://archive.ubuntu.com|https://mirrors.tuna.tsinghua.edu.cn|g; s|http://ports.ubuntu.com|https://mirrors.tuna.tsinghua.edu.cn|g' "$SL"
  log "apt 源已换清华镜像"
else
  log "apt 源保持现状: $(head -1 "$SL" 2>/dev/null)"
fi

# 5) 体检
log "--- GPU / 设备节点 ---"
ls -l /dev/kgsl-3d0 /dev/dri/renderD128 2>&1
log "--- Termux:X11 loader ---"
[ -f /data/local/tmp/loader.apk ] && log "loader.apk 就位" || log "WARN 缺少 /data/local/tmp/loader.apk（--gui 需要）"
log "--- chroot 自检（临时挂 /proc，用完即卸）---"
TMPPROC=0
if ! grep -q " $ROOT/proc " /proc/mounts; then
  mount -t proc proc "$ROOT/proc" 2>/dev/null && TMPPROC=1
fi
chroot "$ROOT" /usr/bin/env PATH=/usr/bin:/bin /bin/bash -c 'id; uname -a' 2>&1
[ "$TMPPROC" = "1" ] && umount "$ROOT/proc" 2>/dev/null
log "resolv.conf: $(head -2 "$ROOT/etc/resolv.conf" 2>/dev/null | tr '\n' ' ')"
log "安装完成。启动: su -c 'sh /data/local/tmp/ubuntu-start.sh --gui'"
