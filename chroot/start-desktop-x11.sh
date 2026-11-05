#!/bin/bash
# start-desktop-x11.sh - 在 chroot 内以 Gold 用户启动 KDE Plasma (X11)  v1.2
# 由 ubuntu-start.sh --gui 调用，DISPLAY 由 Termux:X11 server 提供
export PATH=/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin
USER_NAME=${DESKTOP_USER:-Gold}
UID_N=$(id -u "$USER_NAME" 2>/dev/null || echo 1000)
export DISPLAY=${DISPLAY:-:0}
export XDG_RUNTIME_DIR=/run/user/$UID_N
export XDG_SESSION_TYPE=x11
export XDG_CURRENT_DESKTOP=KDE
export XDG_DATA_DIRS=/usr/local/share:/usr/share
export QT_QPA_PLATFORM=xcb
# Adreno/kgsl：KWin(X11) 合成用
export MESA_LOADER_DRIVER_OVERRIDE=${MESA_LOADER_DRIVER_OVERRIDE:-kgsl}
export GALLIUM_DRIVER=${GALLIUM_DRIVER:-kgsl}
export FD_FORCE_KGSL=${FD_FORCE_KGSL:-1}
# 本机 /data 为 nosuid 且内核无 user namespace，Chromium/Electron 沙箱不可用
export ELECTRON_DISABLE_SANDBOX=1
export CHROMIUM_FLAGS="--no-sandbox"

mkdir -p "$XDG_RUNTIME_DIR" /tmp/.X11-unix
chown "$UID_N:$UID_N" "$XDG_RUNTIME_DIR" 2>/dev/null
chmod 700 "$XDG_RUNTIME_DIR"
chmod 1777 /tmp/.X11-unix 2>/dev/null

# 关闭 KDE 自动锁屏/启动锁屏，否则桌面会被 kscreenlocker_greet 全屏盖住
KL="$HOME/.config/kscreenlockerrc"
[ "$HOME" = "/root" ] && KL="/home/$USER_NAME/.config/kscreenlockerrc"
mkdir -p "$(dirname "$KL")" 2>/dev/null
printf '[Daemon]\nAutolock=false\nLockOnStartup=false\n' > "$KL" 2>/dev/null
chown "$UID_N:$UID_N" "$KL" 2>/dev/null

# system dbus（ubuntu-start.sh 已拉起；这里只做兜底）
[ -S /run/dbus/system_bus_socket ] || dbus-daemon --system --fork --nopidfile >/dev/null 2>&1

echo "[desktop-x11] DISPLAY=$DISPLAY user=$USER_NAME"
if ! xdpyinfo >/dev/null 2>&1; then
  echo "[desktop-x11][ERROR] 连不上 X server（Termux:X11 未启动或 socket 未绑定）"
  exit 1
fi

SESSION_ENV="DISPLAY=$DISPLAY XDG_RUNTIME_DIR=/run/user/$UID_N XDG_DATA_DIRS=/usr/local/share:/usr/share \
XDG_SESSION_TYPE=x11 XDG_CURRENT_DESKTOP=KDE QT_QPA_PLATFORM=xcb \
MESA_LOADER_DRIVER_OVERRIDE=$MESA_LOADER_DRIVER_OVERRIDE GALLIUM_DRIVER=$GALLIUM_DRIVER FD_FORCE_KGSL=$FD_FORCE_KGSL \
ELECTRON_DISABLE_SANDBOX=1 CHROMIUM_FLAGS=--no-sandbox"

if [ -x /usr/bin/startplasma-x11 ]; then
  exec su - "$USER_NAME" -c "export $SESSION_ENV; exec dbus-run-session -- startplasma-x11"
fi
if [ -x /usr/bin/startxfce4 ]; then
  exec su - "$USER_NAME" -c "export $SESSION_ENV; exec dbus-run-session -- startxfce4"
fi
echo "[desktop-x11][ERROR] 未找到 startplasma-x11 / startxfce4"
exit 1
