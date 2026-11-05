#!/system/bin/sh
# ubuntu-status.sh - 容器状态查询 (v1.2)
# 用法: su -c 'sh /data/local/tmp/ubuntu-status.sh'         人读
#       su -c 'sh /data/local/tmp/ubuntu-status.sh --json'  单行 JSON（供 APK 解析）
ROOT=/data/chroot/ubuntu
JSON=0
[ "$1" = "--json" ] && JSON=1

if [ "$(id -u)" != "0" ]; then
  exec su -c "sh $0 $*"
fi

# root 是否可用（能进 su 且是 uid 0 即为可用）
U=$(id -u 2>/dev/null)
[ "$U" = "0" ] && ROOT_OK=1 || ROOT_OK=0

MOUNTS=$(grep -c " $ROOT" /proc/mounts 2>/dev/null)
PROCS=0
for p in /proc/[0-9]*; do
  [ "$(readlink $p/root 2>/dev/null)" = "$ROOT" ] && PROCS=$((PROCS+1))
done

GUI=0
for n in kwin_x11 kwin_wayland plasmashell; do
  if [ -d /proc ] && grep -q . /proc/[0-9]*/comm 2>/dev/null; then :; fi
done
pgrep_out=$(ps -A -o NAME 2>/dev/null)
case "$pgrep_out" in *kwin_x11*) GUI=1 ;; *kwin_wayland*) GUI=1 ;; esac
case "$pgrep_out" in *plasmashell*) SHELL_OK=1 ;; *) SHELL_OK=0 ;; esac

TX11_APP=0
case "$pgrep_out" in *termux-x11*) TX11_APP=1 ;; esac
X11=0
# socket 文件可能在 X server 被杀后残留，必须同时确认 X server 进程还在
[ -S /data/local/tmp/tx11tmp/.X11-unix/X0 ] && [ "$TX11_APP" = "1" ] && X11=1

# X 分辨率（从 chroot 内取，取不到就 NA）
RES="NA"
if [ -S /data/local/tmp/tx11tmp/.X11-unix/X0 ]; then
  RES=$(chroot "$ROOT" /usr/bin/env -i PATH=/usr/bin:/bin DISPLAY=:0 \
        /usr/bin/xdpyinfo 2>/dev/null | grep -m1 dimensions | awk '{print $2}')
  [ -z "$RES" ] && RES="NA"
fi

CONTAINER=0
[ "$MOUNTS" -gt 0 ] && CONTAINER=1
[ "$PROCS" -gt 0 ] && CONTAINER=1

if [ "$JSON" = "1" ]; then
  echo "{\"root\":$ROOT_OK,\"container\":$CONTAINER,\"mounts\":$MOUNTS,\"procs\":$PROCS,\"gui\":$GUI,\"desktop\":$SHELL_OK,\"x11\":$X11,\"tx11\":$TX11_APP,\"res\":\"$RES\"}"
  exit 0
fi

echo "=== root ==="
[ "$ROOT_OK" = "1" ] && echo "  ROOT 可用 (uid=0, $(id | sed 's/.*context=//'))" || echo "  ROOT 不可用：请先在 KSU/临时 root 里授权"
echo "=== 容器 ==="
[ "$CONTAINER" = "1" ] && echo "  运行中：挂载 $MOUNTS 条，chroot 进程 $PROCS 个" || echo "  未运行"
grep " $ROOT" /proc/mounts | awk '{print "   " $2 " <- " $1 " (" $3 ")"}'
echo "=== 图形 ==="
[ "$GUI" = "1" ] && echo "  KDE 窗口管理器: 运行中" || echo "  KDE 窗口管理器: 未运行"
[ "$SHELL_OK" = "1" ] && echo "  plasmashell: 运行中" || echo "  plasmashell: 未运行"
[ "$X11" = "1" ] && echo "  X server: :0 就绪" || echo "  X server: 未就绪"
echo "  分辨率: $RES"
echo "=== 设备 ==="
df -h /data 2>/dev/null | tail -1 | awk '{print "  /data 可用 " $4 " / " $2}'
