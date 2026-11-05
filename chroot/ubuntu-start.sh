#!/system/bin/sh
# ubuntu-start.sh - Ubuntu chroot 启动器 (v1.2)
# 用法:
#   su -c 'sh /data/local/tmp/ubuntu-start.sh'                 仅 shell（不启动图形）
#   su -c 'sh /data/local/tmp/ubuntu-start.sh --gui'           启动 KDE 桌面（Termux:X11 路，已验证）
#   su -c 'sh /data/local/tmp/ubuntu-start.sh --force --gui'   先清理残留挂载再启动
# v1.2 变更:
#   - 补 /dev/pts (devpts) 与独立 /dev/shm（终端/Chromium 依赖）
#   - 启动 chroot 内 system dbus（/run/dbus/system_bus_socket）
#   - GUI 会话强制带上 DBUS_SESSION_BUS_ADDRESS / XDG_DATA_DIRS
#   - 打开 GUI 时自动确保 Termux:X11 不遮挡底部（关闭额外按键条）
ROOT=/data/chroot/ubuntu
MODE=shell
FORCE=0
for a in "$@"; do
  case "$a" in
    --gui|--gui-x11) MODE=gui ;;
    --mount-only)    MODE=mount ;;
    --force)         FORCE=1 ;;
  esac
done

if [ "$(id -u)" != "0" ]; then
  exec su -c "sh $0 $*"
fi

log() { echo "[start] $*"; }
die() { echo "[start][ERROR] $*" >&2; exit 1; }

[ -x "$ROOT/usr/bin/bash" ] || die "rootfs 不存在或 /usr/bin/bash 缺失: $ROOT"

is_mounted() { grep -q " $1 " /proc/mounts; }

# ---------- 幂等 / 清理 ----------
force_clean() {
  log "--force: 逆序卸载 $ROOT 下所有挂载"
  i=0
  while [ $i -lt 40 ]; do
    line=$(grep " $ROOT" /proc/mounts | awk '{print $2}' | awk '{print length, $0}' | sort -rn | head -1 | cut -d' ' -f2-)
    [ -z "$line" ] && break
    umount "$line" 2>/dev/null || umount -l "$line" 2>/dev/null
    i=$((i+1))
  done
  log "--force: 清理完成"
}
[ "$FORCE" = "1" ] && force_clean

mkdir -p "$ROOT/proc" "$ROOT/sys" "$ROOT/dev" "$ROOT/dev/pts" "$ROOT/dev/shm" \
         "$ROOT/tmp" "$ROOT/tmp/.X11-unix" "$ROOT/run" "$ROOT/run/dbus" "$ROOT/sdcard" 2>/dev/null

# ---------- 基础挂载 ----------
if is_mounted "$ROOT/proc"; then log "skip /proc (已挂载)"
else mount -t proc proc "$ROOT/proc" && log "mount /proc ok" || log "WARN /proc 挂载失败"; fi

if is_mounted "$ROOT/sys"; then log "skip /sys (已挂载)"
else mount -t sysfs sysfs "$ROOT/sys" && log "mount /sys ok" || log "WARN /sys 挂载失败"; fi

# /dev：bind 宿主 /dev（一次带进 dri / kgsl-3d0 / fuse / binder）
if is_mounted "$ROOT/dev"; then log "skip /dev (已绑定)"
else mount --bind /dev "$ROOT/dev" && log "bind /dev ok" || log "WARN /dev 绑定失败"; fi

# /dev/pts：bind /dev 不会带上子挂载，必须单独挂 devpts，否则终端类程序无法分配 pty
if is_mounted "$ROOT/dev/pts"; then log "skip /dev/pts"
else
  mount -t devpts devpts "$ROOT/dev/pts" -o gid=5,mode=620,ptmxmode=666 2>/dev/null \
    && log "mount /dev/pts ok" || log "WARN /dev/pts 挂载失败"
fi
[ -e "$ROOT/dev/ptmx" ] || ln -s pts/ptmx "$ROOT/dev/ptmx" 2>/dev/null

# /dev/shm：独立 tmpfs（Chromium/Electron 必需，避免和 /dev 抢空间）
if is_mounted "$ROOT/dev/shm"; then log "skip /dev/shm"
else
  mount -t tmpfs -o size=2G,mode=1777 tmpfs "$ROOT/dev/shm" 2>/dev/null \
    && log "mount /dev/shm ok (2G)" || log "WARN /dev/shm 挂载失败"
fi

if is_mounted "$ROOT/sdcard"; then log "skip /sdcard"
else mount --bind /storage/emulated/0 "$ROOT/sdcard" 2>/dev/null && log "bind /sdcard ok" || log "WARN /sdcard 绑定失败(忽略)"; fi

# resolv.conf（rootfs 内常是悬空软链 -> systemd-resolved，先摘掉再写）
RESOLV="$ROOT/etc/resolv.conf"
if [ -s "$RESOLV" ] && grep -q nameserver "$RESOLV" 2>/dev/null; then
  log "resolv.conf 已存在"
else
  [ -L "$RESOLV" ] && rm -f "$RESOLV"
  printf 'nameserver 223.5.5.5\nnameserver 8.8.8.8\n' > "$RESOLV" && log "resolv.conf 已写入" || log "WARN resolv.conf 写入失败"
