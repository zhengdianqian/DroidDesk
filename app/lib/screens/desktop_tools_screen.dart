import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:droiddesk/l10n/app_strings.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/widgets/droid_ui.dart';

class DesktopToolsScreen extends StatefulWidget {
  const DesktopToolsScreen({super.key});

  @override
  State<DesktopToolsScreen> createState() => _DesktopToolsScreenState();
}

class _DesktopToolsScreenState extends State<DesktopToolsScreen> {
  static const _maxDockApps = 8;

  final _dockSearchController = TextEditingController();
  int _tab = 0;
  List<Map<String, dynamic>> _androidApps = const [];
  List<String> _dockPackages = const [];
  List<Map<String, dynamic>> _snapshots = const [];
  bool _dockBusy = true;
  bool _snapshotBusy = false;

  @override
  void initState() {
    super.initState();
    _loadDock();
    _loadSnapshots();
  }

  @override
  void dispose() {
    _dockSearchController.dispose();
    super.dispose();
  }

  Future<void> _loadDock() async {
    final values = await Future.wait([
      DroidDeskPlatform.getAndroidApps(),
      DroidDeskPlatform.getDockPackages(),
    ]);
    if (!mounted) return;
    setState(() {
      _androidApps = values[0] as List<Map<String, dynamic>>;
      _dockPackages = values[1] as List<String>;
      _dockBusy = false;
    });
  }

