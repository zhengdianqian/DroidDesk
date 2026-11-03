package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.math.roundToInt

/**
 * Touch-friendly Ubuntu 24.04-inspired XFCE defaults.
 *
 * Layout: a dark Ubuntu-style top bar (app menu, window buttons, tray, clock) plus a
 * vertical left dock, like Ubuntu's launcher on a rotated phone. All sizes scale from
 * [BASE_*] by the user's UI scale so the panels stay usable on both phones and tablets.
 *
 * Everything here targets software rendering (llvmpipe): the compositor stays off, GTK
 * animations stay off, and icons use the light Yaru theme when it is installed (falling
 * back to Papirus). No GPU effects, no frame-budget hogs.
 *
 * The profile is rewritten when the scale, power mode or icon theme changes; the marker
 * name encodes all of them, so a settings change is picked up on the next session start.
 */
object XfceMobileProfile {
    private const val TAG = "XfceMobileProfile"
    private const val PROFILE_VERSION = "v19"
    private const val MONOSPACE_FONT = "DejaVu Sans Mono"
    private const val WALLPAPER_ASSET = "droiddesk/sunset-wave-wallpaper.jpg"

    /** Width of the vertical left launcher dock at the reference scale (1.5x). */
    const val BASE_DOCK_SIZE_PX = 88
    const val BASE_TOP_BAR_SIZE_PX = 46
    private const val REFERENCE_SCALE = 1.5

    fun scaleFactor(uiScale: Double): Double = uiScale / REFERENCE_SCALE

    fun dockHeightPx(uiScale: Double): Int =
        (BASE_DOCK_SIZE_PX * scaleFactor(uiScale)).roundToInt()

    private fun markerName(uiScale: Double, powerMode: String, iconTheme: String): String =
        ".droiddesk-xfce-mobile-$PROFILE_VERSION-${uiScale}-$powerMode-$iconTheme"

    /** True when the profile for the current scale/power mode is not installed yet. */
    fun needsInstall(
        homeDir: File,
        uiScale: Double = 1.5,
        powerMode: String = "standard",
        iconTheme: String = "Papirus",
    ): Boolean = !File(homeDir, markerName(uiScale, powerMode, iconTheme)).exists()

    fun install(
        context: Context,
        homeDir: File,
        wallpaperFile: File,
        wallpaperPathInSession: String = wallpaperFile.absolutePath,
        uiScale: Double = 1.5,
        powerMode: String = "standard",
        iconTheme: String = "Papirus",
    ): Boolean {
        val marker = File(homeDir, markerName(uiScale, powerMode, iconTheme))
        if (marker.exists()) return true

        return try {
            wallpaperFile.parentFile?.mkdirs()
            context.assets.open(WALLPAPER_ASSET).use { input ->
                wallpaperFile.outputStream().use(input::copyTo)
            }

            val s = scaleFactor(uiScale)
            val dpi = DisplayProfile.xftDpi(uiScale)

            val xfconfDir = File(
                homeDir,
                ".config/xfce4/xfconf/xfce-perchannel-xml",
            ).apply { mkdirs() }
            File(xfconfDir, "xfce4-panel.xml").writeText(panelConfig(s, powerMode))
            File(xfconfDir, "xfce4-desktop.xml").writeText(
                desktopConfig(xmlEscape(wallpaperPathInSession), s),
            )
            File(xfconfDir, "xfwm4.xml").writeText(windowManagerConfig(s))
            File(xfconfDir, "xsettings.xml").writeText(
                xsettingsConfig(dpi, s, iconTheme),
            )
            File(xfconfDir, "xfce4-terminal.xml").writeText(terminalConfig())
            installMonospaceFontRule(homeDir)
            installPanelCss(homeDir, s)
            installAutoMaximize(homeDir)
            installGtkSettings(homeDir, animations = false)

            val panelDir = File(homeDir, ".config/xfce4/panel")
            writeLauncher(
                File(panelDir, "launcher-21/droiddesk-store.desktop"),
                name = "Ubuntu Software",
                comment = "Install Linux applications and .deb packages (apt front end)",
                exec = "/usr/local/bin/droiddesk-store",
                icon = "system-software-install",
            )
            writeLauncher(
                File(panelDir, "launcher-22/droiddesk-terminal.desktop"),
                name = "Terminal",
                comment = "Open the Linux terminal",
                exec = "xfce4-terminal",
                icon = "utilities-terminal",
            )
            writeLauncher(
                File(panelDir, "launcher-23/droiddesk-files.desktop"),
                name = "Files",
                comment = "Browse files",
                exec = "thunar %u",
                icon = "system-file-manager",
            )
            writeLauncher(
                File(panelDir, "launcher-24/droiddesk-browser.desktop"),
                name = "Web Browser",
                comment = "Browse the web",
                exec = "exo-open --launch WebBrowser %u",
                icon = "web-browser",
            )
            writeLauncher(
                File(panelDir, "launcher-25/droiddesk-settings.desktop"),
                name = "Settings",
                comment = "Display, network and input settings",
                exec = "xfce4-settings-manager",
                icon = "preferences-system",
            )
            // Desktop shortcut for the store, so it is reachable without the dock.
            writeLauncher(
                File(homeDir, "Desktop/droiddesk-store.desktop"),
                name = "Ubuntu Software",
                comment = "Install Linux applications",
                exec = "/usr/local/bin/droiddesk-store",
                icon = "system-software-install",
            )

            marker.writeText(
                "uiScale=$uiScale\npowerMode=$powerMode\niconTheme=$iconTheme\n",
            )
            Log.i(TAG, "Installed XFCE mobile profile ($PROFILE_VERSION scale=$uiScale mode=$powerMode)")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to install XFCE mobile profile", error)
            DesktopLog.append(context, TAG, "profile install failed: ${error.message}")
            false
        }
    }

