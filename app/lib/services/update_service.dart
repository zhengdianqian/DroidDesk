import 'dart:convert';
import 'dart:io';

import 'package:shared_preferences/shared_preferences.dart';

/// A GitHub release newer than the installed app.
class AppRelease {
  final String tag;
  final String version;
  final String title;
  final String notes;
  final String pageUrl;
  final String apkUrl;
  final int apkSize;

  const AppRelease({
    required this.tag,
    required this.version,
    required this.title,
    required this.notes,
    required this.pageUrl,
    required this.apkUrl,
    required this.apkSize,
  });
}

/// Checks GitHub Releases for a newer DroidDesk build.
class UpdateService {
  static const repo = 'zhengdianqian/DroidDesk';
  // Test builds can point at a local mock: --dart-define=DROIDDESK_UPDATE_URL=http://...
  static const _latestUrl = String.fromEnvironment(
    'DROIDDESK_UPDATE_URL',
    defaultValue: 'https://api.github.com/repos/$repo/releases/latest',
  );
  static const _lastCheckKey = 'update_last_check';
  static const _skippedTagKey = 'update_skipped_tag';
  static const checkInterval = Duration(hours: 6);

  /// Returns a newer release, or null.
  ///
  /// Automatic checks run at most every [checkInterval] and respect "Skip this version";
  /// [manual] checks always hit the network and report errors by throwing.
  static Future<AppRelease?> check({
    required String installedVersion,
    bool manual = false,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    if (!manual) {
      final last = prefs.getInt(_lastCheckKey) ?? 0;
      final elapsed = DateTime.now().millisecondsSinceEpoch - last;
      if (elapsed < checkInterval.inMilliseconds) return null;
    }

    final Map<String, dynamic> json;
    try {
      json = await _fetchLatest();
    } on HttpException catch (error) {
      // Rate limiting must never nag the user with popups, manual or not.
      if (error.message.contains('403') || error.message.contains('429')) {
        // Record the attempt even when rate limited: otherwise every launch retried
        // immediately and kept the API blocked.
        await prefs.setInt(_lastCheckKey, DateTime.now().millisecondsSinceEpoch);
        return null;
      }
      if (manual) rethrow;
      return null;
    } catch (error) {
      if (manual) rethrow;
      return null;
    }
    await prefs.setInt(_lastCheckKey, DateTime.now().millisecondsSinceEpoch);

    final tag = json['tag_name']?.toString() ?? '';
    if (tag.isEmpty || json['draft'] == true || json['prerelease'] == true) return null;
    if (!isNewer(tag, installedVersion)) return null;
    if (!manual && prefs.getString(_skippedTagKey) == tag) return null;

    final apk = _pickApk((json['assets'] as List? ?? const []).cast<Map>());
    if (apk == null) return null;

    return AppRelease(
      tag: tag,
      version: _clean(tag),
      title: json['name']?.toString() ?? tag,
      notes: (json['body']?.toString() ?? '').trim(),
      pageUrl: json['html_url']?.toString() ?? 'https://github.com/$repo/releases',
      apkUrl: apk['browser_download_url'].toString(),
      apkSize: (apk['size'] as num?)?.toInt() ?? 0,
    );
  }

  /// Picks the arm64 release APK. Debug, unsigned, x86 and 32-bit builds are rejected:
  /// installing one over the signed arm64 app either fails verification or replaces it
  /// with a build that cannot run. Returns null when no suitable asset exists.
  static Map? _pickApk(List<Map> assets) {
    final apks = assets
        .where((asset) => asset['name'].toString().toLowerCase().endsWith('.apk'))
        .toList();
    for (final asset in apks) {
      final name = asset['name'].toString().toLowerCase();
      if (name.contains('debug') ||
          name.contains('unsigned') ||
          name.contains('x86') ||
          name.contains('armeabi-v7a') ||
          name.contains('armv7')) {
        continue;
      }
      if (name.contains('arm64') || name.contains('aarch64')) return asset;
    }
    return null;
  }

  static Future<void> skip(String tag) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_skippedTagKey, tag);
  }

  static Future<Map<String, dynamic>> _fetchLatest() async {
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 10);
    try {
      final request = await client.getUrl(Uri.parse(_latestUrl));
      request.headers
        ..set(HttpHeaders.acceptHeader, 'application/vnd.github+json')
        ..set(HttpHeaders.userAgentHeader, 'DroidDesk-Updater');
      final response = await request.close().timeout(const Duration(seconds: 15));
      final body = await response.transform(utf8.decoder).join();
      if (response.statusCode != 200) {
        throw HttpException('GitHub returned ${response.statusCode}');
      }
      return jsonDecode(body) as Map<String, dynamic>;
    } finally {
      client.close();
    }
  }

  /// "v1.2.0" → "1.2.0"; drops a "-suffix" and "+build".
  static String _clean(String version) =>
      version.trim().replaceFirst(RegExp(r'^[vV]'), '').split(RegExp(r'[-+ ]')).first;

  /// Build number from `1.4.5+14` (or `1.4.5-14`); 0 when the version carries none.
  static int _build(String version) {
    final match = RegExp(r'[+-](\d+)$').firstMatch(version.trim());
    return match == null ? 0 : (int.tryParse(match.group(1)!) ?? 0);
  }

  /// True when [candidate] is a higher version than [current].
  ///
  /// The build number is compared too: two releases can share a versionName, and a
  /// patch build with the same name was previously ignored, so users never received it.
  static bool isNewer(String candidate, String current) {
    List<int> parts(String v) =>
        _clean(v).split('.').map((p) => int.tryParse(p) ?? 0).toList();
    final a = parts(candidate);
    final b = parts(current);
    for (var i = 0; i < 3; i++) {
      final x = i < a.length ? a[i] : 0;
      final y = i < b.length ? b[i] : 0;
      if (x != y) return x > y;
    }
    return _build(candidate) > _build(current);
  }
}
