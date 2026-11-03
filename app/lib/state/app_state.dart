import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:droiddesk/l10n/app_strings.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/services/update_service.dart';
import 'package:droiddesk/theme/droid_theme.dart';

/// Central state management for the entire DroidDesk app.
class AppState extends ChangeNotifier {
  // ── Theme State ──
  ThemeMode _themeMode = ThemeMode.dark;

  // ── Language State ──
  String _language = AppStrings.lang;

  // ── Setup State ──
  bool _isBootstrapped = false;
  bool _isRunning = false;
  bool _hasRoot = false;
  String _installedDistro = '';
  String _installedDE = '';
  String _selectedDistro = 'ubuntu';
  static const String _selectedDE = 'xfce4';
  int _setupStep = 0; // 0=welcome, 1=distro, 2=de, 3=install, 4=done

  // ── Download/Install Progress ──
  double _downloadProgress = 0.0;
  String _downloadStatus = '';
  double _extractProgress = 0.0;
  String _extractStatus = '';
  bool _isDownloading = false;
  bool _isExtracting = false;
  bool _isInstallingDE = false;
  String? _statusMessage;
  String _setupLog = '';
  Map<String, bool> _optionalApps = {};
  String? _installingOptionalApp;
  double _optionalInstallProgress = 0.0;
  String _optionalInstallStatus = '';
  String _optionalInstallLog = '';
  bool _isProotTerminal = false;

  // Terminal history
  final List<String> _terminalOutput = [
    AppStrings.t('terminal.header'),
  ];
  List<String> get terminalOutput => _terminalOutput;

  /// A command is still running in the terminal; new lines are its stdin
  /// (answers such as apt's "[Y/n]"), not new commands.
  int _runningCommands = 0;
  bool get isCommandRunning => _runningCommands > 0;

  // ── Device Info ──
  Map<String, dynamic> _deviceInfo = {};

  // ── Error State ──
  String? _errorMessage;

  // ── Auto Start ──
  bool _autoStartDesktop = false;
  bool get autoStartDesktop => _autoStartDesktop;

  // ── Desktop display & reliability ──
  String _resolutionMode = 'device';
  double _frameScale = 0.75;
  double _uiScale = 1.5;
  String _powerMode = 'standard';
  bool _autoIme = true;
  Map<String, dynamic> _displayInfo = {};
  String _desktopLog = '';
  bool _desktopLogEnabled = false;
  Map<String, dynamic> _phantomStatus = {};

  String get resolutionMode => _resolutionMode;
  double get frameScale => _frameScale;
  double get uiScale => _uiScale;
  String get powerMode => _powerMode;
  bool get autoIme => _autoIme;
  Map<String, dynamic> get displayInfo => _displayInfo;
  String get desktopLog => _desktopLog;
  bool get desktopLogEnabled => _desktopLogEnabled;
  Map<String, dynamic> get phantomStatus => _phantomStatus;

  // ── Getters ──
  ThemeMode get themeMode => _themeMode;
  String get language => _language;
  bool get isDarkMode => DroidTheme.isDark;
  bool get isBootstrapped => _isBootstrapped;
  bool get isRunning => _isRunning;
  bool get hasRoot => _hasRoot;
  String get installedDistro => _installedDistro;
  String get installedDE => _installedDE;
  String get selectedDistro => _selectedDistro;
  String get selectedDE => _selectedDE;
  int get setupStep => _setupStep;
  double get downloadProgress => _downloadProgress;
  String get downloadStatus => _downloadStatus;
  double get extractProgress => _extractProgress;
  String get extractStatus => _extractStatus;
  String? get statusMessage => _statusMessage;
  String get setupLog => _setupLog;
  bool get isDownloading => _isDownloading;
  bool get isExtracting => _isExtracting;
  bool get isInstallingDE => _isInstallingDE;
  Map<String, dynamic> get deviceInfo => _deviceInfo;
  String? get errorMessage => _errorMessage;
  Map<String, bool> get optionalApps => _optionalApps;
  String? get installingOptionalApp => _installingOptionalApp;
  double get optionalInstallProgress => _optionalInstallProgress;
  String get optionalInstallStatus => _optionalInstallStatus;
  String get optionalInstallLog => _optionalInstallLog;
  bool get isProotTerminal => _isProotTerminal;

  bool get isSetupComplete => _isBootstrapped && _installedDE.isNotEmpty;
  bool get isDEInstalled => _installedDE.isNotEmpty;

  String get gpuType {
    final vendor = _deviceInfo['gpuVendor']?.toString() ?? '';
    if (vendor.contains('adreno')) return AppStrings.t('state.gpu.adreno');
    if (vendor.contains('mali')) return AppStrings.t('state.gpu.mali');
    if (vendor.contains('powervr')) return AppStrings.t('state.gpu.powervr');
    return AppStrings.t('state.gpu.unknown');
  }

