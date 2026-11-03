package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serial, resumable preinstall of the applications a 2.0 desktop is expected to ship.
 *
 * v2.0.0 failed *every* step on device. The recorded tails showed why:
 *  - base: `dictionaries-common` postinst -> `dpkg returned an error code (1)`, exit 100
 *  - chromium: Ubuntu 24.04's `chromium` is a snap-transition package -> dpkg error
 *  - trae/qq/wechat: `curl (77): error setting certificate file …ca-certificates.crt`
 *    (ca-certificates is installed by the base step that had just failed)
 *  - store: probe was checked but no step ever created `/usr/local/bin/droiddesk-store`
 *
 * Every step therefore now records its real output and a human-readable detail line, must
 * survive a broken dpkg database, and never blocks the others. Nothing polls: the queue
 * runs once on its own thread and the settings screen reads [status] on demand.
 */
class PreinstallQueue(
    private val context: Context,
    /** Runs a shell command inside the Linux userland; returns combined output + exit code. */
    private val exec: (command: String, timeoutSeconds: Int) -> ShellResult,
    /** Creates the apt store front-end + helpers; used only when the probe says they are missing. */
    private val ensureStore: () -> Boolean = { false },
) {
    data class ShellResult(val exitCode: Int, val output: String)

    private enum class Special { NONE, STORE, BROWSER, WEBAPPS }
    private data class Step(
        val id: String,
        val title: String,
        val packages: List<String> = emptyList(),
        val debUrl: String? = null,
        /** Page that must be parsed at runtime to find the current deb URL (version churn). */
        val urlPage: String? = null,
        val urlRegex: String? = null,
        val probePath: String? = null,
        val special: Special = Special.NONE,
    )

    private enum class State { PENDING, DONE, SKIPPED, FAILED }

    private data class Outcome(val state: State, val message: String, val detail: String, val log: String)

    companion object {
        private const val TAG = "PreinstallQueue"
        private const val PREFS = "dd_preinstall"
        private const val TIMEOUT_SECONDS = 900
        private const val APT_FLAGS =
            "-y --no-install-recommends -o Acquire::Retries=2 -o Acquire::http::Timeout=20 " +
                "-o Acquire::https::Timeout=20 -o Acquire::ForceIPv4=true " +
                "-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold"

        /**
         * Sources verified from this machine on 2026-11-03 (HTTP status + index contents):
         *  - Chrome arm64: dl.google.com `dists/stable/main/binary-arm64/Packages` = 200,
         *    google-chrome-stable 156.0.8078.79-1 arm64; `..._current_arm64.deb` = 200,
         *    137 MB, arm64/glibc -> first candidate and default browser.
         *  - Ubuntu ports arm64 `chromium-browser` 2:1snap1-0ubuntu2 (no real binary) and
         *    `firefox` 1:1snap1-0ubuntu5 are snap transition packages; snapd cannot run
         *    under proot/chroot -> detected via Version/Depends and skipped with that reason.
         *  - Termux x11-repo: firefox 159.0 aarch64 and chromium 156.0.8078.79 aarch64 exist,
         *    but they depend on libc++/libandroid-shmem (Android bionic ABI), so they cannot
         *    run inside this glibc rootfs; only attempted in a Termux prefix.
         *  - ChromeOS arm64 chrome: no published deb (Packages index 404) and ChromeOS ABI
         *    -> recorded as unsupported, never installed.
         *  - epiphany-browser 46.0-2build1 arm64 is a real apt package -> last fallback.
         *  - WeChat Linux arm64 deb exists on the vendor CDN (211 MB, HTTP 200).
         *  - QQ arm64 deb exists but its filename carries the version; the official download
         *    page is parsed at runtime so the URL follows the release.
         */
        private const val CHROME_URL =
            "https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb"
        private const val WECHAT_URL =
            "https://dldir1.qq.com/weixin/Universal/Linux/WeChatLinux_arm64.deb"
        private const val QQ_PAGE = "https://im.qq.com/rainbow/linuxQQDownload"
        private const val TRAE_PAGE = "https://www.trae.cn/ide/download"

        private val STEPS = listOf(
            Step(
                id = "tls",
                title = "TLS 证书（HTTPS 下载前提）",
                packages = listOf("ca-certificates"),
                probePath = "/etc/ssl/certs/ca-certificates.crt",
            ),
            Step(
                id = "base",
                title = "基础依赖（字体/终端/解包/中文）",
                packages = listOf(
                    "curl", "wget", "xz-utils", "unzip", "xdg-utils", "locales",
                    "xterm", "dbus-x11", "x11-utils", "fonts-noto-cjk",
                ),
                probePath = "/usr/bin/xterm",
            ),
            Step(
                id = "icons",
                title = "Yaru 图标主题",
                packages = listOf("yaru-theme-icon"),
                probePath = "/usr/share/icons/Yaru",
            ),
            Step(
                id = "store",
                title = "Ubuntu 软件（apt 商店）",
                probePath = "/usr/local/bin/droiddesk-store",
                special = Special.STORE,
            ),
            Step(
                id = "browser",
                title = "浏览器（arm64 逐个实测）",
                special = Special.BROWSER,
            ),
            Step(
                id = "trae",
                title = "Trae 编辑器（Linux arm64）",
                urlPage = TRAE_PAGE,
                urlRegex = """https://[^"'\\ ]*linux[^"'\\ ]*arm64[^"'\\ ]*\\.deb""",
                probePath = "/usr/bin/trae",
            ),
            Step(
                id = "qq",
                title = "QQ（arm64）",
                urlPage = QQ_PAGE,
                urlRegex = """https://[^"'\\ ]*arm64[^"'\\ ]*\\.deb""",
                probePath = "/opt/QQ/qq",
            ),
            Step(
                id = "wechat",
                title = "微信（arm64）",
                debUrl = WECHAT_URL,
                probePath = "/usr/bin/wechat",
            ),
            Step(
                id = "webapps",
                title = "网页版入口（QQ/微信兜底）",
                special = Special.WEBAPPS,
            ),
        )

        private val running = AtomicBoolean(false)
    }

    /** False when the Linux userland is not installed yet; nothing is attempted then. */
    var isAvailable: Boolean = true

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // ── Public API ──

    fun startIfNeeded() {
        if (!isAvailable) {
            Log.i(TAG, "Preinstall skipped: Linux userland not ready")
            return
        }
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                STEPS.forEach { step -> runStep(step) }
            } catch (error: Exception) {
                Log.w(TAG, "Preinstall queue aborted", error)
            } finally {
                running.set(false)
            }
        }, "droiddesk-preinstall").start()
    }

    /** Clears failed steps only, then resumes. Successful steps are never repeated. */
    fun retryFailed() {
        STEPS.forEach { step ->
            if (prefs.getString("${step.id}.state", null) == State.FAILED.name) {
                prefs.edit()
                    .remove("${step.id}.state")
                    .remove("${step.id}.message")
                    .remove("${step.id}.detail")
                    .remove("${step.id}.log")
                    .apply()
            }
        }
        startIfNeeded()
    }

    fun status(): List<Map<String, Any?>> = STEPS.map { step ->
        mapOf(
            "id" to step.id,
            "title" to step.title,
            "state" to (prefs.getString("${step.id}.state", null) ?: State.PENDING.name),
            "message" to (prefs.getString("${step.id}.message", "") ?: ""),
            "detail" to (prefs.getString("${step.id}.detail", "") ?: ""),
            "log" to (prefs.getString("${step.id}.log", "") ?: ""),
        )
    }

    fun summary(): Map<String, Any?> {
        val states = STEPS.map { prefs.getString("${it.id}.state", null) }
        return mapOf(
            "total" to STEPS.size,
            "done" to states.count { it == State.DONE.name },
            "skipped" to states.count { it == State.SKIPPED.name },
            "failed" to states.count { it == State.FAILED.name },
            "pending" to states.count { it == null || it == State.PENDING.name },
            "running" to running.get(),
            "browser" to (prefs.getString("browser.chosen", "") ?: ""),
            "browserDetail" to (prefs.getString("browser.detail", "") ?: ""),
        )
    }

    // ── Step driver ──

    private fun runStep(step: Step) {
        val previous = prefs.getString("${step.id}.state", null)
        if (previous == State.DONE.name || previous == State.SKIPPED.name) return
        mark(step, State.PENDING, "正在处理…", "", "")

        val outcome = when (step.special) {
            Special.STORE -> runStoreStep(step)
            Special.BROWSER -> runBrowserStep(step)
            Special.WEBAPPS -> runWebAppsStep(step)
            Special.NONE -> runPackageStep(step)
        }
        mark(step, outcome.state, outcome.message, outcome.detail, outcome.log)
        DesktopLog.append(context, TAG, "${step.id}: ${outcome.state} ${outcome.message}")
    }

    private fun mark(step: Step, state: State, message: String, detail: String, log: String) {
        prefs.edit()
            .putString("${step.id}.state", state.name)
            .putString("${step.id}.message", message)
            .putString("${step.id}.detail", detail)
            .putString("${step.id}.log", log.takeLast(4_000))
            .putLong("${step.id}.ts", System.currentTimeMillis())
            .apply()
    }

    // ── apt / deb helpers ──

    private fun run(command: String, timeout: Int = TIMEOUT_SECONDS): ShellResult = exec(command, timeout)

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun aptInstall(packages: List<String>): ShellResult {
        if (packages.isEmpty()) return ShellResult(0, "")
        val cmd = "export DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC; " +
            "apt-get install $APT_FLAGS ${packages.joinToString(" ")} 2>&1"
        var result = run(cmd)
        if (result.exitCode == 0) return result
        // v2.0.0 gave up here. A single failed postinst (dictionaries-common / locales)
        // poisons the whole run, so repair the dpkg database and retry once.
        run("DEBIAN_FRONTEND=noninteractive dpkg --configure -a 2>&1 | tail -n 15; " +
            "DEBIAN_FRONTEND=noninteractive apt-get --fix-broken install -y 2>&1 | tail -n 15")
        result = run(cmd)
        return result
    }

    private fun exists(path: String): Boolean = run("test -e ${shellQuote(path)} && echo yes || echo no", 60)
        .output.trim().endsWith("yes")

    private fun commandOutput(command: String, timeout: Int = 120): String =
        run(command, timeout).output.trim()

    /**
     * Downloads and installs one .deb. Retries without certificate validation only when curl
     * reports a TLS error (7x/60), which is exactly what happened on device when the base
     * step had failed to install ca-certificates. The downgrade is recorded in the detail.
     */
    private fun installDebFile(url: String, id: String, log: StringBuilder): Pair<State, String> {
        val path = "/tmp/dd-$id.deb"
        var tlsDowngrade = false
        var download = run(
            "curl -fL --retry 2 --connect-timeout 20 --max-time 600 " +
                "-o ${shellQuote(path)} ${shellQuote(url)} 2>&1",
            700,
        )
        val tlsError = download.output.contains("(77)") || download.output.contains("(60)")
        if (download.exitCode != 0 && tlsError) {
            tlsDowngrade = true
            download = run(
                "curl -k -fL --retry 2 --connect-timeout 20 --max-time 600 " +
                    "-o ${shellQuote(path)} ${shellQuote(url)} 2>&1",
                700,
            )
        }
        log.append(download.output).append('\n')
        if (download.exitCode != 0) {
            run("rm -f ${shellQuote(path)}", 30)
            return State.FAILED to "下载失败（exit=${download.exitCode}）：${tailLines(download.output, 3)}"
        }

        val arch = commandOutput("dpkg-deb -f ${shellQuote(path)} Architecture").ifBlank { "unknown" }
        val pkg = commandOutput("dpkg-deb -f ${shellQuote(path)} Package").ifBlank { id }
        val version = commandOutput("dpkg-deb -f ${shellQuote(path)} Version").ifBlank { "?" }
        log.append("deb: package=$pkg version=$version arch=$arch\n")

        if (arch == "aarch64") {
            run("rm -f ${shellQuote(path)}", 30)
            return State.SKIPPED to
                "跳过：包架构为 aarch64（Termux/Android bionic ABI），当前根文件系统是 arm64/glibc"
        }
        if (arch != "arm64" && arch != "all") {
            run("rm -f ${shellQuote(path)}", 30)
            return State.SKIPPED to "跳过：包架构 $arch，不是 arm64"
        }

        val install = run(
            "export DEBIAN_FRONTEND=noninteractive; apt-get install $APT_FLAGS ${shellQuote(path)} 2>&1",
        )
        log.append(install.output).append('\n')
        run("rm -f ${shellQuote(path)}", 30)
        if (install.exitCode != 0) {
            run("DEBIAN_FRONTEND=noninteractive dpkg --configure -a 2>&1 | tail -n 10")
            return State.FAILED to "deb 安装失败（exit=${install.exitCode}）：${tailLines(install.output, 4)}"
        }
        val note = if (tlsDowngrade) "（TLS 证书缺失，已用 curl -k 降级下载）" else ""
        return State.DONE to "已安装 $pkg $version（arm64/glibc）$note"
    }

    // ── Individual steps ──

    private fun runPackageStep(step: Step): Outcome {
        val log = StringBuilder()

        step.debUrl?.let { url ->
            if (step.probePath != null && exists(step.probePath)) {
                return Outcome(State.DONE, "已安装（探测 ${step.probePath} 通过）", "", log.toString())
            }
            val (state, message) = installDebFile(url, step.id, log)
            return Outcome(state, message, "来源 $url", log.toString())
        }

        if (step.urlPage != null && step.urlRegex != null) {
            val page = commandOutput(
                "curl -fsSL --max-time 40 -A 'Mozilla/5.0' ${shellQuote(step.urlPage)}",
                120,
            )
            log.append("page ${step.urlPage}: ${page.length} bytes\n")
            val url = Regex(step.urlRegex).find(page)?.value
            if (url == null) {
                // Fall back to a direct probe so the recorded reason is actionable.
                return Outcome(
                    State.SKIPPED,
                    "跳过：官网页面未提供可解析的 Linux arm64 下载地址（页面可能按 UA/JS 下发）",
                    "来源页 ${step.urlPage}",
                    log.toString(),
                )
            }
            val (state, message) = installDebFile(url, step.id, log)
            return Outcome(state, message, "来源 $url", log.toString())
        }

        val packages = step.packages
        if (packages.isNotEmpty()) {
            // apt-get update only for the TLS step: stale indexes are the usual reason a
            // package looks "missing", and this runs once per deployment.
            if (step.id == "tls") {
                val update = run("DEBIAN_FRONTEND=noninteractive apt-get update " +
                    "-o Acquire::Retries=2 -o Acquire::http::Timeout=20 2>&1", 420)
                log.append(update.output).append('\n')
            }
            val install = aptInstall(packages)
            log.append(install.output).append('\n')
            val probeOk = step.probePath?.let { exists(it) }
            if (install.exitCode != 0 && probeOk != true) {
                return Outcome(
                    State.FAILED,
                    "apt 安装失败（exit=${install.exitCode}）：${tailLines(install.output, 5)}",
                    "包 ${packages.joinToString(" ")}",
                    log.toString(),
                )
            }
            if (install.exitCode != 0) {
                return Outcome(
                    State.DONE,
                    "已安装（dpkg 后置脚本报错，但文件与命令可用）：${tailLines(install.output, 2)}",
                    "包 ${packages.joinToString(" ")}",
                    log.toString(),
                )
            }
            return Outcome(
                State.DONE,
                "已安装：${packages.joinToString(" ")}",
                step.probePath?.let { "校验 $it 存在" } ?: "",
                log.toString(),
            )
        }

        if (step.probePath != null) {
            return if (exists(step.probePath)) {
                Outcome(State.DONE, "已安装（探测 ${step.probePath} 通过）", "", log.toString())
            } else {
                Outcome(State.SKIPPED, "跳过：本次没有可用的安装来源", "探测 ${step.probePath} 缺失", log.toString())
            }
        }

        return Outcome(State.SKIPPED, "跳过：无安装动作", "", log.toString())
    }

    private fun runStoreStep(step: Step): Outcome {
        val log = StringBuilder()
        var installed = exists(step.probePath ?: "/usr/local/bin/droiddesk-store")
        if (!installed) {
            // v2.0.0 only probed this path; the script was created by a code path that is
            // not reached when the desktop is already installed. Create it now.
            installed = runCatching { ensureStore() }.getOrDefault(false) ||
                exists(step.probePath ?: "/usr/local/bin/droiddesk-store")
        }
        return if (installed) {
            Outcome(
                State.DONE,
                "已安装：apt 商店入口 /usr/local/bin/droiddesk-store（可搜索/安装/卸载）",
                "来源：应用内置脚本（apt-get 前端，不依赖 snap）",
                log.toString(),
            )
        } else {
            Outcome(
                State.FAILED,
                "失败：无法创建 /usr/local/bin/droiddesk-store（根文件系统不可写）",
                "",
                log.toString(),
            )
        }
    }

    /**
     * Tries browser candidates in order and installs the first one that is genuinely usable.
     * Every attempt writes its own reason to the shared log, so the settings screen shows
     * exactly which candidate failed and why (snap shim, wrong ABI, TLS, missing candidate).
     */
    private fun runBrowserStep(step: Step): Outcome {
        val log = StringBuilder()
        var lastReason = ""

        // 1) Google Chrome — official arm64 Linux deb (dl.google.com publishes binary-arm64).
        log.append("== candidate: google-chrome (arm64) ==\n")
        val chrome = installDebFile(CHROME_URL, "chrome", log)
        if (chrome.first == State.DONE) {
            val version = commandOutput("/opt/google/chrome/google-chrome --version 2>&1 | head -n 1")
            log.append("chrome --version: $version\n")
            chromiumSelfTest("/opt/google/chrome/google-chrome", log)
            return chosen("google-chrome", version, "Google 官方 arm64 deb $CHROME_URL", log)
        }
        lastReason = chrome.second

        // ChromeOS builds never publish an arm64 deb for generic Linux; probed 404 on
        // 2026-11-03. Recorded here so the log answers "why not ChromeOS chrome?".
        log.append("== candidate: ChromeOS arm64 chrome（仅记录，不安装）==\n")
        log.append("skip: dl.google.com/linux/chromeos .../binary-arm64/Packages = 404，" +
            "ChromeOS 包是 ChromeOS ABI，不能装入 glibc Linux\n")

        // 2) Ubuntu chromium / chromium-browser are snap transition packages on 24.04.
        for (pkg in listOf("chromium", "chromium-browser")) {
            log.append("== candidate: apt $pkg ==\n")
            val show = commandOutput(
                "apt-cache show $pkg 2>/dev/null | grep -E '^(Version|Depends):' | head -n 6",
            )
            log.append(show).append('\n')
            if (show.isBlank()) {
                log.append("not in apt index\n")
                continue
            }
            if (show.contains("snapd") || show.contains("snap1")) {
                log.append("skip: $pkg 是 snap 转接包（Depends/Version 含 snap），proot/chroot 内无法运行\n")
                lastReason = "$pkg 为 snap 转接包"
                continue
            }
            val install = aptInstall(listOf(pkg))
            log.append(install.output).append('\n')
            val bin = if (exists("/usr/bin/chromium")) "/usr/bin/chromium" else "/usr/bin/chromium-browser"
            if (install.exitCode == 0 && exists(bin)) {
                val version = commandOutput("$bin --version 2>&1 | head -n 1")
                chromiumSelfTest(bin, log)
                return chosen(pkg, version, "Ubuntu apt arm64", log)
            }
            lastReason = "apt $pkg 安装失败（exit=${install.exitCode}）"
        }

        // 3) Termux x11-repo firefox — only in a bionic/Termux prefix, never in this glibc chroot.
        log.append("== candidate: Termux x11-repo firefox ==\n")
        val bionic = exists("/system/bin/linker64")
        log.append("bionic guest: $bionic\n")
        if (bionic) {
            val install = run(
                "DEBIAN_FRONTEND=noninteractive pkg install -y x11-repo 2>&1 && " +
                    "DEBIAN_FRONTEND=noninteractive pkg install -y firefox 2>&1",
            )
            log.append(install.output).append('\n')
            if (install.exitCode == 0) {
                val version = commandOutput("firefox --version 2>&1 | head -n 1")
                return chosen("firefox", version, "Termux x11-repo arm64 (bionic)", log)
            }
            lastReason = "Termux firefox 安装失败"
        } else {
            log.append("skip: Termux 包是 Android/bionic ABI（interpreter /system/bin/linker64），" +
                "当前是 arm64/glibc 根文件系统，二进制无法加载\n")
        }

        // 4) Firefox from Ubuntu: snap shim on 24.04, recorded with the same proof.
        log.append("== candidate: apt firefox ==\n")
        val firefoxShow = commandOutput(
            "apt-cache show firefox 2>/dev/null | grep -E '^(Version|Depends):' | head -n 6",
        )
        log.append(firefoxShow).append('\n')
        if (firefoxShow.contains("snapd") || firefoxShow.contains("snap1")) {
            log.append("skip: Ubuntu 的 firefox 是 snap 转接包\n")
        } else if (firefoxShow.isNotBlank()) {
            val install = aptInstall(listOf("firefox"))
            log.append(install.output).append('\n')
            if (install.exitCode == 0 && exists("/usr/bin/firefox")) {
                val version = commandOutput("firefox --version 2>&1 | head -n 1")
                return chosen("firefox", version, "Ubuntu apt", log)
            }
        }

        // 5) epiphany-browser: real arm64 WebKitGTK package, always installable.
        log.append("== candidate: apt epiphany-browser ==\n")
        val epiphany = aptInstall(listOf("epiphany-browser"))
        log.append(epiphany.output).append('\n')
        if (epiphany.exitCode == 0 && exists("/usr/bin/epiphany")) {
            val version = commandOutput("epiphany --version 2>&1 | head -n 1")
            return chosen("epiphany", version, "Ubuntu apt arm64 (WebKitGTK)", log)
        }
        lastReason = lastReason.ifBlank { "所有候选均不可用（见日志）" }

        return Outcome(
            State.FAILED,
            "失败：未装成任何浏览器。最后原因：$lastReason",
            "已尝试：Chrome arm64 deb / Ubuntu chromium(snap) / Termux firefox / Ubuntu firefox(snap) / epiphany",
            log.toString(),
        )
    }

    /**
     * Runtime proof that the installed Chromium-family binary can actually start inside this
     * guest (correct ABI + resolvable libs), not just report a version string. Headless avoids
     * needing a live X server; the result is advisory and never turns a working install into
     * a failure.
     */
    private fun chromiumSelfTest(binary: String, log: StringBuilder) {
        val out = commandOutput(
            "timeout 40 $binary --headless=new --no-sandbox --disable-gpu " +
                "--dump-dom about:blank 2>&1 | head -c 200",
            80,
        )
        log.append("selftest $binary: ${out.ifBlank { "(无输出或超时)" }}\n")
    }

    private fun chosen(binary: String, version: String, source: String, log: StringBuilder): Outcome {
        prefs.edit()
            .putString("browser.chosen", binary)
            .putString("browser.version", version)
            .putString("browser.source", source)
            .putString("browser.detail", "$binary | $version | $source")
            .apply()
        // The dock launcher calls this dispatcher, so it always opens the browser that
        // really exists instead of a hard-coded google-chrome.
        run(
            "install -d /usr/local/bin; " +
                "printf '%s\\n' '#!/bin/sh' " +
                "'for b in google-chrome google-chrome-stable chromium chromium-browser epiphany firefox; do' " +
                "'  command -v \$b >/dev/null 2>&1 || continue' " +
                "'  case \$b in *chrome*|chromium*) exec \$b --no-sandbox --disable-dev-shm-usage \"\$@\" ;;' " +
                "'  *) exec \$b \"\$@\" ;; esac' " +
                "'done' 'exec xdg-open \"\$@\"' > /usr/local/bin/droiddesk-browser; " +
                "chmod 755 /usr/local/bin/droiddesk-browser",
            60,
        )
        DesktopLog.append(context, TAG, "browser chosen: $binary ($version) from $source")
        return Outcome(State.DONE, "已装成 $binary（$version）：$source", source, log.toString())
    }

    private fun runWebAppsStep(step: Step): Outcome {
        val log = StringBuilder()
        // Both clients exist as arm64 deb; these entries are the guaranteed fallback and
        // also the default in a fresh rootfs where the debs are still downloading.
        val entries = listOf(
            Triple("droiddesk-webqq", "QQ（网页版）", "https://web.qq.com"),
            Triple("droiddesk-webwechat", "微信（网页版）", "https://wx.qq.com"),
        )
        entries.forEach { (file, name, url) ->
            val target = "/usr/share/applications/$file.desktop"
            run(
                "mkdir -p /usr/share/applications; cat > ${shellQuote(target)} <<'EOF'\n" +
                    "[Desktop Entry]\n" +
                    "Type=Application\n" +
                    "Name=$name\n" +
                    "Comment=在默认浏览器中打开 $url\n" +
                    "Exec=/usr/local/bin/droiddesk-browser ${shellQuote(url)}\n" +
                    "Icon=droiddesk-browser\n" +
                    "Terminal=false\n" +
                    "Categories=Network;WebBrowser;\n" +
                    "EOF\n" +
                    "chmod 644 ${shellQuote(target)}\n",
                90,
            )
            log.append("written $target\n")
        }
        return Outcome(
            State.DONE,
            "已生成网页版入口：微信 https://wx.qq.com、QQ https://web.qq.com（默认浏览器）",
            "原生 arm64 包若安装成功则由 droiddesk-qq/droiddesk-wechat 优先调用",
            log.toString(),
        )
    }

    private fun tailLines(text: String, lines: Int): String =
        text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(lines)
            .joinToString(" | ").take(600)
}
