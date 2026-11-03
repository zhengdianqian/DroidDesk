package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background, serial, resumable preinstall of the apps the 2.0 setup is expected to ship.
 *
 * Why a queue and not "one big apt install": every package here has a different
 * availability story on arm64/proot (Google Chrome has no arm64 Linux deb at all, WeChat's
 * arm64 build is not on the public CDN, snap is unusable inside chroot, …). A single
 * failure must never block the others or the desktop ready state, so each step:
 *
 *  - runs in isolation with its own timeout,
 *  - records `done` / `skipped(原因)` / `failed(原因)` in SharedPreferences,
 *  - is skipped on the next run when already done,
 *  - can be retried individually or all at once without re-running successful steps.
 *
 * It is started once from the app process (after the desktop environment is installed) and
 * runs on its own thread. Nothing polls: the Flutter settings screen reads [status] on
 * demand and the native journal records the outcome.
 */
class PreinstallQueue(
    private val context: Context,
    /** Runs a shell command inside the Linux userland; returns combined output + exit marker. */
    private val exec: (command: String, timeoutSeconds: Int) -> ShellResult,
) {
    data class ShellResult(val exitCode: Int, val output: String)

    private data class Step(
        val id: String,
        val title: String,
        /** apt packages installed together; empty for verification-only steps. */
        val aptPackages: List<String> = emptyList(),
        /** Optional direct .deb download; skipped when the CDN has no usable build. */
        val debUrl: String? = null,
        val expectedArch: String = "arm64",
        /** Guest path that proves the app is present after installation. */
        val probePath: String? = null,
        val probePackage: String? = null,
    )

    private enum class State { PENDING, DONE, SKIPPED, FAILED }

    companion object {
        private const val TAG = "PreinstallQueue"
        private const val PREFS = "dd_preinstall"
        private const val TIMEOUT_SECONDS = 900
        private const val APT_FLAGS =
            "-y --no-install-recommends -o Acquire::Retries=2 -o Acquire::http::Timeout=20 " +
                "-o Acquire::https::Timeout=20 -o Acquire::ForceIPv4=true"
        private val running = AtomicBoolean(false)

        /**
         * Package sources. QQ/WeChat/Trae have no arm64 package in the Ubuntu archive and
         * their vendor CDNs are not guaranteed to publish one; those steps degrade to
         * `skipped` with the exact curl/dpkg error recorded, never to a hard failure.
         * Chrome is replaced by Chromium from apt because Google ships amd64 only.
         */
        private val STEPS = listOf(
            Step(
                id = "base",
                title = "基础依赖",
                aptPackages = listOf(
                    "ca-certificates", "curl", "wget", "xz-utils", "fonts-noto-cjk",
                ),
            ),
            // Separate step: yaru-theme-icon is absent from some minimal images, and a single
            // missing package must not abort the base dependencies above.
            Step(
                id = "icons",
                title = "Yaru 图标主题",
                aptPackages = listOf("yaru-theme-icon"),
                probePath = "/usr/share/icons/Yaru",
            ),
            Step(
                id = "store",
                title = "Ubuntu 软件商店入口（apt 前端）",
                probePath = "/usr/local/bin/droiddesk-store",
            ),
            Step(
                id = "chrome",
                title = "Chromium 浏览器（Chrome 无 arm64 官方包）",
                aptPackages = listOf("chromium"),
                probePackage = "chromium",
                probePath = "/usr/bin/chromium",
            ),
            Step(
                id = "trae",
                title = "Trae IDE",
                debUrl = "https://lf-cdn.trae.ai/obj/trae-ai-download/Trae-linux-arm64.deb",
            ),
            Step(
                id = "qq",
                title = "QQ（Linux NT）",
                debUrl = "https://dldir1.qq.com/qqfile/qq/QQNT/Linux/linuxqq_arm64.deb",
            ),
            Step(
                id = "wechat",
                title = "微信（Linux 通用版）",
                debUrl = "https://dldir1.qq.com/weixin/Universal/Linux/WeChatLinux_arm64.deb",
            ),
        )
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Starts the queue unless it is already running. Safe to call from every app start. */
    fun startIfNeeded() {
        if (!isAvailable) return
        if (!running.compareAndSet(false, true)) return
        val pending = STEPS.filter { stateOf(it) != State.DONE }
        if (pending.isEmpty()) {
            running.set(false)
            return
        }
        Thread({ runSteps(pending) }, "preinstall-queue").start()
    }

    fun retryFailed() {
        STEPS.forEach { step ->
            if (stateOf(step) == State.FAILED) {
                prefs.edit()
                    .remove("${step.id}.state")
                    .remove("${step.id}.message")
                    .apply()
            }
        }
        startIfNeeded()
    }

    fun isRunning(): Boolean = running.get()

    fun status(): List<Map<String, Any>> = STEPS.map { step ->
        mapOf(
            "id" to step.id,
            "title" to step.title,
            "state" to stateOf(step).name.lowercase(),
            "message" to prefs.getString("${step.id}.message", "").orEmpty(),
        )
    }

    fun summary(): Map<String, Any> {
        val states = STEPS.map { stateOf(it) }
        return mapOf(
            "running" to running.get(),
            "total" to STEPS.size,
            "done" to states.count { it == State.DONE },
            "skipped" to states.count { it == State.SKIPPED },
            "failed" to states.count { it == State.FAILED },
            "pending" to states.count { it == State.PENDING },
        )
    }

    private fun stateOf(step: Step): State = when (prefs.getString("${step.id}.state", null)) {
        "done" -> State.DONE
        "skipped" -> State.SKIPPED
        "failed" -> State.FAILED
        else -> State.PENDING
    }

    private fun runSteps(steps: List<Step>) {
        try {
            // One apt lock at a time: the desktop session may be upgrading packages.
            steps.forEach { step -> execute(step) }
        } finally {
            running.set(false)
            DesktopLog.append(context, TAG, "preinstall queue finished: ${summary()}")
        }
    }

    private fun execute(step: Step) {
        // Resume: a successful step is never repeated, even after a crash mid-queue.
        if (step.probePath != null && fileExists(step.probePath)) {
            record(step, State.DONE, "已存在")
            return
        }
        if (step.probePackage != null && packageInstalled(step.probePackage)) {
            record(step, State.DONE, "已安装")
            return
        }
        if (step.aptPackages.isNotEmpty() && installViaApt(step)) return
        if (step.debUrl != null && installViaDeb(step)) return
        if (step.probePath != null || step.aptPackages.isNotEmpty() || step.debUrl != null) {
            // Errors are already recorded by the individual attempts.
            if (stateOf(step) == State.PENDING) {
                record(step, State.SKIPPED, "无可用安装来源")
            }
        }
    }

    private fun installViaApt(step: Step): Boolean {
        val packages = step.aptPackages.joinToString(" ")
        val result = run("DEBIAN_FRONTEND=noninteractive apt-get install $APT_FLAGS $packages", TIMEOUT_SECONDS)
        if (result.exitCode == 0) {
            record(step, State.DONE, "apt 安装成功：$packages")
            return true
        }
        record(
            step,
            State.FAILED,
            "apt 安装失败（exit=${result.exitCode}）：${tail(result.output)}",
        )
        return false
    }

    /**
     * Downloads the vendor .deb, verifies the real package architecture (CDNs happily serve
     * amd64 to an arm64 device) and installs it with apt so dependencies still resolve.
     */
    private fun installViaDeb(step: Step): Boolean {
        val url = step.debUrl ?: return false
        val target = "/tmp/droiddesk-preinstall-${step.id}.deb"
        val download = run(
            "rm -f $target && curl -fL --connect-timeout 20 --max-time 600 -o $target ${shellQuote(url)}",
            TIMEOUT_SECONDS,
        )
        if (download.exitCode != 0) {
            record(step, State.SKIPPED, "下载失败（${download.exitCode}）：${tail(download.output)}")
            return false
        }
        val arch = run("dpkg-deb -f $target Architecture 2>/dev/null").output.trim()
        if (arch.isEmpty()) {
            record(step, State.SKIPPED, "不是有效的 deb 包（缺少 Architecture 字段）")
            run("rm -f $target")
            return false
        }
        if (arch != step.expectedArch && arch != "all") {
            record(
                step,
                State.SKIPPED,
                "官方包架构为 $arch，与本机 ${step.expectedArch} 不兼容，已跳过",
            )
            run("rm -f $target")
            return false
        }
        val install = run("DEBIAN_FRONTEND=noninteractive apt-get install $APT_FLAGS $target", TIMEOUT_SECONDS)
        val ok = install.exitCode == 0
        record(
            step,
            if (ok) State.DONE else State.FAILED,
            if (ok) "已安装 ${step.title}" else "dpkg 安装失败：${tail(install.output)}",
        )
        run("rm -f $target")
        return ok
    }

    private fun packageInstalled(name: String): Boolean {
        val result = run("dpkg-query -W -f='\${Status}' ${shellQuote(name)} 2>/dev/null")
        return result.exitCode == 0 && result.output.contains("install ok installed")
    }

    private fun fileExists(path: String): Boolean =
        run("test -e ${shellQuote(path)}").exitCode == 0

    private fun run(command: String, timeoutSeconds: Int = 120): ShellResult {
        if (!isAvailable) return ShellResult(-1, "当前运行模式不支持在 Linux 内执行命令")
        return runCatching { exec(command, timeoutSeconds) }
            .getOrElse { ShellResult(-1, it.message ?: it.javaClass.simpleName) }
    }

    /** Set by the caller: false when neither chroot nor the native prefix is ready. */
    var isAvailable: Boolean = true

    private fun record(step: Step, state: State, message: String) {
        prefs.edit()
            .putString("${step.id}.state", state.name.lowercase())
            .putString("${step.id}.message", message)
            .putLong("${step.id}.ts", System.currentTimeMillis())
            .apply()
        DesktopLog.append(context, TAG, "${step.id}: ${state.name.lowercase()} — $message")
        Log.i(TAG, "${step.id}: ${state.name.lowercase()} — $message")
    }

    private fun tail(text: String, maxLines: Int = 3): String = text
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .takeLast(maxLines)
        .joinToString(" | ")
        .take(300)

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
