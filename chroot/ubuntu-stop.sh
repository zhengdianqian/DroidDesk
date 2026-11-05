#!/system/bin/sh
# ubuntu-stop.sh - Ubuntu chroot 停止 (v1.2)
# 用法:
#   su -c 'sh /data/local/tmp/ubuntu-stop.sh --gui-only'   只关图形：杀桌面会话 + 停 X server + 卸 X socket
#                                                          （保持容器运行、不 umount proc/sys/dev、不动 shell）
#   su -c 'sh /data/local/tmp/ubuntu-stop.sh'              停止容器：关图形 + 杀 chroot 内全部进程 + 卸载所有挂载
#   su -c 'sh /data/local/tmp/ubuntu-stop.sh --force'      懒卸载兜底（残留挂载时用）
ROOT=/data/chroot/ubuntu
FORCE=0
GUI_ONLY=0
for a in "$@"; do
  case "$a" in
    --force)    FORCE=1 ;;
    --gui-only) GUI_ONLY=1 ;;
  esac
done

if [ "$(id -u)" != "0" ]; then
  exec su -c "sh $0 $*"
fi

log() { echo "[stop] $*"; }

# 桌面会话进程名（只关图形时只动这些）
GUI_NAMES="kwin_x11 kwin_wayland plasmashell startplasma-x11 startplasma-wayland plasma_session
kded6 kded5 klauncher ksmserver kactivitymanagerd knotifyd powerdevil kscreen_backend_launcher
kaccess xembedsniproxy gmenudbusmenuproxy polkit-kde-authentication-agent-1 Xwayland niri
dbus-run-session krunner kwin_x11 "

stop_gui() {
  log "停止 Termux:X11（X server + 前端界面）"
  pkill -f "com.termux.x11.Loader" 2>/dev/null
  am force-stop com.termux.x11 >/dev/null 2>&1
  rm -f /data/local/tmp/tx11tmp/.X0-lock 2>/dev/null
  # X server 已死，socket 文件会残留，必须删掉，否则状态会误判“X11 就绪”
  rm -f /data/local/tmp/tx11tmp/.X11-unix/X0 2>/dev/null

  log "结束桌面会话进程"
  n=0
  for p in /proc/[0-9]*; do
    pid=${p#/proc/}
    [ "$pid" = "$$" ] && continue
    [ "$(readlink $p/root 2>/dev/null)" = "$ROOT" ] || continue
    c=$(cat "$p/comm" 2>/dev/null)
    case " $GUI_NAMES " in
      *" $c "*) kill -TERM "$pid" 2>/dev/null && n=$((n+1)) ;;
    esac
  done
  log "已发送 TERM 给 $n 个桌面进程"
  sleep 2
  for p in /proc/[0-9]*; do
    pid=${p#/proc/}
    [ "$pid" = "$$" ] && continue
    [ "$(readlink $p/root 2>/dev/null)" = "$ROOT" ] || continue
    c=$(cat "$p/comm" 2>/dev/null)
    case " $GUI_NAMES " in
      *" $c "*) kill -KILL "$pid" 2>/dev/null ;;
    esac
  done
  # X socket 绑定属于图形层，单独卸掉，便于下次干净重绑
  if grep -q " $ROOT/tmp/.X11-unix " /proc/mounts; then
    umount "$ROOT/tmp/.X11-unix" 2>/dev/null || umount -l "$ROOT/tmp/.X11-unix" 2>/dev/null
    log "已卸载 $ROOT/tmp/.X11-unix"
  fi
  rm -f /data/local/tmp/guinone 2>/dev/null
}

kill_chroot_procs() {
  n=0
  for p in /proc/[0-9]*; do
    pid=${p#/proc/}
    [ "$pid" = "$$" ] && continue
    r=$(readlink "$p/root" 2>/dev/null)
    if [ "$r" = "$ROOT" ]; then
      kill -TERM "$pid" 2>/dev/null && n=$((n+1))
    fi
  done
  log "已发送 TERM 给 $n 个 chroot 内进程"
  sleep 2
  m=0
  for p in /proc/[0-9]*; do
    pid=${p#/proc/}
    [ "$pid" = "$$" ] && continue
    r=$(readlink "$p/root" 2>/dev/null)
    if [ "$r" = "$ROOT" ]; then
      kill -KILL "$pid" 2>/dev/null && m=$((m+1))
    fi
  done
  [ "$m" -gt 0 ] && log "强制结束 $m 个残留进程"
}

umount_all() {
  i=0
  while [ $i -lt 60 ]; do
    line=$(grep " $ROOT" /proc/mounts | awk '{print $2}' | awk '{print length, $0}' | sort -rn | head -1 | cut -d' ' -f2-)
    [ -z "$line" ] && break
    if umount "$line" 2>/dev/null; then
      log "umount $line"
    else
      if [ "$FORCE" = "1" ]; then
        umount -l "$line" 2>/dev/null || log "WARN 懒卸载失败: $line"
      else
        log "WARN 卸载失败(仍被占用): $line  —— 可加 --force 重试"
        break
      fi
    fi
    i=$((i+1))
  done
}

if [ "$GUI_ONLY" = "1" ]; then
  stop_gui
  left=$(grep -c " $ROOT" /proc/mounts)
  log "--gui-only 完成：容器仍在运行，剩余挂载 $left 条（proc/sys/dev 保留）"
  exit 0
fi

stop_gui
kill_chroot_procs
umount_all

left=$(grep -c " $ROOT" /proc/mounts)
log "剩余挂载数: $left"
[ "$left" = "0" ] && log "清理干净" || log "仍有残留，请再执行 --force"