  // ── Initialization ──

  /// Re-reads the runtime status whenever the app returns to the foreground, since the
  /// desktop can be stopped from outside Flutter (e.g. the notification's Stop action).
  AppLifecycleListener? _lifecycleListener;

  Future<void> initialize() async {
    DroidDeskPlatform.onPackageOperationProgress = _onPackageProgress;
    DroidDeskPlatform.onPackageOperationLog = _onPackageLog;
    _lifecycleListener ??= AppLifecycleListener(
      onResume: () {
        refreshStatus();
        checkForUpdate();
      },
    );
    await _loadThemeMode();

    // Set up progress callbacks
    DroidDeskPlatform.onDownloadProgress = (progress, status) {
      final localizedStatus = AppStrings.localizeNativeStatus(status);
      _downloadProgress = progress;
      _downloadStatus = localizedStatus;
      if (progress < 0) {
        _isDownloading = false;
        _errorMessage = localizedStatus;
      } else if (progress >= 1.0) {
        _isDownloading = false;
      }
      notifyListeners();
    };

    DroidDeskPlatform.onExtractProgress = (progress, status) {
      final localizedStatus = AppStrings.localizeNativeStatus(status);
      _extractProgress = progress;
      _extractStatus = localizedStatus;
      if (progress < 0) {
        _isExtracting = false;
        _errorMessage = localizedStatus;
      } else if (progress >= 1.0) {
        _isExtracting = false;
        refreshStatus();
      }
      notifyListeners();
    };

    DroidDeskPlatform.onInstallProgress = (progress, status) {
      final localizedStatus = AppStrings.localizeNativeStatus(status);
      _extractProgress = progress; // reusing extract progress state for UI
      _extractStatus = localizedStatus;
      _statusMessage = localizedStatus;
      _isInstallingDE = progress >= 0 && progress < 1.0;
      if (progress < 0) {
        _isExtracting = false;
        _isInstallingDE = false;
        _errorMessage = localizedStatus;
      } else if (progress >= 1.0) {
        _isExtracting = false;
        _isInstallingDE = false;
        refreshStatus();
      }
      notifyListeners();
    };

    DroidDeskPlatform.onTerminalOutput = (text) {
      if (_terminalOutput.isEmpty) _terminalOutput.add('');

      final cleanedText = text.replaceAll(RegExp(r'.*\r(?!\n)'), '');
      if (_setupStep == 3) {
        _setupLog += cleanedText;
        if (_setupLog.length > 20000) {
          _setupLog = _setupLog.substring(_setupLog.length - 20000);
        }
      }
      if (_installingOptionalApp != null) {
        _optionalInstallLog += cleanedText;
        if (_optionalInstallLog.length > 20000) {
          _optionalInstallLog = _optionalInstallLog.substring(
            _optionalInstallLog.length - 20000,
          );
        }
      }
      final lines = cleanedText.split('\n');

      for (int i = 0; i < lines.length; i++) {
        if (i == 0) {
          _terminalOutput[_terminalOutput.length - 1] += lines[i];
        } else {
          _terminalOutput.add(lines[i]);
        }
      }
      notifyListeners();
    };

    DroidDeskPlatform.onOptionalInstallProgress = (progress, status) {
      _optionalInstallProgress = progress.clamp(0.0, 1.0);
      _optionalInstallStatus = AppStrings.localizeNativeStatus(status);
      notifyListeners();
    };

    await refreshStatus();
    await loadDeviceInfo();
    DroidDeskPlatform.onUpdateProgress = _onUpdateProgress;
    checkForUpdate();

    final prefs = await SharedPreferences.getInstance();
    _autoStartDesktop = prefs.getBool('autoStartDesktop') ?? false;
    _resolutionMode = prefs.getString('ddResolutionMode') ?? 'device';
    _frameScale = double.tryParse(prefs.getString('ddFrameScale') ?? '') ?? 0.75;
    _uiScale = double.tryParse(prefs.getString('ddUiScale') ?? '') ?? 1.5;
    _powerMode = prefs.getString('ddPowerMode') ?? 'standard';
    _autoIme = prefs.getBool('ddAutoIme') ?? true;
    // Opt-in: the native journal stays completely inert (no file, no thread) unless
    // the user turns this switch on.
    _desktopLogEnabled = prefs.getBool('ddDesktopLog') ?? false;
    await refreshDisplayInfo();

    if (_autoStartDesktop && isSetupComplete && !_isRunning) {
      // Auto-start desktop when the app (launcher) boots up
      startLinux(mode: 'x11');
    }
  }

