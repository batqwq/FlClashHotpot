import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/plugins/hotspot.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/views/hotspot.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:prism_ui/shapes/shapes.dart';

import 'card.dart';

class HotspotCard extends StatefulWidget {
  const HotspotCard({super.key});

  @override
  State<HotspotCard> createState() => _HotspotCardState();
}

class _HotspotCardState extends State<HotspotCard> {
  late final _state = hotspotManager.watchQuickState();
  var _busy = false;

  Future<void> _runAction(Future<void> Function() action) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await action();
    } catch (error) {
      if (mounted) {
        final message = error is PlatformException
            ? (error.message ?? error.code)
            : error.toString();
        dialogs.showBanner(message, level: BannerLevel.error);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final localizations = context.appLocalizations;
    void openSettings() {
      pushNestedPage<void>(context, const HotspotView());
    }
    return DashboardCard(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: 12,
        children: [
          PressSurface(
            fill: Colors.transparent,
            shape: AppShape.sm,
            onPressed: openSettings,
            child: SizedBox(
              height: 44,
              child: Center(
                child: DashboardCardHeader(
                  glyph: AppGlyphs.wifi,
                  title: localizations.vpnHotspot,
                  chevron: true,
                ),
              ),
            ),
          ),
          StreamBuilder<HotspotSnapshot>(
            stream: _state,
            builder: (context, event) {
              final snapshot = event.data;
              final ready =
                  !event.hasError && snapshot?.supported == true && !_busy;
              final wifi = snapshot?.systemHotspotActive ?? false;
              final usb = snapshot?.usbTetheringActive ?? false;
              final mac =
                  snapshot?.macWiredMode == true ||
                  snapshot?.ncmTetheringActive == true;
              final macSupported = snapshot?.ncmSupported ?? false;
              Widget toggle({
                required Glyph glyph,
                required String title,
                required bool value,
                required bool enabled,
                required Future<void> Function(bool) action,
              }) => DashboardSwitchRow(
                glyph: glyph,
                title: title,
                value: value,
                onChanged: enabled
                    ? (value) => _runAction(() => action(value))
                    : null,
                onTap: enabled ? () => _runAction(() => action(!value)) : null,
                iconSize: 32,
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 8,
                ),
                radius: AppCorner.md,
              );
              return Column(
                spacing: 6,
                children: [
                  toggle(
                    glyph: AppGlyphs.wifi,
                    title: localizations.hotspotWifiTethering,
                    value: wifi,
                    enabled: ready,
                    action: (value) async {
                      await hotspotManager.setSystemTetheringEnabled(
                        'wifi',
                        value,
                      );
                    },
                  ),
                  toggle(
                    glyph: AppGlyphs.link,
                    title: localizations.hotspotUsbTethering,
                    value: usb,
                    enabled: ready && !mac,
                    action: (value) async {
                      await hotspotManager.setSystemTetheringEnabled(
                        'usb',
                        value,
                      );
                    },
                  ),
                  toggle(
                    glyph: AppGlyphs.connections,
                    title: localizations.hotspotMacWiredMode,
                    value: mac,
                    enabled: ready && (mac || (macSupported && !usb)),
                    action: hotspotManager.setMacWiredMode,
                  ),
                ],
              );
            },
          ),
        ],
      ),
    );
  }
}