  Future<void> _saveDock(List<String> packages) async {
    setState(() {
      _dockPackages = packages;
      _dockBusy = true;
    });
    final saved = await DroidDeskPlatform.saveDockPackages(packages);
    if (!mounted) return;
    setState(() => _dockBusy = false);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          saved
              ? AppStrings.t('tools.dockUpdated')
              : AppStrings.t('tools.dockUpdateFailed'),
        ),
      ),
    );
  }

  Future<void> _loadSnapshots() async {
    final snapshots = await DroidDeskPlatform.listDesktopSnapshots();
    if (mounted) setState(() => _snapshots = snapshots);
  }

  Future<void> _createSnapshot() async {
    setState(() => _snapshotBusy = true);
    try {
      await DroidDeskPlatform.createDesktopSnapshot();
      await _loadSnapshots();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(AppStrings.t('tools.snapshotCreated'))),
        );
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              AppStrings.t('tools.snapshotFailed', args: {'error': '$error'}),
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _snapshotBusy = false);
    }
  }

  Future<void> _restoreSnapshot(String name) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppStrings.t('tools.restoreDialog.title')),
        content: Text(AppStrings.t('tools.restoreDialog.body')),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(AppStrings.t('common.cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(AppStrings.t('tools.restore')),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() => _snapshotBusy = true);
    try {
      final restored = await DroidDeskPlatform.restoreDesktopSnapshot(name);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              restored
                  ? AppStrings.t('tools.restored')
                  : AppStrings.t('tools.restoreFailed'),
            ),
          ),
        );
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              AppStrings.t('tools.restoreFailedError',
                  args: {'error': '$error'}),
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _snapshotBusy = false);
    }
  }

  Future<void> _deleteSnapshot(String name) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(AppStrings.t('tools.deleteDialog.title')),
        content: Text(name),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(AppStrings.t('common.cancel')),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            style: TextButton.styleFrom(foregroundColor: DroidTheme.error),
            child: Text(AppStrings.t('common.delete')),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      await DroidDeskPlatform.deleteDesktopSnapshot(name);
      await _loadSnapshots();
    }
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
                title: AppStrings.t('tools.title'),
                subtitle: AppStrings.t('tools.subtitle'),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 20, 20, 8),
                child: DroidSegmentedTabs(
                  labels: [
                    AppStrings.t('tools.tab.dock'),
                    AppStrings.t('tools.tab.backups'),
                  ],
                  icons: const [
                    Icons.space_dashboard_rounded,
                    Icons.history_rounded,
                  ],
                  selected: _tab,
                  onChanged: (index) => setState(() => _tab = index),
                ),
              ),
              Expanded(
                child: AnimatedSwitcher(
                  duration: const Duration(milliseconds: 200),
                  child: _tab == 0
                      ? KeyedSubtree(key: const ValueKey('dock'), child: _dockTab())
                      : KeyedSubtree(
                          key: const ValueKey('backups'),
                          child: _backupsTab(),
                        ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _dockTab() {
    if (_dockBusy && _androidApps.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    final byPackage = {
      for (final app in _androidApps) app['package'].toString(): app,
    };
    final query = _dockSearchController.text.trim().toLowerCase();
    final dockFull = _dockPackages.length >= _maxDockApps;
    final available = _androidApps
        .where((app) {
          if (_dockPackages.contains(app['package'])) return false;
          return query.isEmpty ||
              app['label'].toString().toLowerCase().contains(query) ||
              app['package'].toString().toLowerCase().contains(query);
        })
        .take(30)
        .toList();

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
      children: [
        Row(
          children: [
            DroidSectionLabel(AppStrings.t('tools.pinnedToDock')),
            const Spacer(),
            if (_dockBusy)
              const Padding(
                padding: EdgeInsets.only(right: 8),
                child: SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              ),
            _CountPill(count: _dockPackages.length, max: _maxDockApps),
          ],
        ),
        const SizedBox(height: 10),
        if (_dockPackages.isEmpty)
          DroidEmptyCard(
            icon: Icons.push_pin_outlined,
            title: AppStrings.t('tools.emptyDock.title'),
            subtitle: AppStrings.t('tools.emptyDock.subtitle'),
          )
        else
          DroidGroupCard(
            child: ReorderableListView.builder(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              buildDefaultDragHandles: false,
              itemCount: _dockPackages.length,
              proxyDecorator: (child, _, _) => Material(
                color: DroidTheme.cardBg,
                elevation: 6,
                borderRadius: BorderRadius.circular(DroidTheme.radiusMd),
                child: child,
              ),
              onReorder: (oldIndex, newIndex) {
                final next = [..._dockPackages];
                final value = next.removeAt(oldIndex);
                next.insert(newIndex > oldIndex ? newIndex - 1 : newIndex, value);
                _saveDock(next);
              },
              itemBuilder: (context, index) {
                final package = _dockPackages[index];
                final app = byPackage[package];
                return Column(
                  key: ValueKey(package),
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (index > 0) DroidInsetDivider(),
                    _AppRow(
                      package: package,
                      label: app?['label']?.toString() ?? package,
                      leading: ReorderableDragStartListener(
                        index: index,
                        child: Padding(
                          padding: const EdgeInsets.only(right: 6),
                          child: Icon(
                            Icons.drag_indicator_rounded,
                            color: DroidTheme.textDim,
                            size: 20,
                          ),
                        ),
                      ),
                      trailing: _RoundIconButton(
                        icon: Icons.close_rounded,
                        color: DroidTheme.error,
                        tooltip: AppStrings.t('tools.removeFromDock'),
                        onTap: _dockBusy
                            ? null
                            : () => _saveDock([..._dockPackages]..remove(package)),
                      ),
                    ),
                  ],
                );
              },
            ),
          ),
        const SizedBox(height: 28),
        DroidSectionLabel(AppStrings.t('tools.addAndroidApp')),
        const SizedBox(height: 10),
        DroidSearchField(
          controller: _dockSearchController,
          hint: AppStrings.t('tools.searchInstalledApps'),
          onChanged: (_) => setState(() {}),
        ),
        if (dockFull) ...[
          const SizedBox(height: 10),
          Text(
            AppStrings.t('tools.dockFull'),
            style: DroidTheme.bodySm.copyWith(color: DroidTheme.warning),
          ),
        ],
        const SizedBox(height: 12),
        if (available.isEmpty)
          DroidEmptyCard(
            icon: Icons.search_off_rounded,
            title: AppStrings.t('tools.emptyApps.title'),
            subtitle: query.isEmpty
                ? AppStrings.t('tools.emptyApps.allPinned')
                : AppStrings.t('tools.emptyApps.tryOther'),
          )
        else
          DroidGroupCard(
            child: Column(
              children: [
                for (var i = 0; i < available.length; i++) ...[
                  if (i > 0) DroidInsetDivider(),
                  _AppRow(
                    package: available[i]['package'].toString(),
                    label: available[i]['label'].toString(),
                    trailing: _RoundIconButton(
                      icon: Icons.add_rounded,
                      color: DroidTheme.primary,
                      tooltip: AppStrings.t('tools.addToDock'),
                      onTap: _dockBusy || dockFull
                          ? null
                          : () => _saveDock([
                                ..._dockPackages,
                                available[i]['package'].toString(),
                              ]),
                    ),
                  ),
                ],
              ],
            ),
          ),
      ],
    );
  }

  Widget _backupsTab() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
      children: [
        DroidGroupCard(
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    _IconTile(icon: Icons.backup_rounded, color: DroidTheme.primary),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            AppStrings.t('tools.snapshotCard.title'),
                            style: DroidTheme.headingSm,
                          ),
                          const SizedBox(height: 2),
                          Text(
                            AppStrings.t('tools.snapshotCard.subtitle'),
                            style: DroidTheme.bodySm,
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton.icon(
                    onPressed: _snapshotBusy ? null : _createSnapshot,
                    icon: _snapshotBusy
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Colors.white,
                            ),
                          )
                        : const Icon(Icons.add_rounded),
                    label: Text(
                      _snapshotBusy
                          ? AppStrings.t('tools.working')
                          : AppStrings.t('tools.createSnapshot'),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 28),
        Row(
          children: [
            DroidSectionLabel(AppStrings.t('tools.snapshots')),
            const Spacer(),
            if (_snapshots.isNotEmpty)
              Text('${_snapshots.length}', style: DroidTheme.bodySm),
          ],
        ),
        const SizedBox(height: 10),
        if (_snapshots.isEmpty)
          DroidEmptyCard(
            icon: Icons.inventory_2_outlined,
            title: AppStrings.t('tools.emptySnapshots.title'),
            subtitle: AppStrings.t('tools.emptySnapshots.subtitle'),
          )
        else
          DroidGroupCard(
            child: Column(
              children: [
                for (var i = 0; i < _snapshots.length; i++) ...[
                  if (i > 0) DroidInsetDivider(),
                  _snapshotRow(_snapshots[i]),
                ],
              ],
            ),
          ),
      ],
    );
  }

  Widget _snapshotRow(Map<String, dynamic> snapshot) {
    final name = snapshot['name'].toString();
    final created = DateTime.fromMillisecondsSinceEpoch(
      (snapshot['created'] as num).toInt(),
    ).toLocal();
    final size = _formatBytes((snapshot['sizeBytes'] as num).toInt());
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 12, 6, 12),
      child: Row(
        children: [
          _IconTile(icon: Icons.inventory_2_rounded, color: DroidTheme.accent),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  name,
                  style: DroidTheme.headingSm.copyWith(fontSize: 15),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 2),
                Text('${_formatDate(created)} · $size', style: DroidTheme.bodySm),
              ],
            ),
          ),
          PopupMenuButton<String>(
            icon: Icon(Icons.more_horiz_rounded, color: DroidTheme.textMuted),
            color: DroidTheme.surfaceLight,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(DroidTheme.radiusMd),
            ),
            onSelected: (value) => value == 'restore'
                ? _restoreSnapshot(name)
                : _deleteSnapshot(name),
            itemBuilder: (_) => [
              PopupMenuItem(
                value: 'restore',
                child: Row(
                  children: [
                    Icon(Icons.restore_rounded, size: 18, color: DroidTheme.textPrimary),
                    const SizedBox(width: 10),
                    Text(AppStrings.t('tools.restore')),
                  ],
                ),
              ),
              PopupMenuItem(
                value: 'delete',
                child: Row(
                  children: [
                    const Icon(Icons.delete_outline_rounded, size: 18, color: DroidTheme.error),
                    const SizedBox(width: 10),
                    Text(
                      AppStrings.t('common.delete'),
                      style: const TextStyle(color: DroidTheme.error),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  String _formatDate(DateTime date) {
    const months = [
      'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
      'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
    ];
    final hour = date.hour.toString().padLeft(2, '0');
    final minute = date.minute.toString().padLeft(2, '0');
    if (AppStrings.isZh) {
      return '${date.year}年${date.month}月${date.day}日 $hour:$minute';
    }
    return '${date.day} ${months[date.month - 1]} ${date.year}, $hour:$minute';
  }

  String _formatBytes(int bytes) {
    if (bytes >= 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
    }
    if (bytes >= 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    }
    return '${(bytes / 1024).toStringAsFixed(1)} KB';
  }
}

// ── Building blocks ──

class _IconTile extends StatelessWidget {
  final IconData icon;
  final Color color;

  const _IconTile({required this.icon, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 42,
      height: 42,
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Icon(icon, color: color, size: 21),
    );
  }
}

class _CountPill extends StatelessWidget {
  final int count;
  final int max;

  const _CountPill({required this.count, required this.max});

  @override
  Widget build(BuildContext context) {
    final full = count >= max;
    final color = full ? DroidTheme.warning : DroidTheme.primary;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.14),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        AppStrings.t('tools.countOfMax', args: {
          'count': '$count',
          'max': '$max',
        }),
        style: DroidTheme.label.copyWith(color: color, letterSpacing: 0.1),
      ),
    );
  }
}

class _RoundIconButton extends StatelessWidget {
  final IconData icon;
  final Color color;
  final String tooltip;
  final VoidCallback? onTap;

  const _RoundIconButton({
    required this.icon,
    required this.color,
    required this.tooltip,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final enabled = onTap != null;
    return Tooltip(
      message: tooltip,
      child: Material(
        color: color.withValues(alpha: enabled ? 0.14 : 0.06),
        shape: const CircleBorder(),
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: onTap,
          child: SizedBox(
            width: 34,
            height: 34,
            child: Icon(
              icon,
              size: 19,
              color: enabled ? color : color.withValues(alpha: 0.35),
            ),
          ),
        ),
      ),
    );
  }
}

/// One Android app: icon, name and package, with optional leading/trailing controls.
class _AppRow extends StatelessWidget {
  final String package;
  final String label;
  final Widget? leading;
  final Widget trailing;

  const _AppRow({
    required this.package,
    required this.label,
    required this.trailing,
    this.leading,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.fromLTRB(leading == null ? 14 : 8, 10, 12, 10),
      child: Row(
        children: [
          ?leading,
          _AppIcon(package: package, label: label),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: DroidTheme.headingSm.copyWith(fontSize: 15),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 1),
                Text(
                  package,
                  style: DroidTheme.bodySm.copyWith(fontSize: 12),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          trailing,
        ],
      ),
    );
  }
}

/// Launcher icon loaded from Android once and cached; falls back to a letter tile.
class _AppIcon extends StatefulWidget {
  final String package;
  final String label;

  const _AppIcon({required this.package, required this.label});

  static final Map<String, Future<Uint8List?>> _cache = {};

  @override
  State<_AppIcon> createState() => _AppIconState();
}

class _AppIconState extends State<_AppIcon> {
  late Future<Uint8List?> _icon;

  @override
  void initState() {
    super.initState();
    _icon = _load();
  }

  @override
  void didUpdateWidget(covariant _AppIcon oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.package != widget.package) _icon = _load();
  }

  Future<Uint8List?> _load() => _AppIcon._cache.putIfAbsent(
        widget.package,
        () => DroidDeskPlatform.getAppIcon(widget.package).catchError((_) => null),
      );

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Uint8List?>(
      future: _icon,
      builder: (context, snapshot) {
        final bytes = snapshot.data;
        if (bytes != null) {
          return ClipRRect(
            borderRadius: BorderRadius.circular(11),
            child: Image.memory(
              bytes,
              width: 42,
              height: 42,
              gaplessPlayback: true,
              filterQuality: FilterQuality.medium,
            ),
          );
        }
        final letter = widget.label.isEmpty ? '?' : widget.label[0].toUpperCase();
        return Container(
          width: 42,
          height: 42,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: DroidTheme.primary.withValues(alpha: 0.14),
            borderRadius: BorderRadius.circular(11),
          ),
          child: Text(
            letter,
            style: DroidTheme.headingSm.copyWith(color: DroidTheme.primary),
          ),
        );
      },
    );
  }
}
