#!/system/bin/sh
# ubuntu-agent.sh - Ubuntu 容器管理器的 ROOT 代理 (v1.3)
# 背景: KSU 越狱模式对未授权应用隐藏 /system/bin/su，APK 无法用 su -c 提权。
#       本代理以 root 常驻监听 127.0.0.1:47123，APK 用一次性 token 调用，代跑 chroot 脚本。
# 用法:
#   su -c 'sh /data/local/tmp/ubuntu-agent.sh start'    启动代理
#   su -c 'sh /data/local/tmp/ubuntu-agent.sh stop'     停止代理
#   su -c 'sh /data/local/tmp/ubuntu-agent.sh status'   查看状态
# 回滚: sh ubuntu-agent.sh stop; rm /data/local/tmp/ubuntu-agent*.sh
PORT=${UB_AGENT_PORT:-47123}
BB=/data/adb/ksu/bin/busybox
HANDLER=/data/local/tmp/ubuntu-agent-handler.sh
TOKEN_FILE=/data/local/tmp/.ub_agent_token
PKG=com.trae.ubuntu.container
APP_TOKEN=/data/data/$PKG/files/agent.token
APK_UID=$(cmd package list packages -U 2>/dev/null | grep "package:$PKG " | sed 's/.*uid://' | tr -d ' ')

log() { echo "[agent] $*"; }

running() {
  # 只看监听端口，避免 pgrep 匹配到本脚本自身的命令行
  netstat -ln 2>/dev/null | grep -q ":$PORT "
}

start() {
  if running; then log "已在运行 (port $PORT)"; return 0; fi
  [ -x "$HANDLER" ] || { log "缺少 $HANDLER"; exit 1; }
  [ -x "$BB" ] || BB=/system/bin/nc
  # 生成一次性 token，仅本 APK uid 可读（同时写入 APK 私有目录，避免 SELinux 读 shell_data_file 被拒）
  NEW=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
  printf '%s\n' "$NEW" > "$TOKEN_FILE"
  chmod 600 "$TOKEN_FILE"
  if [ -n "$APK_UID" ]; then chown "$APK_UID:$APK_UID" "$TOKEN_FILE" 2>/dev/null; fi
  restorecon "$TOKEN_FILE" 2>/dev/null
  if mkdir -p "$(dirname "$APP_TOKEN")" 2>/dev/null; then
    printf '%s\n' "$NEW" > "$APP_TOKEN" 2>/dev/null
    chmod 600 "$APP_TOKEN" 2>/dev/null
    if [ -n "$APK_UID" ]; then chown -R "$APK_UID:$APK_UID" /data/data/$PKG/files 2>/dev/null; fi
    restorecon -R /data/data/$PKG/files 2>/dev/null
  fi

  if [ "$BB" = "/system/bin/nc" ]; then
    log "WARN 未找到 busybox nc，尝试 toybox nc"
    nohup /system/bin/nc -l -p "$PORT" -e "$HANDLER" >/dev/null 2>&1 &
  else
    nohup "$BB" nc -lk -p "$PORT" -e "$HANDLER" >/dev/null 2>&1 &
  fi
  sleep 1
  if running; then
    log "已启动: 127.0.0.1:$PORT (apk uid=${APK_UID:-unknown})"
  else
    log "启动失败"; exit 1
  fi
}

stop() {
  # 不能 pkill -f handler：会误杀正在执行本脚本的 shell（命令行里含同名参数）
  for p in /proc/[0-9]*; do
    pid=${p#/proc/}
    [ -n "$pid" ] || continue
    args=$(tr '\0' ' ' < "$p/cmdline" 2>/dev/null) || continue
    case "$args" in *"nc -lk -p $PORT"*) kill "$pid" 2>/dev/null ;; esac
  done
  rm -f "$TOKEN_FILE"
  log "已停止"
}

case "$1" in
  start)  start ;;
  stop)   stop ;;
  status)
    if running; then
      log "运行中: 127.0.0.1:$PORT"
    else
      log "未运行"
    fi
    [ -f "$TOKEN_FILE" ] && log "token 文件: $TOKEN_FILE ($(stat -c '%U:%G %a' "$TOKEN_FILE" 2>/dev/null))"
    ;;
  *) echo "用法: $0 start|stop|status"; exit 1 ;;
esac
