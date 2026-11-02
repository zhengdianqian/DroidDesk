import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:droiddesk/l10n/app_strings.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/screens/setup/setup_progress.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/widgets/update_sheet.dart';

class WelcomeScreen extends StatelessWidget {
  const WelcomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();
    promptForUpdateIfAvailable(context, state);

    return Scaffold(
      body: Container(
        decoration: BoxDecoration(
          gradient: DroidTheme.backgroundGradient,
        ),
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(28, 24, 28, 28),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Align(
                  alignment: Alignment.topRight,
                  child: IconButton(
                    onPressed: () => state.toggleThemeMode(),
                    tooltip: state.isDarkMode
                        ? AppStrings.t('theme.switchToLight')
                        : AppStrings.t('theme.switchToDark'),
                    icon: Icon(
                      state.isDarkMode
                          ? Icons.light_mode_rounded
                          : Icons.dark_mode_rounded,
                      color: DroidTheme.textMuted,
                    ),
                  ),
                ),
                const Spacer(flex: 2),
                Container(
                      width: 82,
                      height: 82,
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(22),
                        boxShadow: [
                          BoxShadow(
                            color: DroidTheme.primary.withValues(alpha: 0.22),
                            blurRadius: 36,
                            offset: const Offset(0, 14),
                          ),
                        ],
                      ),
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(22),
                        child: Image.asset(
                          'assets/icons/logo.png',
                          fit: BoxFit.cover,
                        ),
                      ),
                    )
                    .animate()
                    .fadeIn(duration: 450.ms)
                    .scale(
                      begin: const Offset(0.92, 0.92),
                      curve: Curves.easeOutCubic,
                    ),
                const SizedBox(height: 34),
                Text(
                      AppStrings.t('welcome.title'),
                      style: DroidTheme.headingXl,
                    )
                    .animate()
                    .fadeIn(delay: 100.ms, duration: 450.ms)
                    .slideY(begin: 0.08, curve: Curves.easeOutCubic),
                const SizedBox(height: 18),
                Text(
                  AppStrings.t('welcome.subtitle'),
                  style: DroidTheme.bodyLg,
                ).animate().fadeIn(delay: 180.ms, duration: 450.ms),
                const SizedBox(height: 28),
                _Feature(
                  icon: Icons.lock_outline_rounded,
                  text: AppStrings.t('welcome.feature.private'),
                ),
                const SizedBox(height: 14),
                _Feature(
                  icon: Icons.bolt_rounded,
                  text: AppStrings.t('welcome.feature.gpu'),
                ),
                const SizedBox(height: 14),
                _Feature(
                  icon: Icons.desktop_mac_outlined,
                  text: AppStrings.t('welcome.feature.desktop'),
                ),
                const Spacer(flex: 4),
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton(
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => const SetupProgressScreen(),
                      ),
                    ),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text(AppStrings.t('welcome.setup')),
                        const SizedBox(width: 8),
                        const Icon(Icons.arrow_forward_rounded, size: 19),
                      ],
                    ),
                  ),
                ).animate().fadeIn(delay: 350.ms, duration: 450.ms),
                const SizedBox(height: 14),
                Center(
                  child: Text(
                    AppStrings.t('welcome.footer'),
                    style: DroidTheme.bodySm,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Feature extends StatelessWidget {
  final IconData icon;
  final String text;

  const _Feature({required this.icon, required this.text});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Container(
          width: 34,
          height: 34,
          decoration: BoxDecoration(
            color: DroidTheme.primary.withValues(alpha: 0.12),
            shape: BoxShape.circle,
          ),
          child: Icon(icon, size: 17, color: DroidTheme.primary),
        ),
        const SizedBox(width: 13),
        Text(
          text,
          style: DroidTheme.bodyMd.copyWith(color: DroidTheme.textPrimary),
        ),
      ],
    );
  }
}
