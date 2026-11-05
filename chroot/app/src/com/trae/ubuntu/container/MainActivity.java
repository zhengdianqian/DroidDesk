package com.trae.ubuntu.container;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ubuntu chroot 容器管理界面 v1.3
 * 特权操作通道（按顺序尝试）：
 *   1) root 代理：127.0.0.1:47123（KSU 越狱模式对未授权应用隐藏 su 时的唯一可用通道）
 *   2) su -c：若本应用已在 KSU 中被授权，则直接走 su
 */
public class MainActivity extends Activity {

    private static final String DIR = "/data/local/tmp";
    private static final String SH_START = DIR + "/ubuntu-start.sh";
    private static final String SH_STOP = DIR + "/ubuntu-stop.sh";
    private static final String SH_STATUS = DIR + "/ubuntu-status.sh";
    private static final String PKG = "com.trae.ubuntu.container";
    private static final int AGENT_PORT = 47123;
    private static final String[] TOKEN_FILES = {
            "/data/data/" + PKG + "/files/agent.token",
            DIR + "/.ub_agent_token"};

    private static final int C_BG = 0xFF0F1115;
    private static final int C_CARD = 0xFF1B1E24;
    private static final int C_TEXT = 0xFFE8EAED;
    private static final int C_DIM = 0xFF9AA0A6;
    private static final int C_OK = 0xFF4ADE80;
    private static final int C_BAD = 0xFFF87171;
    private static final int C_ACCENT = 0xFF4F8CFF;

    private TextView tvRoot, tvContainer, tvGui, tvDetail, tvResult;
    private Button btnGui, btnCmd, btnOpenGui, btnCloseGui, btnStop, btnRefresh;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;
    private volatile String lastRaw = "";
    private static final String[] SU_CANDIDATES =
            {"/system/bin/su", "/system/xbin/su", "/debug_ramdisk/su", "/sbin/su", "su"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        refresh();
    }