fi

# ---------- chroot 内 system dbus ----------
CHROOT_ENV="/usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8"
start_system_dbus() {
  mkdir -p "$ROOT/run/dbus"
  if [ -S "$ROOT/run/dbus/system_bus_socket" ]; then
    log "system dbus 已在运行"
    return 0
  fi
  chroot "$ROOT" $CHROOT_ENV /usr/bin/dbus-daemon --system --fork --nopidfile </dev/null >/dev/null 2>&1
  if [ -S "$ROOT/run/dbus/system_bus_socket" ]; then
    log "system dbus ok"
  else
    log "WARN system dbus 启动失败（部分三方程序会报 bus 错误，不影响桌面）"
  fi
}
start_system_dbus

# ---------- 图形：Termux:X11 ----------
TX11_TMP=/data/local/tmp/tx11tmp
X11_LOADER=/data/local/tmp/loader.apk
TX11_PREF=/data/data/com.termux.x11/shared_prefs/com.termux.x11_preferences.xml

ensure_tx11_prefs() {
  # 关掉「额外按键条」，否则它会盖住 KDE 面板（用户反馈的底部遮挡）
  [ -f "$TX11_PREF" ] || return 0
  if grep -q 'name="showAdditionalKbd" value="true"' "$TX11_PREF" 2>/dev/null; then
    OWNER=$(stat -c %U /data/data/com.termux.x11 2>/dev/null)
    GROUP=$(stat -c %G /data/data/com.termux.x11 2>/dev/null)
    sed -i -e 's|<boolean name="showAdditionalKbd" value="true" />|<boolean name="showAdditionalKbd" value="false" />|' \
           -e 's|<boolean name="additionalKbdVisible" value="true" />|<boolean name="additionalKbdVisible" value="false" />|' \
           "$TX11_PREF"
    chown "$OWNER:$GROUP" "$TX11_PREF"; chmod 660 "$TX11_PREF"; restorecon "$TX11_PREF" 2>/dev/null
    log "已关闭 Termux:X11 额外按键条（避免遮挡 KDE 面板）"
  fi
}

start_x11_server() {
  if [ -S "$TX11_TMP/.X11-unix/X0" ] && [ -r "$TX11_TMP/.X0-lock" ]; then
    log "Termux:X11 server 已在运行（复用）"
    return 0
  fi
  [ -f "$X11_LOADER" ] || die "缺少 $X11_LOADER（Termux:X11 loader），请先执行 install.sh"
  log "启动 Termux:X11 server ..."
  mkdir -p "$TX11_TMP"; chmod 777 "$TX11_TMP"
  rm -f "$TX11_TMP/.X0-lock"
  TMPDIR="$TX11_TMP" \
  XKB_CONFIG_ROOT="$ROOT/usr/share/X11/xkb" \
  CLASSPATH="$X11_LOADER" \
  nohup /system/bin/app_process -Xnoimage-dex2oat / --nice-name=termux-x11 \
        com.termux.x11.Loader :0 > /data/local/tmp/tx11.log 2>&1 &
  i=0
  while [ $i -lt 20 ]; do
    [ -S "$TX11_TMP/.X11-unix/X0" ] && break
    sleep 0.5; i=$((i+1))
  done
  if [ -S "$TX11_TMP/.X11-unix/X0" ]; then
    log "X server ok (socket $TX11_TMP/.X11-unix/X0)"
  else
    log "WARN X server 未就绪，日志："
    cat /data/local/tmp/tx11.log
  fi
  am start -n com.termux.x11/.MainActivity >/dev/null 2>&1
}

mount_x11_socket() {
  mkdir -p "$ROOT/tmp/.X11-unix"
  if is_mounted "$ROOT/tmp/.X11-unix"; then
    log "skip /tmp/.X11-unix (已绑定)"
  else
    mount --bind "$TX11_TMP/.X11-unix" "$ROOT/tmp/.X11-unix" \
      && log "bind /tmp/.X11-unix ok" || log "WARN /tmp/.X11-unix 绑定失败"
  fi
}

[ "$MODE" = "gui" ] && { ensure_tx11_prefs; start_x11_server; mount_x11_socket; }

log "挂载状态:"
grep " $ROOT" /proc/mounts | awk '{print "   " $2 " <- " $1 " (" $3 ")"}'

case "$MODE" in
  mount)
    log "容器已就绪（仅命令模式，未启动图形）"
    log "进入命令行: su -c 'chroot $ROOT /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash -l'"
    exit 0
    ;;
  gui)
    log "启动 KDE 桌面 (Termux:X11 / DISPLAY=:0)"
    exec chroot "$ROOT" /usr/bin/env -i \
      HOME=/home/Gold USER=Gold LOGNAME=Gold TERM=xterm-256color \
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
      LANG=C.UTF-8 \
      /usr/local/bin/start-desktop-x11.sh
    ;;
  *)
    log "进入 shell（退出后挂载保留，需 stop 清理）"
    exec chroot "$ROOT" /usr/bin/env -i \
      HOME=/root TERM=xterm-256color \
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
      LANG=C.UTF-8 \
      /bin/bash -l
    ;;
esac