    private fun writeLauncher(
        file: File,
        name: String,
        comment: String,
        exec: String,
        icon: String,
    ) {
        file.parentFile?.mkdirs()
        file.writeText(
            """
            [Desktop Entry]
            Version=1.0
            Type=Application
            Name=$name
            Comment=$comment
            Exec=$exec
            Icon=$icon
            StartupNotify=true
            Terminal=false
            Categories=System;PackageManager;
            """.trimIndent() + "\n",
        )
        // Desktop entries must be executable to be launched by xfdesktop/Thunar.
        file.setExecutable(true, false)
    }

    private fun installGtkSettings(homeDir: File, animations: Boolean) {
        val file = File(homeDir, ".config/gtk-3.0/settings.ini")
        file.parentFile?.mkdirs()
        val existing = if (file.exists()) file.readText() else ""
        val startMarker = "# DroidDesk managed start"
        val endMarker = "# DroidDesk managed end"
        val block = """
            $startMarker
            [Settings]
            gtk-enable-animations=$animations
            gtk-menu-images=false
            $endMarker
        """.trimIndent()
        val cleaned = existing.replace(
            Regex(
                Regex.escape(startMarker) + ".*?" + Regex.escape(endMarker),
                setOf(RegexOption.DOT_MATCHES_ALL),
            ),
            "",
        ).trimEnd()
        file.writeText(if (cleaned.isEmpty()) "$block\n" else "$cleaned\n$block\n")
    }

    private fun installPanelCss(homeDir: File, s: Double) {
        val cssFile = File(homeDir, ".config/gtk-3.0/gtk.css")
        cssFile.parentFile?.mkdirs()
        val startMarker = "/* DroidDesk mobile panel start */"
        val endMarker = "/* DroidDesk mobile panel end */"
        val pad = (4 * s).roundToInt()
        val side = (8 * s).roundToInt()
        val managedBlock = """
            $startMarker
            .xfce4-panel button {
              padding: ${pad}px ${side}px;
              border-radius: ${(12 * s).roundToInt()}px;
            }
            /* Touch-sized feedback for the dock; no transitions, the power saver
               profile must not spend frames on animation. */
            .xfce4-panel button:hover,
            .xfce4-panel button:active {
              background-color: rgba(255, 255, 255, 0.16);
            }
            .xfce4-panel .tasklist button {
              min-width: ${(34 * s).roundToInt()}px;
              /* The dock is vertical now, so touch target height matters too. */
              min-height: ${(34 * s).roundToInt()}px;
            }
            $endMarker
        """.trimIndent()
        val existing = if (cssFile.exists()) cssFile.readText() else ""
        val withoutOldBlock = existing.replace(
            Regex(
                Regex.escape(startMarker) + ".*?" + Regex.escape(endMarker),
                setOf(RegexOption.DOT_MATCHES_ALL),
            ),
            "",
        ).trimEnd()
        cssFile.writeText(
            if (withoutOldBlock.isEmpty()) "$managedBlock\n"
            else "$withoutOldBlock\n\n$managedBlock\n",
        )
    }