    // ---------------- UI ----------------

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(24));
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Ubuntu 容器");
        title.setTextColor(C_TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("chroot · KDE Plasma · Termux:X11   v1.3");
        sub.setTextColor(C_DIM);
        sub.setTextSize(12);
        sub.setPadding(0, dp(2), 0, dp(14));
        root.addView(sub);

        // --- 状态卡片 ---
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(C_CARD, dp(14)));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(sectionLabel("状态"));
        tvRoot = addRow(card, "ROOT 权限");
        tvContainer = addRow(card, "容器");
        tvGui = addRow(card, "图形");
        tvDetail = new TextView(this);
        tvDetail.setTextColor(C_DIM);
        tvDetail.setTextSize(12);
        tvDetail.setPadding(0, dp(8), 0, 0);
        tvDetail.setText("正在读取…");
        card.addView(tvDetail);

        // --- 操作按钮 ---
        TextView opLabel = sectionLabel("操作");
        opLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(opLabel);

        btnGui = addButton(root, "启动容器（图形）", C_ACCENT);
        btnCmd = addButton(root, "启动容器（仅命令）", 0xFF2A2E36);
        btnOpenGui = addButton(root, "打开图形（容器保持运行）", 0xFF2A2E36);
        btnCloseGui = addButton(root, "关闭图形（容器保持运行）", 0xFF2A2E36);
        btnStop = addButton(root, "停止容器", 0xFF7F1D1D);
        btnRefresh = addButton(root, "刷新状态", 0xFF2A2E36);

        btnGui.setOnClickListener(v -> doStartGui(false));
        btnCmd.setOnClickListener(v -> doStartCmd());
        btnOpenGui.setOnClickListener(v -> doStartGui(true));
        btnCloseGui.setOnClickListener(v -> doCloseGui());
        btnStop.setOnClickListener(v -> doStop());
        btnRefresh.setOnClickListener(v -> refresh());

        // --- 结果 ---
        TextView resLabel = sectionLabel("结果");
        resLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(resLabel);

        tvResult = new TextView(this);
        tvResult.setTextColor(C_DIM);
        tvResult.setTextSize(12);
        tvResult.setTypeface(Typeface.MONOSPACE);
        tvResult.setText("就绪");
        tvResult.setBackground(roundRect(C_CARD, dp(12)));
        tvResult.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(tvResult, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        return sv;
    }

    private TextView sectionLabel(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(C_DIM);
        v.setTextSize(11);
        v.setLetterSpacing(0.08f);
        return v;
    }

    private TextView addRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(C_DIM);
        l.setTextSize(13);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setText("—");
        v.setTextColor(C_TEXT);
        v.setTextSize(13);
        v.setGravity(Gravity.END);
        row.addView(v);

        parent.addView(row);
        return v;
    }

    private Button addButton(LinearLayout parent, String text, int color) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(C_TEXT);
        btn.setTextSize(14);
        btn.setAllCaps(false);
        btn.setBackground(roundRect(color, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(8);
        parent.addView(btn, lp);
        return btn;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    // ---------------- 命令执行 ----------------

    private String run(String cmd, int timeoutSec) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            java.io.IOException lastErr = null;
            for (String cand : SU_CANDIDATES) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(cand, "-c", cmd);
                    pb.redirectErrorStream(true);
                    pb.environment().put("PATH",
                            "/system/bin:/system/xbin:/vendor/bin:/sbin:/usr/bin:/bin");
                    p = pb.start();
                    break;
                } catch (java.io.IOException e) {
                    lastErr = e;
                }
            }
            if (p == null) throw (lastErr != null ? lastErr : new java.io.IOException("no su"));
            final Process fp = p;
            final int t = timeoutSec;
            Thread watchdog = new Thread(() -> {
                try { Thread.sleep(t * 1000L); } catch (InterruptedException ignored) { }
                try { fp.destroy(); } catch (Exception ignored) { }
            });
            watchdog.setDaemon(true);
            watchdog.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 20000) break;
            }
            p.waitFor();
            watchdog.interrupt();
        } catch (Exception e) {
            sb.append("EXEC_ERROR: ").append(e.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) { }
        }
        return sb.toString().trim();
    }

    // ---------------- root 代理 ----------------

    private String readToken() {
        for (String f : TOKEN_FILES) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String t = r.readLine();
                if (t != null && t.trim().length() >= 8) return t.trim();
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** 调用 root 代理；不可达/未授权返回 null */
    private String agentCall(String action, int timeoutSec) {
        String token = readToken();
        if (token == null) return null;
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", AGENT_PORT), 2500);
            s.setSoTimeout(Math.max(5, timeoutSec) * 1000);
            OutputStream os = s.getOutputStream();
            os.write((token + " " + action + "\n").getBytes("UTF-8"));
            os.flush();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream is = s.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                // 协议以 "ELAPSED <ms>" 结尾；见到即可返回，不依赖对端关闭（后台守护进程可能持有 fd）
                String acc = new String(bos.toByteArray(), "UTF-8");
                if (acc.contains("ELAPSED ") && acc.endsWith("\n")) break;
                if (bos.size() > 400000) break;
            }
            String out = new String(bos.toByteArray(), "UTF-8").trim();
            if (out.startsWith("ERR") && out.contains("token")) return null;
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (s != null) s.close(); } catch (Exception ignored) { }
        }
    }

    private JSONObject jsonFrom(String out) {
        if (out == null) return null;
        int k = out.indexOf("RESULT ");
        try {
            if (k >= 0) {
                int e = out.indexOf('\n', k);
                String j = (e < 0 ? out.substring(k + 7) : out.substring(k + 7, e)).trim();
                return new JSONObject(j);
            }
            int i = out.indexOf('{'), j = out.lastIndexOf('}');
            if (i >= 0 && j > i) return new JSONObject(out.substring(i, j + 1));
        } catch (Exception ignored) { }
        return null;
    }

    private boolean hasAgent = false;

    /** 代理优先，su 兜底 */
    private String call(String action, String suCmd, int timeoutSec) {
        String out = agentCall(action, timeoutSec);
        if (out != null) {
            hasAgent = true;
            lastRaw = "[agent] " + out;
            return out;
        }
        hasAgent = false;
        lastRaw = "[su] " + (suCmd == null ? "" : suCmd);
        return suCmd == null ? "" : run(suCmd, timeoutSec);
    }

    private boolean rootAvailable() {
        return hasAgent || agentCall("ping", 5) != null || rootOk();
    }

    private boolean rootOk() {
        String out = run("id -u", 8);
        return out.trim().equals("0") && !out.startsWith("EXEC_ERROR");
    }

    private JSONObject readStatus() {
        String out = call("status", "sh " + SH_STATUS + " --json 2>&1", 40);
        JSONObject o = jsonFrom(out);
        if (o != null) lastRaw = out;
        return o;
    }

    // ---------------- 状态刷新 ----------------

    private void refresh() {
        if (busy) { toast("有操作正在执行"); return; }
        pool.execute(() -> {
            final JSONObject o = readStatus();
            ui.post(() -> render(o));
        });
    }

    private void render(JSONObject o) {
        if (o == null) {
            tvRoot.setText("不可用");
            tvRoot.setTextColor(C_BAD);
            tvContainer.setText("未知");
            tvContainer.setTextColor(C_BAD);
            tvGui.setText("—");
            tvDetail.setText("先恢复 ROOT：本应用未获 KSU 授权，且 root 代理未运行。\n"
                    + "任选其一：\n"
                    + "1) 在 KSU 管理器「超级用户」中授权 " + PKG + "\n"
                    + "2) 用 root 终端执行: su -c 'sh /data/local/tmp/ubuntu-agent.sh start'\n\n"
                    + "原始输出:\n" + lastRaw);
            return;
        }
        boolean root = o.optInt("root") == 1;
        boolean cont = o.optInt("container") == 1;
        boolean gui = o.optInt("gui") == 1;
        int mounts = o.optInt("mounts");
        int procs = o.optInt("procs");
        String res = o.optString("res", "NA");

        tvRoot.setText(root ? (hasAgent ? "可用 (root 代理)" : "已授权 (uid=0)") : "不可用");
        tvRoot.setTextColor(root ? C_OK : C_BAD);

        tvContainer.setText(cont ? "运行中" : "已停止");
        tvContainer.setTextColor(cont ? C_OK : C_DIM);

        tvGui.setText(gui ? "桌面运行中" : "未启动");
        tvGui.setTextColor(gui ? C_OK : C_DIM);

        tvDetail.setText("挂载 " + mounts + " 条 · chroot 进程 " + procs + " 个"
                + "\nX11 " + (o.optInt("x11") == 1 ? "就绪" : "未就绪")
                + " · 分辨率 " + res
                + "\n通道 " + (hasAgent ? "root 代理 127.0.0.1:47123" : "su")
                + " · rootfs /data/chroot/ubuntu");

        boolean r = root;
        btnGui.setEnabled(r && !busy);
        btnCmd.setEnabled(r && (!cont) && !busy);
        btnOpenGui.setEnabled(r && cont && !gui && !busy);
        btnCloseGui.setEnabled(r && gui && !busy);
        btnStop.setEnabled(r && cont && !busy);
        btnRefresh.setEnabled(!busy);
    }

    // ---------------- 操作 ----------------

    private void begin(String msg) {
        busy = true;
        tvResult.setText(msg);
        btnGui.setEnabled(false); btnCmd.setEnabled(false); btnOpenGui.setEnabled(false);
        btnCloseGui.setEnabled(false); btnStop.setEnabled(false); btnRefresh.setEnabled(false);
    }

    private void finish(String msg, boolean ok) {
        busy = false;
        tvResult.setText(msg);
        tvResult.setTextColor(ok ? C_TEXT : C_BAD);
        refresh();
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private interface Checker { boolean test(JSONObject o); }

    /** 统一动作执行：无论成功/失败/异常都必须 finish()，避免按钮一直卡在 busy */
    private void action(String beginMsg, String agentAction, String suCmd, int timeoutSec,
                        Checker chk, String okMsg, String failMsg) {
        begin(beginMsg);
        pool.execute(() -> {
            long t0 = System.currentTimeMillis();
            String out = "";
            String extra = "";
            JSONObject o = null;
            boolean ok = false;
            try {
                out = call(agentAction, suCmd, timeoutSec);
                o = jsonFrom(out);
                if (o == null) o = readStatus();
                ok = o != null && chk.test(o);
            } catch (Throwable t) {
                extra = "\n异常: " + t;
            } finally {
                final String fout = out, fextra = extra;
                final JSONObject fo = o;
                final boolean fok = ok;
                final long fms = System.currentTimeMillis() - t0;
                ui.post(() -> finish((fok ? okMsg : failMsg) + "  耗时 " + fms + "ms\n"
                        + lastLines(fout, 14) + fextra + "\n" + brief(fo), fok));
            }
        });
    }

    private void doStartGui(boolean onlyOpen) {
        action(onlyOpen ? "正在打开图形（容器保持运行）…" : "正在启动容器与图形…",
                "start-gui", "nohup sh " + SH_START + " --gui >" + DIR + "/gui.log 2>&1 &", 130,
                o -> o.optInt("gui") == 1 && o.optInt("x11") == 1,
                "图形已就绪（KDE 桌面运行中）", "图形未就绪");
    }

    private void doStartCmd() {
        action("正在启动容器（仅命令）…",
                "start-cmd", "sh " + SH_START + " --mount-only", 90,
                o -> o.optInt("container") == 1,
                "容器已就绪（未开图形）", "容器启动失败");
    }

    private void doCloseGui() {
        action("正在关闭图形（容器保持运行）…",
                "gui-off", "sh " + SH_STOP + " --gui-only", 90,
                o -> o.optInt("gui") == 0 && o.optInt("container") == 1,
                "图形已关闭，容器仍在运行", "关闭图形后状态不符");
    }

    private void doStop() {
        new AlertDialog.Builder(this)
                .setTitle("停止容器")
                .setMessage("将结束 chroot 内全部进程并卸载所有挂载（等同 ubuntu-stop.sh --force）。rootfs 数据不会删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定停止", (d, w) ->
                        action("正在停止容器…",
                                "stop", "sh " + SH_STOP + " --force", 150,
                                o -> o.optInt("mounts") == 0,
                                "容器已停止，挂载已全部卸载", "容器停止未完成"))
                .show();
    }

    private String brief(JSONObject o) {
        if (o == null) return "状态: 读取失败";
        return "状态: 容器=" + (o.optInt("container") == 1 ? "运行" : "停止")
                + " 挂载=" + o.optInt("mounts")
                + " 进程=" + o.optInt("procs")
                + " 图形=" + (o.optInt("gui") == 1 ? "开" : "关")
                + " X11=" + (o.optInt("x11") == 1 ? "就绪" : "未就绪")
                + " 分辨率=" + o.optString("res", "NA");
    }

    private String lastLines(String s, int n) {
        String[] a = s.split("\n");
        if (a.length <= n) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = a.length - n; i < a.length; i++) sb.append(a[i]).append('\n');
        return sb.toString().trim();
    }
}
