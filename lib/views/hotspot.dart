import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/l10n/l10n.dart';
import 'package:fl_clash/plugins/hotspot.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class HotspotView extends ConsumerStatefulWidget {
  const HotspotView({super.key});

  @override
  ConsumerState<HotspotView> createState() => _HotspotViewState();
}

class _HotspotViewState extends ConsumerState<HotspotView> {
  late final ScrollController _controller;
  late final OutboundIpProbe _outboundProbe;
  StreamSubscription<HotspotSnapshot>? _subscription;
  HotspotSnapshot? _snapshot;
  bool _loading = true;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _controller = sheetScrollController(context);
    _outboundProbe = ref.read(outboundIpProbeProvider.notifier)
      ..watch(routedOutbound);
    if (!hotspotManager.isSupported) {
      _snapshot = const HotspotSnapshot();
      _loading = false;
      return;
    }
    _subscription = hotspotManager.watchState().listen(
      (snapshot) {
        if (!mounted) {
          return;
        }
        setState(() {
          _snapshot = snapshot;
          _loading = false;
        });
      },
      onError: (Object error) {
        if (!mounted) {
          return;
        }
        setState(() => _loading = false);
        _showError(error);
      },
    );
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _subscription?.cancel();
    _outboundProbe.unwatch(routedOutbound);
    _controller.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (!hotspotManager.isSupported) {
      return;
    }
    await _runAction(() async {
      final state = await hotspotManager.getState();
      if (!mounted) {
        return;
      }
      setState(() {
        _snapshot = state;
        _loading = false;
      });
    });
  }

  Future<void> _runAction(Future<void> Function() action) async {
    if (_busy) {
      return;
    }
    setState(() => _busy = true);
    try {
      await action();
    } catch (error) {
      if (mounted) {
        _showError(error);
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  void _showError(Object error) {
    final message = error is PlatformException
        ? (error.message ?? error.code)
        : error.toString();
    dialogs.showBanner(message, level: BannerLevel.error);
  }

  Future<void> _toggleAutoMatchCountryCode(bool enabled) async {
    await _runAction(() async {
      await hotspotManager.setAutoMatchCountryCode(enabled);
      if (enabled) {
        final exitCode = normalizeHotspotCountryCode(
          ref
              .read(outboundIpProbeProvider)
              .entryOf(routedOutbound)
              .value
              ?.countryCode,
        );
        if (exitCode != null) {
          final applied = await hotspotManager.setCountryCode(exitCode);
          if (mounted && applied != null) {
            dialogs.showBanner(
              context.appLocalizations.hotspotCountryCodeUpdated(applied),
              level: BannerLevel.success,
            );
          }
        } else {
          _outboundProbe.refresh(const [routedOutbound]);
        }
      }
      final state = await hotspotManager.getState();
      if (mounted) {
        setState(() => _snapshot = state);
      }
    });
  }

  Future<void> _syncExitCountryCodeNow() async {
    await _runAction(() async {
      _outboundProbe.refresh(const [routedOutbound]);
      final exitCode = normalizeHotspotCountryCode(
        ref
            .read(outboundIpProbeProvider)
            .entryOf(routedOutbound)
            .value
            ?.countryCode,
      );
      if (exitCode == null) {
        return;
      }
      final applied = await hotspotManager.setCountryCode(exitCode);
      final state = await hotspotManager.getState();
      if (!mounted) {
        return;
      }
      setState(() => _snapshot = state);
      if (applied != null) {
        dialogs.showBanner(
          context.appLocalizations.hotspotCountryCodeUpdated(applied),
          level: BannerLevel.success,
        );
      }
    });
  }

  Future<void> _resetCountryCode() async {
    await _runAction(() async {
      await hotspotManager.setCountryCode(null);
      final state = await hotspotManager.getState();
      if (!mounted) {
        return;
      }
      setState(() => _snapshot = state);
      dialogs.showBanner(
        context.appLocalizations.hotspotCountryCodeReset,
        level: BannerLevel.success,
      );
    });
  }

  Future<void> _cleanRouting() async {
    await _runAction(() async {
      await hotspotManager.cleanRouting();
      final state = await hotspotManager.getState();
      if (!mounted) {
        return;
      }
      setState(() => _snapshot = state);
      dialogs.showBanner(
        context.appLocalizations.hotspotCleaned,
        level: BannerLevel.success,
      );
    });
  }

  Future<void> _toggleInterfaceManaged(String iface, bool enabled) {
    return _runAction(() => hotspotManager.setInterfaceManaged(iface, enabled));
  }

  Future<void> _toggleInterfaceMonitored(String iface, bool enabled) {
    return _runAction(
      () => hotspotManager.setInterfaceMonitored(iface, enabled),
    );
  }

  String _masqueradeLabel(AppLocalizations l, HotspotMasqueradeMode mode) {
    return switch (mode) {
      HotspotMasqueradeMode.none => l.hotspotMasqueradeNone,
      HotspotMasqueradeMode.simple => l.hotspotMasqueradeSimple,
      HotspotMasqueradeMode.netd => l.hotspotMasqueradeNetd,
    };
  }

  String _ipv6Label(AppLocalizations l, HotspotIpv6Mode mode) {
    return switch (mode) {
      HotspotIpv6Mode.system => l.hotspotIpv6System,
      HotspotIpv6Mode.block => l.hotspotIpv6Block,
      HotspotIpv6Mode.nat => l.hotspotIpv6Nat,
    };
  }

  List<Widget> _buildSections(
    BuildContext context,
    HotspotSnapshot snapshot,
    String? exitCountryCode,
  ) {
    final l = context.appLocalizations;
    final colorScheme = context.colorScheme;
    final vpnLabel = snapshot.vpnUpstream ?? l.hotspotWaitingForVpn;
    final fallbackLabel = snapshot.fallbackUpstream ?? l.hotspotNone;
    final currentCc = snapshot.currentCountryCode ?? l.hotspotNone;
    final exitCcLabel = exitCountryCode ?? l.hotspotNone;
    final repeaterSub = [
      if (snapshot.repeaterNetworkName != null &&
          snapshot.repeaterNetworkName!.isNotEmpty)
        snapshot.repeaterNetworkName!,
      if (snapshot.repeaterInterface != null &&
          snapshot.repeaterInterface!.isNotEmpty)
        snapshot.repeaterInterface!,
    ].join(' · ');
    final tempSub = [
      if (snapshot.localOnlySsid != null && snapshot.localOnlySsid!.isNotEmpty)
        snapshot.localOnlySsid!,
      if (snapshot.localOnlyInterface != null &&
          snapshot.localOnlyInterface!.isNotEmpty)
        snapshot.localOnlyInterface!,
    ].join(' · ');

    return [
      generateSection(
        title: l.hotspotUpstream,
        items: [
          ListItem(
            leading: const GlyphIcon(AppGlyphs.vpn),
            title: Text(l.hotspotVpnUpstream),
            subtitle: Text(vpnLabel),
            trailing: snapshot.activeClientCount > 0
                ? TonalChip(
                    label: '${snapshot.activeClientCount}',
                    color: colorScheme.primary.opacity15,
                    foregroundColor: colorScheme.primary,
                  )
                : null,
          ),
          ListItem(
            leading: const GlyphIcon(AppGlyphs.proxies),
            title: Text(l.hotspotFallbackUpstream),
            subtitle: Text(fallbackLabel),
          ),
          ListItem(
            leading: const GlyphIcon(AppGlyphs.openExternal),
            title: Text(l.hotspotOpenNative),
            subtitle: Text(l.hotspotOpenNativeDesc),
            onTap: () => _runAction(hotspotManager.openNativeUi),
          ),
        ],
      ),
      generateSection(
        title: l.hotspotCountryCode,
        items: [
          ListItem.toggle(
            leading: const GlyphIcon(AppGlyphs.locate),
            title: Text(l.hotspotAutoMatchCountryCode),
            subtitle: Text(l.hotspotAutoMatchCountryCodeDesc),
            value: snapshot.autoMatchCountryCode,
            onChanged: _toggleAutoMatchCountryCode,
          ),
          ListItem(
            leading: const GlyphIcon(AppGlyphs.wifi),
            title: Text(l.hotspotCurrentCountryCode),
            subtitle: Text(currentCc),
            trailing: TonalChip(
              label: '${l.hotspotExitCountryCode}: $exitCcLabel',
              color: colorScheme.primary.opacity15,
              foregroundColor: colorScheme.primary,
            ),
          ),
          ListItem(
            leading: const GlyphIcon(AppGlyphs.sync),
            title: Text(l.hotspotSyncCountryCode),
            subtitle: Text(exitCcLabel),
            onTap: _syncExitCountryCodeNow,
          ),
          ListItem(
            leading: const GlyphIcon(AppGlyphs.reset),
            title: Text(l.hotspotResetCountryCode),
            onTap: _resetCountryCode,
          ),
        ],
      ),
      generateSection(
        title: l.hotspotTethering,
        items: [
          ListItem.toggle(
            leading: const GlyphIcon(AppGlyphs.wifi),
            title: Text(l.hotspotWifiTethering),
            value: snapshot.systemHotspotActive,
            onChanged: (value) => _runAction(
              () => hotspotManager.setSystemTetheringEnabled('wifi', value),
            ),
          ),
          ListItem.toggle(
            leading: const GlyphIcon(AppGlyphs.link),
            title: Text(l.hotspotUsbTethering),
            value: snapshot.usbTetheringActive,
            onChanged: (value) => _runAction(
              () => hotspotManager.setSystemTetheringEnabled('usb', value),
            ),
          ),
          if (snapshot.ethernetSupported)
            ListItem.toggle(
              leading: const GlyphIcon(AppGlyphs.connections),
              title: Text(l.hotspotEthernetTethering),
              value: snapshot.ethernetTetheringActive,
              onChanged: (value) => _runAction(
                () => hotspotManager.setSystemTetheringEnabled(
                  'ethernet',
                  value,
                ),
              ),
            ),
          if (snapshot.repeaterSupported)
            ListItem.toggle(
              leading: const GlyphIcon(AppGlyphs.radar),
              title: Text(l.hotspotRepeater),
              subtitle: repeaterSub.isEmpty ? null : Text(repeaterSub),
              value: snapshot.repeaterActive,
              onChanged: (value) =>
                  _runAction(() => hotspotManager.setRepeaterEnabled(value)),
            ),
          ListItem.toggle(
            leading: const GlyphIcon(AppGlyphs.bolt),
            title: Text(l.hotspotTempHotspot),
            subtitle: tempSub.isEmpty ? null : Text(tempSub),
            value: snapshot.localOnlyActive,
            onChanged: (value) => _runAction(
              () => hotspotManager.setLocalOnlyHotspotEnabled(value),
            ),
          ),
        ],
      ),
      if (snapshot.interfaces.isNotEmpty)
        generateSection(
          title: l.hotspotInterfaces,
          items: [
            for (final iface in snapshot.interfaces) ...[
              ListItem.toggle(
                leading: GlyphIcon(
                  iface.type == 'wifi' || iface.type == 'wifi_p2p'
                      ? AppGlyphs.wifi
                      : AppGlyphs.connections,
                ),
                title: Text(iface.name),
                subtitle: iface.addresses.isEmpty
                    ? null
                    : Text(iface.addresses.join(', ')),
                value: iface.managed,
                onChanged: iface.inactive
                    ? null
                    : (value) => _toggleInterfaceManaged(iface.name, value),
              ),
              if (iface.managed || iface.monitored)
                ListItem.toggle(
                  title: Text(l.hotspotWatchReconnect),
                  subtitle: Text(iface.name),
                  value: iface.monitored,
                  onChanged: (value) =>
                      _toggleInterfaceMonitored(iface.name, value),
                ),
            ],
          ],
        ),
      generateSection(
        title: l.hotspotClients,
        items: snapshot.clients.isEmpty
            ? [
                ListItem(
                  leading: const GlyphIcon(AppGlyphs.devices),
                  title: Text(l.hotspotNoClients),
                ),
              ]
            : [
                for (final client in snapshot.clients)
                  ListItem(
                    leading: const GlyphIcon(AppGlyphs.devices),
                    title: Text(client.displayName),
                    subtitle: Text(
                      [
                        if (client.iface != null) client.iface!,
                        if (client.nickname.isNotEmpty) client.mac,
                        ...client.addresses,
                      ].join(' · '),
                    ),
                    trailing: client.blocked
                        ? TonalChip(
                            label: l.hotspotBlocked,
                            color: colorScheme.error.opacity15,
                            foregroundColor: colorScheme.error,
                          )
                        : null,
                  ),
              ],
      ),
      generateSection(
        title: l.hotspotRouting,
        items: [
          ListItem<HotspotMasqueradeMode>.options(
            leading: const GlyphIcon(AppGlyphs.sliders),
            title: Text(l.hotspotMasqueradeMode),
            options: HotspotMasqueradeMode.values,
            value: snapshot.masqueradeMode,
            textBuilder: (mode) => _masqueradeLabel(l, mode),
            onChanged: (mode) =>
                _runAction(() => hotspotManager.setMasqueradeMode(mode)),
          ),
          ListItem<HotspotIpv6Mode>.options(
            leading: const GlyphIcon(AppGlyphs.layers),
            title: Text(l.hotspotIpv6Mode),
            options: HotspotIpv6Mode.values,
            value: snapshot.ipv6Mode,
            textBuilder: (mode) => _ipv6Label(l, mode),
            onChanged: (mode) =>
                _runAction(() => hotspotManager.setIpv6Mode(mode)),
          ),
          ListItem.toggle(
            leading: const GlyphIcon(AppGlyphs.speed),
            title: Text(l.hotspotTetherOffload),
            subtitle: Text(l.hotspotTetherOffloadDesc),
            value: snapshot.tetherOffloadEnabled,
            onChanged: (value) =>
                _runAction(() => hotspotManager.setTetherOffloadEnabled(value)),
          ),
          ListItem.toggle(
            leading: const GlyphIcon(AppGlyphs.bolt),
            title: Text(l.hotspotAutoStart),
            value: snapshot.autoStart,
            onChanged: (value) =>
                _runAction(() => hotspotManager.setAutoStartEnabled(value)),
          ),
          ListItem(
            leading: const GlyphIcon(AppGlyphs.broom),
            title: Text(l.hotspotCleanRouting),
            subtitle: Text(l.hotspotCleanRoutingDesc),
            onTap: _cleanRouting,
          ),
          ListItem(
            leading: const GlyphIcon(AppGlyphs.settings),
            title: Text(l.hotspotSystemSettings),
            onTap: () => _runAction(hotspotManager.openSystemTetheringSettings),
          ),
        ],
      ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final l = context.appLocalizations;
    final exitCountryCode = normalizeHotspotCountryCode(
      ref.watch(
        outboundIpProbeProvider.select(
          (state) => state.entryOf(routedOutbound).value?.countryCode,
        ),
      ),
    );
    final snapshot = _snapshot;
    final supported = snapshot?.supported == true;
    final sections = supported
        ? _buildSections(context, snapshot!, exitCountryCode)
        : const <Widget>[];
    return Scaffold(
      title: l.vpnHotspot,
      isLoading: _busy,
      iconActions: [
        if (supported) ...[
          IconButtonData(
            glyph: AppGlyphs.refresh,
            tooltip: l.sync,
            onPressed: _refresh,
          ),
          IconButtonData(
            glyph: AppGlyphs.openExternal,
            tooltip: l.hotspotOpenNative,
            onPressed: () => _runAction(hotspotManager.openNativeUi),
          ),
        ],
      ],
      body: Builder(
        builder: (context) {
          if (_loading) {
            return const Center(child: ActivityIndicator.page());
          }
          if (!supported) {
            return NullStatus(
              label: l.vpnHotspot,
              description: l.hotspotAndroidOnly,
              illustration: NullStatusIllustration.permission,
            );
          }
          return PrismListView(
            controller: _controller,
            itemCount: sections.length,
            itemBuilder: (_, index) => sections[index],
            padding: EdgeInsets.only(
              left: 16,
              right: 16,
              top: context.sectionsTopPadding,
              bottom: 20 + context.bottomInset,
            ),
          );
        },
      ),
    );
  }
}
