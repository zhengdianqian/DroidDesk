import 'package:shared_preferences/shared_preferences.dart';

import 'strings_en.dart';
import 'strings_zh.dart';

/// Hand-written localization table for DroidDesk (no build_runner / ARB).
///
/// Default language is Chinese. Call [init] once before `runApp`, then read
/// strings with `AppStrings.t('key')`.
class AppStrings {
  AppStrings._();

  static const String _prefsKey = 'appLanguage';
  static const String _defaultLang = 'zh';

  static String _lang = _defaultLang;

  /// Current language code: `'zh'` or `'en'`.
  static String get lang => _lang;

  static bool get isZh => _lang == 'zh';

  static const List<String> supportedLanguages = ['zh', 'en'];

  static String normalize(String? value) =>
      value == 'en' ? 'en' : _defaultLang;

  /// Reads the saved language from SharedPreferences (defaults to Chinese).
  static Future<void> init() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      _lang = normalize(prefs.getString(_prefsKey));
    } catch (_) {
      _lang = _defaultLang;
    }
  }

  /// Changes the language and persists it.
  static Future<void> setLang(String value) async {
    _lang = normalize(value);
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_prefsKey, _lang);
    } catch (_) {}
  }

  /// Looks up [key] for the current language, replacing `{name}` placeholders
  /// with [args]. Falls back to the English string, then to the key itself.
  static String t(String key, {Map<String, String>? args}) {
    final map = isZh ? zhStrings : enStrings;
    var value = map[key] ?? enStrings[key] ?? key;
    if (args != null && args.isNotEmpty) {
      for (final entry in args.entries) {
        value = value.replaceAll('{${entry.key}}', entry.value);
      }
    }
    return value;
  }

  // ── Native (Kotlin) status strings ──
  //
  // The native layer reports progress in English. Only these well-known status
  // lines are translated; raw apt/dpkg log output is always passed through.

  static const Map<String, String> _nativeStatusKeys = {
    'Mounting rootfs...': 'native.mountingRootfs',
    'Updating package index...': 'native.updatingPackageIndex',
    'Installing core tools...': 'native.installingCoreTools',
    'Extracting rootfs...': 'native.extractingRootfs',
    'Installing desktop environment (this may take a while)...':
        'native.installingDesktopEnvironment',
    'Selecting fastest APT mirror...': 'native.selectingMirror',
    'Installing Desktop Essentials tools...': 'native.installingEssentialsTools',
    'Cleaning up...': 'native.cleaningUp',
  };

  static final RegExp _downloadUbuntuPattern =
      RegExp(r'^Downloading Ubuntu:\s*(.*)$');
  static final RegExp _setupFailedPattern = RegExp(r'^Setup failed:\s*(.*)$');

  /// Translates a status string coming from the Kotlin layer (English mode
  /// returns it unchanged). Unrecognized text, such as raw apt output, is
  /// returned as-is.
  static String localizeNativeStatus(String raw) {
    if (!isZh || raw.isEmpty) return raw;
    final text = raw.trim();

    final download = _downloadUbuntuPattern.firstMatch(text);
    if (download != null) {
      return t(
        'native.downloadingUbuntu',
        args: {'progress': download.group(1)?.trim() ?? ''},
      );
    }

    final failed = _setupFailedPattern.firstMatch(text);
    if (failed != null) {
      return t('native.setupFailed', args: {'error': failed.group(1) ?? ''});
    }

    final key = _nativeStatusKeys[text];
    return key == null ? raw : t(key);
  }
}