    /** Maximizes every normal window so apps never open wider than a portrait phone screen. */
    private fun installAutoMaximize(homeDir: File) {
        File(homeDir, ".config/devilspie2/droiddesk-maximize.lua").apply {
            parentFile?.mkdirs()
            writeText(
                """
                -- Managed by DroidDesk: dialogs, menus and splash screens keep their own size.
                if get_window_type() == "WINDOW_TYPE_NORMAL" then
                  if get_screen_geometry then
                    local screen_w, screen_h = get_screen_geometry()
                    local w = math.floor(screen_w * 0.9)
                    local h = math.floor(screen_h * 0.8)
                    set_window_geometry(math.floor((screen_w - w) / 2), math.floor((screen_h - h) / 2), w, h)
                  end
                  maximize()
                end
                """.trimIndent() + "\n",
            )
        }
        File(homeDir, ".config/autostart/droiddesk-automax.desktop").apply {
            parentFile?.mkdirs()
            writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=DroidDesk window fitting
                Comment=Maximize new windows on small screens
                Exec=sh -c 'command -v devilspie2 >/dev/null 2>&1 && exec devilspie2'
                OnlyShowIn=XFCE;
                NoDisplay=true
                """.trimIndent() + "\n",
            )
        }
    }

    /**
     * Software rendering only: no compositor in any power mode (double-buffered repaint is
     * the "compositor off" fix for Firefox video flicker), no box animations, no cycle popups.
     */
    private fun windowManagerConfig(s: Double): String {
        val titleSize = (11 * s).roundToInt().coerceAtLeast(9)
        return """
            <?xml version="1.1" encoding="UTF-8"?>

            <channel name="xfwm4" version="1.0">
              <property name="general" type="empty">
                <property name="theme" type="string" value="Default-hdpi"/>
                <property name="title_font" type="string" value="Sans Bold $titleSize"/>
                <property name="button_layout" type="string" value="O|HMC"/>
                <property name="placement_ratio" type="int" value="100"/>
                <property name="tile_on_move" type="bool" value="true"/>
                <property name="snap_to_border" type="bool" value="true"/>
                <property name="wrap_windows" type="bool" value="false"/>
                <property name="easy_click" type="string" value="Alt"/>
                <property name="show_dock_shadow" type="bool" value="false"/>
                <property name="use_compositing" type="bool" value="false"/>
                <property name="box_move" type="bool" value="false"/>
                <property name="box_resize" type="bool" value="false"/>
                <property name="cycle_preview" type="bool" value="false"/>
              </property>
            </channel>
        """.trimIndent() + "\n"
    }

    private fun xsettingsConfig(dpi: Int, s: Double, iconTheme: String): String {
        val cursor = (32 * s).roundToInt().coerceAtLeast(24)
        // Ubuntu 24.04 look: dark widgets, Yaru icons when the package is installed (the
        // installer preinstalls yaru-theme-icon). Papirus is the fallback already used by
        // older profiles; Adwaita is the last resort so icons always resolve.
        val themeName = if (iconTheme == "Yaru") "Yaru-dark" else "Adwaita-dark"
        return """
            <?xml version="1.1" encoding="UTF-8"?>

            <channel name="xsettings" version="1.0">
              <property name="Xft" type="empty">
                <property name="DPI" type="int" value="$dpi"/>
                <property name="Antialias" type="int" value="1"/>
                <property name="HintStyle" type="string" value="hintslight"/>
                <property name="RGBA" type="string" value="rgb"/>
              </property>
              <property name="Net" type="empty">
                <property name="ThemeName" type="string" value="$themeName"/>
                <property name="IconThemeName" type="string" value="$iconTheme"/>
                <property name="CursorSize" type="int" value="$cursor"/>
              </property>
              <property name="Gtk" type="empty">
                <property name="CursorThemeSize" type="int" value="$cursor"/>
                <property name="FontName" type="string" value="Sans ${(11 * s).roundToInt().coerceAtLeast(9)}"/>
                <property name="ButtonImages" type="bool" value="true"/>
                <property name="DialogsUseHeader" type="bool" value="true"/>
                <property name="MonospaceFontName" type="string" value="$MONOSPACE_FONT 10"/>
              </property>
            </channel>
        """.trimIndent() + "\n"
    }

    private fun terminalConfig(): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xfce4-terminal" version="1.0">
          <property name="font-use-system" type="bool" value="false"/>
          <property name="font-name" type="string" value="$MONOSPACE_FONT 10"/>
        </channel>
    """.trimIndent() + "\n"

    /**
     * The native runtime's fontconfig also scans Android's /system/fonts, so "monospace" can
     * resolve to a proportional Android font and terminals draw every letter in a wide cell.
     */
    private fun installMonospaceFontRule(homeDir: File) {
        File(homeDir, ".config/fontconfig/conf.d/60-droiddesk-monospace.conf").apply {
            parentFile?.mkdirs()
            writeText(
                """
                <?xml version="1.0"?>
                <!DOCTYPE fontconfig SYSTEM "fonts.dtd">
                <!-- Managed by DroidDesk -->
                <fontconfig>
                  <alias binding="strong">
                    <family>monospace</family>
                    <prefer><family>$MONOSPACE_FONT</family></prefer>
                  </alias>
                </fontconfig>
                """.trimIndent() + "\n",
            )
        }
    }

    private fun panelConfig(s: Double, powerMode: String): String {
        val topSize = (BASE_TOP_BAR_SIZE_PX * s).roundToInt()
        val topIcon = (30 * s).roundToInt()
        val dockSize = dockHeightPx(s * REFERENCE_SCALE)
        val dockIcon = (44 * s).roundToInt()
        // Power saver hides the dock until the pointer touches the left edge.
        val dockAutohide = if (powerMode == "saver") 2 else 1
        return """
            <?xml version="1.1" encoding="UTF-8"?>

            <channel name="xfce4-panel" version="1.0">
              <property name="configver" type="int" value="2"/>
              <property name="panels" type="array">
                <value type="int" value="1"/>
                <value type="int" value="2"/>
                <!-- Ubuntu 24.04 ships a dark top bar; the light one looked nothing like
                     it and its tasklist labels were unreadable on a phone. -->
                <property name="dark-mode" type="bool" value="true"/>
                <property name="panel-1" type="empty">
                  <property name="position" type="string" value="p=6;x=0;y=0"/>
                  <!-- length is a percentage while length-adjust is on: 1 meant the top
                       bar collapsed to ~11px and every plugin but the clock was clipped. -->
                  <property name="length" type="uint" value="100"/>
                  <property name="length-adjust" type="bool" value="true"/>
                  <property name="position-locked" type="bool" value="true"/>
                  <property name="autohide-behavior" type="uint" value="0"/>
                  <property name="size" type="uint" value="$topSize"/>
                  <property name="icon-size" type="uint" value="$topIcon"/>
                  <property name="background-style" type="uint" value="1"/>
                  <property name="background-rgba" type="array">
                    <value type="double" value="0.106000"/>
                    <value type="double" value="0.106000"/>
                    <value type="double" value="0.106000"/>
                    <value type="double" value="0.940000"/>
                  </property>
                  <property name="plugin-ids" type="array">
                    <value type="int" value="20"/>
                    <value type="int" value="2"/>
                    <value type="int" value="3"/>
                    <value type="int" value="4"/>
                    <value type="int" value="5"/>
                  </property>
                </property>
                <property name="panel-2" type="empty">
                  <!-- Ubuntu-style launcher: left edge, vertical, full height. p=2 is
                       xfce4-panel's left snap position; mode=1 makes the plugins stack
                       top-to-bottom instead of a horizontal strip. -->
                  <property name="position" type="string" value="p=2;x=0;y=0"/>
                  <property name="mode" type="uint" value="1"/>
                  <!-- For a vertical panel "size" is the dock WIDTH; "length" is the
                       height percentage, same unit as panel-1. -->
                  <property name="length" type="uint" value="100"/>
                  <property name="length-adjust" type="bool" value="true"/>
                  <property name="position-locked" type="bool" value="true"/>
                  <property name="autohide-behavior" type="uint" value="$dockAutohide"/>
                  <property name="size" type="uint" value="$dockSize"/>
                  <property name="icon-size" type="uint" value="$dockIcon"/>
                  <property name="background-style" type="uint" value="1"/>
                  <property name="background-rgba" type="array">
                    <value type="double" value="0.110000"/>
                    <value type="double" value="0.110000"/>
                    <value type="double" value="0.120000"/>
                    <value type="double" value="0.880000"/>
                  </property>
                  <property name="plugin-ids" type="array">
                    <value type="int" value="21"/>
                    <value type="int" value="22"/>
                    <value type="int" value="23"/>
                    <value type="int" value="24"/>
                    <value type="int" value="25"/>
                  </property>
                </property>
              </property>
              <property name="plugins" type="empty">
                <property name="plugin-2" type="string" value="tasklist">
                  <property name="show-labels" type="bool" value="false"/>
                  <property name="grouping" type="uint" value="1"/>
                  <property name="flat-buttons" type="bool" value="true"/>
                  <property name="show-handle" type="bool" value="false"/>
                </property>
                <property name="plugin-3" type="string" value="separator">
                  <property name="expand" type="bool" value="true"/>
                  <property name="style" type="uint" value="0"/>
                </property>
                <property name="plugin-4" type="string" value="systray">
                  <property name="square-icons" type="bool" value="true"/>
                </property>
                <property name="plugin-5" type="string" value="clock">
                  <property name="digital-layout" type="uint" value="2"/>
                  <property name="digital-time-format" type="string" value="%H:%M"/>
                  <property name="digital-date-format" type="string" value="%Y-%m-%d"/>
                </property>
                <property name="plugin-20" type="string" value="applicationsmenu">
                  <property name="show-button-title" type="bool" value="false"/>
                  <property name="button-icon" type="string" value="applications-other"/>
                </property>
                <property name="plugin-21" type="string" value="launcher">
                  <property name="items" type="array">
                    <value type="string" value="droiddesk-store.desktop"/>
                  </property>
                </property>
                <property name="plugin-22" type="string" value="launcher">
                  <property name="items" type="array">
                    <value type="string" value="droiddesk-terminal.desktop"/>
                  </property>
                </property>
                <property name="plugin-23" type="string" value="launcher">
                  <property name="items" type="array">
                    <value type="string" value="droiddesk-files.desktop"/>
                  </property>
                </property>
                <property name="plugin-24" type="string" value="launcher">
                  <property name="items" type="array">
                    <value type="string" value="droiddesk-browser.desktop"/>
                  </property>
                </property>
                <property name="plugin-25" type="string" value="launcher">
                  <property name="items" type="array">
                    <value type="string" value="droiddesk-settings.desktop"/>
                  </property>
                </property>
              </property>
            </channel>
        """.trimIndent() + "\n"
    }

    private fun desktopConfig(wallpaperPath: String, s: Double): String {
        // xfdesktop names its backdrop "monitorbuiltin" on some ROMs and "monitor0..N"
        // on others, and the name can change with the framebuffer size. Writing only
        // one of them left the desktop pure black after a resolution switch.
        val monitors = listOf("monitorbuiltin", "monitor0", "monitor1", "monitor2", "monitor3")
        val backdrops = monitors.joinToString("\n") { name ->
            """
            <property name="$name" type="empty">
              <property name="workspace0" type="empty">
                <property name="color-style" type="int" value="0"/>
                <property name="image-style" type="int" value="5"/>
                <property name="last-image" type="string" value="$wallpaperPath"/>
              </property>
            </property>
            """.trimIndent()
        }
        return """
            <?xml version="1.1" encoding="UTF-8"?>

            <channel name="xfce4-desktop" version="1.0">
              <property name="last-settings-migration-version" type="uint" value="1"/>
              <property name="backdrop" type="empty">
                <property name="screen0" type="empty">
            $backdrops
                </property>
              </property>
              <property name="desktop-icons" type="empty">
                <property name="style" type="int" value="1"/>
                <property name="icon-size" type="uint" value="${(72 * s).roundToInt()}"/>
                <property name="file-icons" type="empty">
                  <property name="show-home" type="bool" value="false"/>
                  <property name="show-filesystem" type="bool" value="false"/>
                  <property name="show-trash" type="bool" value="false"/>
                  <property name="show-removable" type="bool" value="false"/>
                </property>
              </property>
            </channel>
        """.trimIndent() + "\n"
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
