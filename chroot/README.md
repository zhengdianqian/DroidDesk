# chroot 方案（Ubuntu + KDE + Termux:X11）

本目录是「Ubuntu chroot + KDE Plasma + Termux:X11」这条路线在 Redmi Pad Pro（KSU 临时 root）上的
可复现产物，与 `feat/2.0.0`(PRoot/Xfce) 路线互相独立。

## 目录

```
chroot/
├── ubuntu-install.sh          # 首次部署 rootfs（下载/解压到 /data/chroot/ubuntu）
├── ubuntu-start.sh            # 启动：挂载 / --mount-only / --gui / --force
├── ubuntu-stop.sh             # 停止：全量 --force / 仅关图形 --gui-only
├── ubuntu-status.sh           # 状态：人读 / --json
├── start-desktop-x11.sh       # chroot 内启动 KDE Plasma(X11)（拷到 rootfs /usr/local/bin）
├── ubuntu-agent.sh            # ROOT 代理守护进程（解决 KSU 对未授权应用隐藏 su）
├── ubuntu-agent-handler.sh    # 代理请求处理端（token 鉴权 + 白名单动作）
├── fix-thirdparty.sh          # 给 Chromium/Electron 系 .desktop 追加 --no-sandbox
├── restore-thirdparty.sh      # 回滚上面的改动（*.desktop.orig）
├── app/                       # 容器管理器 APK 源码（com.trae.ubuntu.container）
├── dist/                      # 构建产物 Ubuntu-Container-v1.3.apk
└── docs/                      # 使用说明、实测记录、截图
```

## 快速开始（设备端）

```sh
adb push ubuntu-*.sh ubuntu-agent*.sh start-desktop-x11.sh /data/local/tmp/
adb shell "su -c 'chmod 755 /data/local/tmp/ubuntu-*.sh /data/local/tmp/ubuntu-agent*.sh'"

# 仅命令行
adb shell "su -c 'sh /data/local/tmp/ubuntu-start.sh --mount-only'"
adb shell "su -c 'sh /data/local/tmp/ubuntu-start.sh --force'"        # 进入 chroot shell

# 图形
adb shell "su -c 'sh /data/local/tmp/ubuntu-start.sh --gui'"
adb shell "su -c 'sh /data/local/tmp/ubuntu-stop.sh --gui-only'"      # 只关图形，容器继续跑
adb shell "su -c 'sh /data/local/tmp/ubuntu-stop.sh --force'"         # 停容器并卸载全部挂载

# APK 用的 root 代理
adb shell "su -c 'sh /data/local/tmp/ubuntu-agent.sh start'"
```

## APK 构建（无需 gradle）

见 [docs/使用说明-v1.3.md](docs/使用说明-v1.3.md#apk-构建)。
要点：`javac --release 11` → `d8` → `aapt2 compile/link` → 塞 classes.dex → `zipalign` → `apksigner`，
构建目录必须是 **ASCII 路径**（aapt2 不认中文路径），签名 keystore 不随仓库分发，需自备。

## 已知问题

见仓库根目录 [docs/ISSUES-20261105.md](../docs/ISSUES-20261105.md)。
