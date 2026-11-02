import 'package:droiddesk/l10n/app_strings.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/widgets/droid_ui.dart';
import 'package:flutter/material.dart';

/// How much space Linux uses on the phone, with a one-tap package cache clean-up.
class StorageScreen extends StatefulWidget {
  const StorageScreen({super.key});

  @override
  State<StorageScreen> createState() => _StorageScreenState();
}

class _StorageScreenState extends State<StorageScreen> {
  Map<String, dynamic>? _info;
  bool _cleaning = false;

  static const _systemColor = DroidTheme.primary;
  static const _homeColor = Color(0xFFBF5AF2);
  static const _cacheColor = DroidTheme.warning;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final info = await DroidDeskPlatform.getStorageInfo();
    if (mounted) setState(() => _info = info);
  }

  int _bytes(String key) => (_info?[key] as num?)?.toInt() ?? 0;

  Future<void> _clean() async {
    setState(() => _cleaning = true);
    final freed = await DroidDeskPlatform.cleanPackageCache();
    await _load();
    if (!mounted) return;
    setState(() => _cleaning = false);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          freed < 0
              ? AppStrings.t('storage.cleanFailed')
              : AppStrings.t('storage.freed', args: {'size': _format(freed)}),
        ),
        backgroundColor: freed < 0 ? DroidTheme.error : DroidTheme.success,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        decoration: BoxDecoration(gradient: DroidTheme.backgroundGradient),
        child: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              DroidPageHeader(
                title: AppStrings.t('storage.title'),
                subtitle: AppStrings.t('storage.subtitle'),
              ),
              Expanded(
                child: _info == null
                    ? const Center(child: CircularProgressIndicator())
                    : RefreshIndicator(onRefresh: _load, child: _content()),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _content() {
    final system = _bytes('system');
    final home = _bytes('home');
    final cache = _bytes('cache');
    final linux = system + home + cache;
    final free = _bytes('deviceFree');
    final total = _bytes('deviceTotal');
    final other = (total - free - linux).clamp(0, total);
    final packages = _bytes('packages');

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 32),
      children: [
        DroidGroupCard(
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(AppStrings.t('storage.linuxUses'), style: DroidTheme.label),
                const SizedBox(height: 6),
                Text(
                  _format(linux),
                  style: DroidTheme.headingXl.copyWith(fontSize: 32),
                ),
                Text(
                  AppStrings.t('storage.packagesLine', args: {
                    'count': '$packages',
                    'free': _format(free),
                    'total': _format(total),
                  }),
                  style: DroidTheme.bodySm,
                ),
                const SizedBox(height: 18),
                _usageBar(total, [
                  (system, _systemColor),
                  (home, _homeColor),
                  (cache, _cacheColor),
                  (other, DroidTheme.textDim.withValues(alpha: 0.45)),
                ]),
                const SizedBox(height: 16),
                _legend(AppStrings.t('storage.legend.system'), system, _systemColor),
                _legend(AppStrings.t('storage.legend.home'), home, _homeColor),
                _legend(AppStrings.t('storage.legend.cache'), cache, _cacheColor),
                _legend(AppStrings.t('storage.legend.other'), other, DroidTheme.textDim),
                _legend(AppStrings.t('storage.legend.free'), free, DroidTheme.surfaceBorder),
              ],
            ),
          ),
        ),
        const SizedBox(height: 26),
        DroidSectionLabel(AppStrings.t('storage.cleanUp')),
        const SizedBox(height: 10),
        DroidGroupCard(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(14, 14, 14, 14),
            child: Row(
              children: [
                Container(
                  width: 42,
                  height: 42,
                  decoration: BoxDecoration(
                    color: _cacheColor.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Icon(
                    Icons.cleaning_services_rounded,
                    color: _cacheColor,
                    size: 21,
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        AppStrings.t('storage.packageCache'),
                        style: DroidTheme.headingSm.copyWith(fontSize: 15),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        cache > 0
                            ? AppStrings.t('storage.cacheHint',
                                args: {'size': _format(cache)})
                            : AppStrings.t('storage.nothingToClean'),
                        style: DroidTheme.bodySm,
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 10),
                _cleaning
                    ? const SizedBox(
                        width: 22,
                        height: 22,
                        child: CircularProgressIndicator(strokeWidth: 2.4),
                      )
                    : Material(
                        color: _cacheColor.withValues(alpha: cache > 0 ? 0.16 : 0.06),
                        borderRadius: BorderRadius.circular(20),
                        child: InkWell(
                          borderRadius: BorderRadius.circular(20),
                          onTap: cache > 0 ? _clean : null,
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 16,
                              vertical: 7,
                            ),
                            child: Text(
                              AppStrings.t('storage.cleanButton'),
                              style: DroidTheme.label.copyWith(
                                fontSize: 13,
                                fontWeight: FontWeight.w700,
                                letterSpacing: 0.4,
                                color: _cacheColor.withValues(
                                  alpha: cache > 0 ? 1 : 0.4,
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4),
          child: Text(
            AppStrings.t('storage.cleanNote'),
            style: DroidTheme.bodySm,
          ),
        ),
      ],
    );
  }

  Widget _usageBar(int total, List<(int, Color)> parts) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(8),
      child: Container(
        height: 14,
        color: DroidTheme.surfaceLight,
        child: Row(
          children: [
            for (final (bytes, color) in parts)
              if (total > 0 && bytes > 0)
                Expanded(
                  // Keep tiny slices visible.
                  flex: (bytes * 1000 ~/ total).clamp(4, 1000),
                  child: Container(color: color),
                ),
            Expanded(
              flex: ((total - parts.fold<int>(0, (sum, p) => sum + p.$1)) * 1000 ~/
                      (total == 0 ? 1 : total))
                  .clamp(1, 1000),
              child: const SizedBox(),
            ),
          ],
        ),
      ),
    );
  }

  Widget _legend(String label, int bytes, Color color) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        children: [
          Container(
            width: 10,
            height: 10,
            decoration: BoxDecoration(color: color, shape: BoxShape.circle),
          ),
          const SizedBox(width: 10),
          Expanded(child: Text(label, style: DroidTheme.bodyMd)),
          Text(
            _format(bytes),
            style: DroidTheme.bodyMd.copyWith(
              color: DroidTheme.textPrimary,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }

  static String _format(int bytes) {
    if (bytes >= 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
    }
    if (bytes >= 1024 * 1024) return '${(bytes / (1024 * 1024)).toStringAsFixed(0)} MB';
    if (bytes >= 1024) return '${(bytes / 1024).toStringAsFixed(0)} KB';
    return '$bytes B';
  }
}
