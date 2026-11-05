#!/system/bin/sh
# ubuntu-agent-handler.sh - ROOT 代理的请求处理端 (v1.3)
# 由 ubuntu-agent.sh 通过 busybox nc -lk -e 调用；stdin/stdout 即 socket。
# 协议: 客户端发送一行 "<token> <action>"，服务端输出若干行文本，最后两行为:
#   RESULT <json>      最新状态（ubuntu-status.sh --json）
#   ELAPSED <毫秒>
TOKEN_FILE=/data/local/tmp/.ub_agent_token
S=/data/local/tmp

read -r LINE
TOK=${LINE%% *}
CMD=${LINE#* }
EXP=$(cat "$TOKEN_FILE" 2>/dev/null)
if [ -z "$EXP" ] || [ "$TOK" != "$EXP" ]; then
  echo "ERR 未授权（token 不匹配）。请在 root shell 重新执行 ubuntu-agent.sh start"
  exit 1
fi

T0=$(date +%s)
st() { sh "$S/ubuntu-status.sh" --json 2>/dev/null | tail -1; }
emit() { echo "RESULT $(st)"; echo "ELAPSED $(( ($(date +%s) - T0) * 1000 ))"; }

# 轻量探测：不跑重量级 status（status 要遍历 /proc 与 chroot 内 xdpyinfo，约 10s+）
mounts() { grep -c " /data/chroot/ubuntu" /proc/mounts 2>/dev/null; }
has_proc() { ps -A -o NAME 2>/dev/null | grep -qx "$1"; }
fast() {
  echo "mounts=$(mounts) kwin=$(has_proc kwin_x11 && echo 1 || echo 0) plasma=$(has_proc plasmashell && echo 1 || echo 0)"
}

wait_gui() {
  # 200ms 轮询，最多 20s；超时不阻塞按钮，直接返回“已启动中”，桌面在后台继续起
  i=0
  while [ $i -lt 100 ]; do
    if has_proc kwin_x11 && [ -S /data/local/tmp/tx11tmp/.X11-unix/X0 ]; then return 0; fi
    sleep 0.2; i=$((i+1))
  done
  return 1
}

case "$CMD" in
  ping)
    echo "RESULT {\"pong\":1}"; echo "ELAPSED 0" ;;
  status)
    emit ;;
  start-cmd)
    echo "== ubuntu-start.sh --mount-only =="
    # 文件重定向 + </dev/null：脚本内 dbus 等后台进程会继承 socket fd，导致客户端永远等不到 EOF
    sh "$S/ubuntu-start.sh" --mount-only </dev/null >"$S/agent-last.log" 2>&1
    tail -25 "$S/agent-last.log"
    emit ;;
  start-gui|gui-on)
    M=$(mounts)
    if [ "${M:-0}" -eq 0 ]; then FLAG=--force; else FLAG=; fi   # 没挂载=容器没起 → 先清残留
    echo "== ubuntu-start.sh $FLAG --gui =="
    nohup sh "$S/ubuntu-start.sh" $FLAG --gui >"$S/gui.log" 2>&1 &
    echo "PROBE0 $(fast)"
    if wait_gui; then echo "图形已就绪"; else echo "图形仍在启动中（已超 20s，后台继续）"; fi
    echo "PROBE1 $(fast)"
    echo "-- gui.log 末尾 --"
    tail -15 "$S/gui.log" 2>/dev/null
    emit ;;
  gui-off)
    echo "== ubuntu-stop.sh --gui-only =="
    sh "$S/ubuntu-stop.sh" --gui-only </dev/null >"$S/agent-last.log" 2>&1
    for i in 1 2 3 4 5 6 7 8 9 10; do has_proc kwin_x11 || break; sleep 1; done
    tail -25 "$S/agent-last.log"
    emit ;;
  stop)
    echo "== ubuntu-stop.sh --force =="
    sh "$S/ubuntu-stop.sh" --force </dev/null >"$S/agent-last.log" 2>&1
    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do [ "$(mounts)" -eq 0 ] && break; sleep 1; done
    tail -30 "$S/agent-last.log"
    emit ;;
  *)
    echo "ERR 未知动作: $CMD"; exit 1 ;;
esac