  @override
  void dispose() {
    _lifecycleListener?.dispose();
    super.dispose();
  }

  // ── Theme Control ──

  Future<void> _loadThemeMode() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final savedMode = prefs.getString('theme_mode');
      if (savedMode == 'light') {
        _themeMode = ThemeMode.light;
      } else if (savedMode == 'system') {
        _themeMode = ThemeMode.system;
      } else {
        _themeMode = ThemeMode.dark;
      }
      DroidTheme.currentThemeMode = _themeMode;
      notifyListeners();
      await DroidDeskPlatform.updateStatusBarTheme(!isDarkMode);
    } catch (_) {
      // Default to dark theme if preferences fail
    }
  }

  Future<void> setThemeMode(ThemeMode mode) async {
    _themeMode = mode;
    DroidTheme.currentThemeMode = mode;
    notifyListeners();
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('theme_mode', mode.name);
      await DroidDeskPlatform.updateStatusBarTheme(!isDarkMode);
    } catch (_) {}
  }

  Future<void> toggleThemeMode() async {
    if (_themeMode == ThemeMode.dark) {
      await setThemeMode(ThemeMode.light);
    } else {
      await setThemeMode(ThemeMode.dark);
    }
  }

  /// Switches the UI language (`'zh'` or `'en'`) and persists it. Watchers of
  /// [AppState] rebuild immediately, so no restart is needed.
  Future<void> setLanguage(String lang) async {
    final next = AppStrings.normalize(lang);
    if (_language == next) return;
    _language = next;
    await AppStrings.setLang(next);
    notifyListeners();
  }

  Future<void> refreshStatus() async {
    try {
      final status = await DroidDeskPlatform.getRuntimeStatus();
      _isBootstrapped = status['isBootstrapped'] == true;
      _isRunning = status['isRunning'] == true;
      _hasRoot = status['hasRoot'] == true;
      _installedDistro = status['distro']?.toString() ?? '';
      _installedDE = status['installedDE']?.toString() ?? '';

      if (_installedDE.isNotEmpty) {
        await refreshOptionalApps();
      }

      notifyListeners();
    } catch (e) {
      _errorMessage = AppStrings.t('state.error.runtimeStatus', args: {'error': '$e'});
      notifyListeners();
    }
  }

  Future<void> loadDeviceInfo() async {
    try {
      _deviceInfo = await DroidDeskPlatform.getDeviceInfo();
      notifyListeners();
    } catch (e) {
      // Non-fatal — continue without device info
    }
  }

  // ── Setup Flow ──

  void setSelectedDistro(String distro) {
    _selectedDistro = distro;
    notifyListeners();
  }

  void setSetupStep(int step) {
    _setupStep = step;
    _errorMessage = null;
    notifyListeners();
  }

  Future<void> setAutoStartDesktop(bool value) async {
    _autoStartDesktop = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('autoStartDesktop', value);
  }

  // ── Display profile, phantom guard, desktop journal ──

  /// Reads the framebuffer size the X server will actually allocate.
  Future<void> refreshDisplayInfo() async {
    try {
      _displayInfo = await DroidDeskPlatform.getDisplayInfo();
      notifyListeners();
    } catch (_) {
      // Frames rendered before the channel is ready can fail silently.
    }
  }

  /// Persists one display preference; the next desktop start applies it.
  Future<void> setDisplayProfile({
    String? resolutionMode,
    double? frameScale,
    double? uiScale,
    String? powerMode,
    bool? autoIme,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    if (resolutionMode != null) {
      _resolutionMode = resolutionMode;
      await prefs.setString('ddResolutionMode', resolutionMode);
    }
    if (frameScale != null) {
      _frameScale = frameScale;
      await prefs.setString('ddFrameScale', frameScale.toStringAsFixed(2));
    }
    if (uiScale != null) {
      _uiScale = uiScale;
      await prefs.setString('ddUiScale', uiScale.toStringAsFixed(2));
    }
    if (powerMode != null) {
      _powerMode = powerMode;
      await prefs.setString('ddPowerMode', powerMode);
    }
    if (autoIme != null) {
      _autoIme = autoIme;
      await prefs.setBool('ddAutoIme', autoIme);
    }
    notifyListeners();
    await refreshDisplayInfo();
  }

  /// Applies zh_CN locale, CJK fonts and IBus to an already-installed image.
  Future<bool> applyChineseEnvironment() async {
    final ok = await DroidDeskPlatform.applyChineseEnvironment();
    await refreshDisplayInfo();
    return ok;
  }

  /// Runs the apt/dpkg health report; the output is streamed into the package log card.
  Future<void> diagnosePackageManager() async {
    await DroidDeskPlatform.diagnosePackageManager();
  }

  /// A resolution/scale change only applies to a freshly built X server, so the
  /// guest and the framebuffer are torn down before starting a new session.
  Future<void> restartDesktopForDisplay() async {
    if (!_isRunning) {
      await refreshDisplayInfo();
      return;
    }
    await DroidDeskPlatform.restartDesktopForDisplay();
    _isRunning = false;
    notifyListeners();
    await refreshDisplayInfo();
    await Future.delayed(const Duration(milliseconds: 1200));
    await startLinux(mode: 'x11');
    await refreshStatus();
  }

  Future<Map<String, dynamic>> refreshPhantomStatus() async {
    try {
      _phantomStatus = await DroidDeskPlatform.getPhantomStatus();
      notifyListeners();
    } catch (_) {}
    return _phantomStatus;
  }

  Future<Map<String, dynamic>> relaxPhantomLimit() async {
    final result = await DroidDeskPlatform.relaxPhantomLimit();
    await refreshPhantomStatus();
    return result;
  }

  /// Persists the opt-in log switch and mirrors it into the native journal immediately.
  Future<void> setDesktopLogEnabled(bool enabled) async {
    _desktopLogEnabled = enabled;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('ddDesktopLog', enabled);
    await DroidDeskPlatform.setDesktopLogEnabled(enabled);
    if (!enabled) {
      // Keep no stale file around for a user who turned logging back off.
      _desktopLog = '';
      notifyListeners();
    }
  }

  /// Preinstall queue snapshot (background Chromium/QQ/WeChat/Trae/store install).
  /// Read on demand from the settings sheet — no periodic polling anywhere.
  Map<String, dynamic> get preinstallSummary => _preinstallSummary;
  List<Map<String, dynamic>> get preinstallSteps => _preinstallSteps;
  Map<String, dynamic> _preinstallSummary = {};
  List<Map<String, dynamic>> _preinstallSteps = [];

  Future<void> refreshPreinstallStatus() async {
    try {
      final result = await DroidDeskPlatform.getPreinstallStatus();
      final summary = result['summary'];
      final steps = result['steps'];
      _preinstallSummary = summary is Map
          ? Map<String, dynamic>.from(summary)
          : <String, dynamic>{};
      _preinstallSteps = steps is List
          ? steps
                .whereType<Map>()
                .map((e) => Map<String, dynamic>.from(e))
                .toList()
          : <Map<String, dynamic>>[];
      notifyListeners();
    } catch (_) {
      // A missing native side must not break the settings screen.
    }
  }

  Future<void> retryPreinstall() async {
    await DroidDeskPlatform.retryPreinstall();
    await refreshPreinstallStatus();
  }

  Future<String> loadDesktopLog() async {
    _desktopLog = await DroidDeskPlatform.getDesktopLog();
    notifyListeners();
    return _desktopLog;
  }

  Future<void> clearDesktopLog() async {
    await DroidDeskPlatform.clearDesktopLog();
    _desktopLog = '';
    notifyListeners();
  }

  Future<bool> shareDesktopLog() async {
    return DroidDeskPlatform.shareDesktopLog();
  }

  /// `dpkg --configure -a` + `--fix-broken` + a fresh index, shown in the normal
  /// package-progress card.
  Future<bool> repairPackageManager() async {
    if (_activePackage != null) return false;
    _startPackageOperation(
      '__repair__',
      AppStrings.t('settings.apt.repairable'),
      const [],
      removing: false,
    );
    _packageStatus = AppStrings.t('settings.apt.repairing');
    var ok = false;
    try {
      ok = await DroidDeskPlatform.repairPackageManager();
    } catch (_) {}
    _finishPackageOperation(ok);
    return ok;
  }

  /// Returns a structured result code from the platform layer
  /// (`Ok` / `NoNetwork` / `AptFailed` / `Unsupported`).
  Future<String> applyBestMirror() async {
    try {
      return await DroidDeskPlatform.applyBestMirror();
    } catch (_) {
      return 'Unsupported';
    }
  }

  Future<Map<String, dynamic>> installDebPackage() async {
    final result = await DroidDeskPlatform.pickDebPackage();
    if (result['ok'] == true) await refreshOptionalApps();
    return result;
  }

  Future<bool> detectRootForSetup() async {
    _hasRoot = await DroidDeskPlatform.checkRoot();
    notifyListeners();
    return _hasRoot;
  }

  /// Main setup entry point. [useRoot] records confirmation from the
  /// rooted-device dialog.
  Future<void> runSetup({bool? useRoot}) async {
    try {
      _errorMessage = null;
      _setupLog = '';
      _setupStep = 3;
      notifyListeners();

      // Detect root and choose path
      final rootAvailable = await DroidDeskPlatform.checkRoot();
      _hasRoot = rootAvailable && (useRoot ?? true);
      notifyListeners();

      if (_hasRoot) {
        await _runChrootSetup();
      } else {
        await _runNativeSetup();
      }

      _setupStep = 4;
      notifyListeners();
    } catch (e) {
      _errorMessage = AppStrings.t('state.error.setupFailed', args: {'error': '$e'});
      _isDownloading = false;
      _isExtracting = false;
      _isInstallingDE = false;
      notifyListeners();
    }
  }

  Future<void> _runNativeSetup() async {
    _isExtracting = true;
    _extractProgress = 0.0;
    _extractStatus = AppStrings.t('state.status.extractingBootstrap');
    _statusMessage = _extractStatus;
    notifyListeners();
    await DroidDeskPlatform.setupBootstrap();

    _extractProgress = 0.08;
    _extractStatus = AppStrings.t('state.status.bootstrapReady');
    _statusMessage = _extractStatus;
    _isInstallingDE = true;
    notifyListeners();
    final installed = await DroidDeskPlatform.installDesktopNative(
      de: _selectedDE,
    );
    if (!installed) {
      throw StateError(AppStrings.t('state.error.nativeInstallFailed'));
    }
    _isExtracting = false;
    _isInstallingDE = false;

    await refreshStatus();
  }

  Future<void> _runChrootSetup() async {
    _statusMessage = AppStrings.t('state.status.downloadingRootfs');
    _isDownloading = true;
    _downloadProgress = 0.0;
    notifyListeners();
    if (!await DroidDeskPlatform.downloadRootfs(_selectedDistro)) {
      throw StateError(AppStrings.t('state.error.downloadFailed'));
    }

    _isDownloading = false;
    _isExtracting = true;
    _extractProgress = 0.0;
    _statusMessage = AppStrings.t('state.status.extractingRootfs');
    notifyListeners();
    if (!await DroidDeskPlatform.extractRootfs()) {
      throw StateError(AppStrings.t('state.error.extractFailed'));
    }

    _statusMessage = AppStrings.t('state.status.installingDesktop');
    _isInstallingDE = true;
    notifyListeners();
    if (!await DroidDeskPlatform.installDesktopEnvironment(_selectedDE)) {
      throw StateError(AppStrings.t('state.error.desktopEssentialsFailed'));
    }
    _isExtracting = false;
    _isInstallingDE = false;

    await refreshStatus();
  }

  Future<void> runExtraction() async {
    // Handled inside runSetup for chroot mode.
    // Kept for API compatibility.
    _extractProgress = 1.0;
    _extractStatus = AppStrings.t('state.status.extractionHandled');
    notifyListeners();
  }

  Future<void> installDesktopEnvironment() async {
    try {
      _isExtracting = true;
      _extractProgress = 0.0;
      _statusMessage = AppStrings.t('state.status.installingDe');
      _errorMessage = null;
      notifyListeners();

      if (_hasRoot) {
        if (!await DroidDeskPlatform.installDesktopEnvironment(_selectedDE)) {
          throw StateError(AppStrings.t('state.error.desktopEssentialsFailed'));
        }
      } else {
        final installed = await DroidDeskPlatform.installDesktopNative(
          de: _selectedDE,
        );
        if (!installed) {
          throw StateError(AppStrings.t('state.error.nativeInstallFailed'));
        }
      }

      _isExtracting = false;
      _isInstallingDE = false;
      // Fire-and-forget: the optional apps (Chromium/QQ/WeChat/Trae/store) install in a
      // background serial queue and must never delay the desktop-ready state.
      DroidDeskPlatform.startPreinstall().then((_) {
        refreshPreinstallStatus();
      }).catchError((_) {});
      await refreshStatus();
    } catch (e) {
      _errorMessage = AppStrings.t('state.error.installFailed', args: {'error': '$e'});
      _isExtracting = false;
      _isInstallingDE = false;
      notifyListeners();
    }
  }

  // ── Optional applications ──

  Future<void> refreshOptionalApps() async {
    try {
      _optionalApps = await DroidDeskPlatform.getOptionalApps();
      notifyListeners();
    } catch (_) {
      // The desktop remains usable even if package status cannot be queried.
    }
  }

  Future<bool> installOptionalApp(String appId) async {
    if (_installingOptionalApp != null) return false;
    _installingOptionalApp = appId;
    _optionalInstallProgress = 0.0;
    _optionalInstallStatus = AppStrings.t('package.preparingInstall');
    _optionalInstallLog = '';
    notifyListeners();

    try {
      final installed = await DroidDeskPlatform.installOptionalApp(appId);
      await refreshOptionalApps();
      return installed;
    } finally {
      _installingOptionalApp = null;
      notifyListeners();
    }
  }

  // ── In-app updates ──

  String _appVersion = '';
  AppRelease? _availableUpdate;
  bool _checkingUpdate = false;
  bool _downloadingUpdate = false;
  int _updateDownloaded = 0;
  int _updateTotal = 0;
  String? _updateError;
  String? _updateErrorCode;
  String? _downloadedUpdatePath;
  bool _updatePromptShown = false;

  String get appVersion => _appVersion;
  AppRelease? get availableUpdate => _availableUpdate;
  bool get isCheckingUpdate => _checkingUpdate;
  bool get isDownloadingUpdate => _downloadingUpdate;
  int get updateDownloaded => _updateDownloaded;
  int get updateTotal => _updateTotal;
  double get updateProgress =>
      _updateTotal > 0 ? (_updateDownloaded / _updateTotal).clamp(0.0, 1.0) : 0;
  String? get updateError => _updateError;
  String? get updateErrorCode => _updateErrorCode;
  String? get downloadedUpdatePath => _downloadedUpdatePath;

  /// True once per app session when an automatic check found an update to offer.
  bool takeUpdatePrompt() {
    if (_availableUpdate == null || _updatePromptShown) return false;
    _updatePromptShown = true;
    return true;
  }

  void _onUpdateProgress(int downloaded, int total) {
    _updateDownloaded = downloaded;
    _updateTotal = total;
    notifyListeners();
  }

  Future<String> _installedVersion() async {
    if (_appVersion.isEmpty) {
      try {
        final info = await DroidDeskPlatform.getAppVersion();
        final name = info['versionName']?.toString() ?? '';
        // Include the build number: two releases can share a versionName, and
        // comparing names alone made patch builds invisible to the updater.
        final code = info['versionCode'];
        _appVersion = (code == null || code.toString().isEmpty) ? name : '$name+$code';
      } catch (_) {}
    }
    return _appVersion;
  }

  /// Looks for a newer GitHub release. Automatic checks are throttled and silent;
  /// [manual] checks always run and surface errors in [updateError].
  Future<void> checkForUpdate({bool manual = false}) async {
    if (_checkingUpdate || _downloadingUpdate) return;
    _checkingUpdate = true;
    if (manual) {
      _updateError = null;
      _updateErrorCode = null;
    }
    notifyListeners();
    try {
      final installed = await _installedVersion();
      if (installed.isEmpty) return;
      final release = await UpdateService.check(installedVersion: installed, manual: manual);
      if (release != null || manual) {
        if (_availableUpdate?.tag != release?.tag) _downloadedUpdatePath = null;
        _availableUpdate = release;
      }
      if (release == null && manual) DroidDeskPlatform.cleanupUpdates();
    } catch (error) {
      if (manual) {
        _updateError = AppStrings.t('state.error.githubUnreachable');
        _updateErrorCode = 'network';
      }
    } finally {
      _checkingUpdate = false;
      notifyListeners();
    }
  }

  Future<void> skipUpdate() async {
    final release = _availableUpdate;
    if (release == null) return;
    await UpdateService.skip(release.tag);
    _availableUpdate = null;
    notifyListeners();
  }

  /// Downloads (if needed) and opens the installer. Returns false on error or cancel.
  Future<bool> downloadAndInstallUpdate() async {
    final release = _availableUpdate;
    if (release == null || _downloadingUpdate) return false;
    _updateError = null;
    _updateErrorCode = null;
    var path = _downloadedUpdatePath;
    if (path == null) {
      _downloadingUpdate = true;
      _updateDownloaded = 0;
      _updateTotal = release.apkSize;
      notifyListeners();
      final result = await DroidDeskPlatform.downloadUpdate(
        url: release.apkUrl,
        size: release.apkSize,
        tag: release.tag,
      );
      _downloadingUpdate = false;
      if (result['ok'] != true) {
        _updateErrorCode = result['code']?.toString();
        _updateError = switch (_updateErrorCode) {
          'cancelled' => null,
          'signature_mismatch' => AppStrings.t('update.error.signatureMismatch'),
          'size_mismatch' || 'network' =>
            AppStrings.t('update.error.downloadIncomplete'),
          _ =>
            result['message']?.toString() ??
                AppStrings.t('update.error.downloadFailed'),
        };
        notifyListeners();
        return false;
      }
      path = result['path'].toString();
      _downloadedUpdatePath = path;
    }
    notifyListeners();
    final opened = await DroidDeskPlatform.installUpdate(path);
    if (!opened) {
      _updateError = AppStrings.t('update.error.installer');
      _updateErrorCode = 'installer';
      notifyListeners();
    }
    return opened;
  }

  Future<void> cancelUpdateDownload() async {
    if (!_downloadingUpdate) return;
    await DroidDeskPlatform.cancelUpdateDownload();
  }

  // ── Package operations ──
  //
  // Installs and removals from the App Store live here rather than in the screen, so they keep
  // running (and stay visible on Home) when the user navigates away.

  String? _activePackage;
  String _activePackageName = '';
  List<String> _activePackageIconNames = const [];
  bool _packageRemoving = false;
  bool _packageCancelling = false;
  double _packageProgress = 0;
  String _packageStatus = '';
  String _packageLog = '';
  PackageOperationResult? _lastPackageResult;
  int _packageResultId = 0;

  String? get activePackage => _activePackage;
  String get activePackageName => _activePackageName;
  List<String> get activePackageIconNames => _activePackageIconNames;
  bool get packageRemoving => _packageRemoving;
  bool get packageCancelling => _packageCancelling;
  double get packageProgress => _packageProgress;
  String get packageStatus => _packageStatus;
  String get packageLog => _packageLog;

  /// Result of the most recent finished operation; [packageResultId] changes with each one.
  PackageOperationResult? get lastPackageResult => _lastPackageResult;
  int get packageResultId => _packageResultId;

  void _onPackageProgress(double progress, String status) {
    if (_activePackage == null) return;
    _packageProgress = progress.clamp(0.0, 1.0);
    _packageStatus = AppStrings.localizeNativeStatus(status);
    notifyListeners();
  }

  void _onPackageLog(String text) {
    if (_activePackage == null) return;
    _packageLog += text;
    if (_packageLog.length > 12000) {
      _packageLog = _packageLog.substring(_packageLog.length - 12000);
    }
    notifyListeners();
  }

  void _startPackageOperation(
    String package,
    String displayName,
    List<String> iconNames, {
    required bool removing,
  }) {
    _activePackage = package;
    _activePackageName = displayName;
    _activePackageIconNames = iconNames;
    _packageRemoving = removing;
    _packageCancelling = false;
    _packageProgress = 0;
    _packageStatus = removing
        ? AppStrings.t('package.preparingRemoval')
        : AppStrings.t('package.preparing', args: {'name': displayName});
    _packageLog = '';
    notifyListeners();
  }

  void _finishPackageOperation(bool ok) {
    final result = PackageOperationResult(
      package: _activePackage ?? '',
      displayName: _activePackageName,
      iconNames: _activePackageIconNames,
      removed: _packageRemoving,
      cancelled: _packageCancelling,
      ok: ok,
    );
    _activePackage = null;
    _packageCancelling = false;
    _lastPackageResult = result;
    _packageResultId++;
    notifyListeners();
  }

  /// Installs a native package; returns false if another operation is running or it failed.
  Future<bool> installPackage(
    String package, {
    String? displayName,
    List<String> iconNames = const [],
  }) async {
    if (_activePackage != null) return false;
    _startPackageOperation(package, displayName ?? package, iconNames, removing: false);
    var ok = false;
    try {
      ok = await DroidDeskPlatform.installNativePackage(package);
    } catch (_) {}
    _finishPackageOperation(ok);
    return ok;
  }

  Future<bool> removePackage(
    String package, {
    String? displayName,
    List<String> iconNames = const [],
  }) async {
    if (_activePackage != null) return false;
    _startPackageOperation(package, displayName ?? package, iconNames, removing: true);
    var ok = false;
    try {
      ok = await DroidDeskPlatform.removeNativePackage(package);
    } catch (_) {}
    _finishPackageOperation(ok);
    return ok;
  }

  /// Package id used for the "update everything" operation.
  static const updatesOperation = '__updates__';

  bool get isUpdatingAll => _activePackage == updatesOperation;

  /// Upgrades all non-core packages through the same progress UI as installs.
  Future<bool> updateAllPackages() async {
    if (_activePackage != null) return false;
    _startPackageOperation(
      updatesOperation,
      AppStrings.t('package.allPackages'),
      const [],
      removing: false,
    );
    _packageStatus = AppStrings.t('package.checkingUpdates');
    var ok = false;
    try {
      ok = await DroidDeskPlatform.upgradeAllPackages();
    } catch (_) {}
    _finishPackageOperation(ok);
    return ok;
  }

  Future<void> cancelPackageOperation() async {
    if (_activePackage == null || _packageCancelling || _packageRemoving) return;
    _packageCancelling = true;
    _packageStatus = AppStrings.t('package.cancellingInstall');
    notifyListeners();
    await DroidDeskPlatform.cancelNativePackageOperation();
  }

  /// Hides the "finished" card on Home.
  void dismissPackageResult() {
    if (_lastPackageResult == null) return;
    _lastPackageResult = null;
    notifyListeners();
  }

  // ── Session Control ──

  Future<void> startLinux({
    String mode = 'x11',
    int width = 1920,
    int height = 1080,
  }) async {
    try {
      _errorMessage = null;
      final started = await DroidDeskPlatform.startLinux(
        de: _selectedDE,
        mode: mode,
        width: width,
        height: height,
      );
      if (!started) {
        throw StateError(AppStrings.t('state.error.runtimeNotReady'));
      }
      _isRunning = true;
      notifyListeners();
    } catch (e) {
      _errorMessage = AppStrings.t('state.error.startFailed', args: {'error': '$e'});
      notifyListeners();
    }
  }

  Future<void> launchDesktopActivity() async {
    try {
      await DroidDeskPlatform.launchDesktopActivity();
    } catch (e) {
      _errorMessage = AppStrings.t('state.error.launchDesktop', args: {'error': '$e'});
      notifyListeners();
    }
  }

  /// True while a native teardown request is in flight; prevents double taps from
  /// queueing two teardowns while still giving feedback to the second tap.
  bool get isStopping => _isStopping;
  bool _isStopping = false;

  /// Runs the native teardown and reports what actually happened.
  /// [stopLinux] never flips `isRunning` optimistically: the native result decides, so a
  /// failed stop keeps showing "运行中" plus an explicit error instead of lying.
  Future<String?> stopLinux() async {
    if (_isStopping) {
      return AppStrings.t('state.stopping');
    }
    _isStopping = true;
    notifyListeners();
    try {
      final result = await DroidDeskPlatform.stopLinux();
      final ok = result['ok'] == true;
      _isRunning = result['running'] == true;
      if (ok) {
        return null;
      }
      final message = (result['message'] as String?)?.trim();
      _errorMessage = message == null || message.isEmpty
          ? AppStrings.t('state.error.stopFailed', args: {'error': 'unknown'})
          : message;
      return _errorMessage;
    } catch (e) {
      _errorMessage = AppStrings.t('state.error.stopFailed', args: {'error': '$e'});
      return _errorMessage;
    } finally {
      _isStopping = false;
      notifyListeners();
      await refreshStatus();
    }
  }

  Future<String> executeCommand(String command) async {
    final isInput = isCommandRunning;
    if (!isInput) _runningCommands++;
    try {
      _terminalOutput.add(isInput ? '$command\n' : '\$ $command\n');
      notifyListeners();
      return await DroidDeskPlatform.executeCommand(command);
    } catch (e) {
      return AppStrings.t('state.error.command', args: {'error': '$e'});
    } finally {
      if (!isInput) {
        _runningCommands--;
        notifyListeners();
      }
    }
  }

  void useNativeTerminal() {
    _isProotTerminal = false;
    notifyListeners();
  }

  Future<void> startDebianShell() async {
    _isProotTerminal = true;
    clearTerminal();
    await executeCommand('start-debian');
  }

  void appendTerminalOutput(String output) {
    if (_terminalOutput.isEmpty) _terminalOutput.add('');
    _terminalOutput[_terminalOutput.length - 1] += output;
    notifyListeners();
  }

  void clearTerminal() {
    _terminalOutput.clear();
    _terminalOutput.add(AppStrings.t('terminal.header'));
    notifyListeners();
  }

  Future<void> interruptCommand() async {
    try {
      await DroidDeskPlatform.interruptCommand();
    } catch (e) {
      debugPrint("Error interrupting command: $e");
    }
  }

  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }
}

/// Outcome of a finished App Store install or removal.
class PackageOperationResult {
  final String package;
  final String displayName;
  final List<String> iconNames;
  final bool removed;
  final bool cancelled;
  final bool ok;

  const PackageOperationResult({
    required this.package,
    required this.displayName,
    required this.iconNames,
    required this.removed,
    required this.cancelled,
    required this.ok,
  });

  bool get isUpdateAll => package == AppState.updatesOperation;

  String get message => isUpdateAll
      ? (cancelled
          ? AppStrings.t('package.result.updateCancelled')
          : ok
              ? AppStrings.t('package.result.packagesUpdated')
              : AppStrings.t('package.result.someUpdatesFailed'))
      : cancelled
      ? AppStrings.t('package.result.installCancelled', args: {'name': displayName})
      : removed
          ? (ok
              ? AppStrings.t('package.result.removed', args: {'name': displayName})
              : AppStrings.t('package.result.removeFailed', args: {'name': displayName}))
          : (ok
              ? AppStrings.t('package.result.installed', args: {'name': displayName})
              : AppStrings.t('package.result.installFailed', args: {'name': displayName}));
}
