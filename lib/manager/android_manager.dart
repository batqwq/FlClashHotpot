import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/core/core.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/plugins/app.dart';
import 'package:fl_clash/plugins/hotspot.dart';
import 'package:fl_clash/plugins/service.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:prism_ui/banner.dart';

class AndroidManager extends ConsumerStatefulWidget {
  final Widget child;

  const AndroidManager({super.key, required this.child});

  @override
  ConsumerState<AndroidManager> createState() => _AndroidContainerState();
}

class _AndroidContainerState extends ConsumerState<AndroidManager>
    with ServiceListener {
  StreamSubscription<bool>? _autoMatchSubscription;
  ProviderSubscription<String?>? _exitCountrySubscription;
  OutboundIpProbe? _outboundProbe;
  bool _autoMatchCountryCode = false;
  bool _watchingOutbound = false;
  String? _lastSyncedCountryCode;

  @override
  void initState() {
    super.initState();
    ref.listenManual(appSettingProvider.select((state) => state.hidden), (
      prev,
      next,
    ) {
      app?.updateExcludeFromRecents(next);
    }, fireImmediately: true);
    ref.listenManual(loadedLocaleProvider, (prev, next) {
      if (prev != null && prev != next && !safeModeBuild) {
        app?.initShortcuts();
      }
    });
    ref.listenManual(sharedStateProvider, (prev, next) {
      if (prev != next) {
        debouncer.call(FunctionTag.saveSharedFile, () async {
          await preferences.saveShareState(next);
        }, duration: const Duration(seconds: 1));
        if (prev?.needSyncSharedState != next.needSyncSharedState) {
          service?.syncState(next.needSyncSharedState);
        }
      }
    });
    if (hotspotManager.isSupported) {
      _outboundProbe = ref.read(outboundIpProbeProvider.notifier);
      _exitCountrySubscription = ref.listenManual(
        outboundIpProbeProvider.select(
          (state) => state.entryOf(routedOutbound).value?.countryCode,
        ),
        (_, next) => _syncCountryCodeIfEnabled(next),
      );
      _autoMatchSubscription = hotspotManager
          .watchAutoMatchCountryCode()
          .listen(_handleAutoMatchChanged, onError: (Object _) {});
    }
    service?.addListener(this);
    app?.onPackagesChanged = _reloadPackages;
  }

  void _handleAutoMatchChanged(bool enabled) {
    if (!mounted) {
      return;
    }
    _autoMatchCountryCode = enabled;
    if (enabled) {
      if (!_watchingOutbound) {
        _watchingOutbound = true;
        _outboundProbe?.watch(routedOutbound);
      }
      _syncCountryCodeIfEnabled(
        ref
            .read(outboundIpProbeProvider)
            .entryOf(routedOutbound)
            .value
            ?.countryCode,
      );
    } else {
      if (_watchingOutbound) {
        _watchingOutbound = false;
        _outboundProbe?.unwatch(routedOutbound);
      }
      _lastSyncedCountryCode = null;
    }
  }

  void _syncCountryCodeIfEnabled(String? rawCountryCode) {
    if (!_autoMatchCountryCode || !hotspotManager.isSupported) {
      return;
    }
    final code = normalizeHotspotCountryCode(rawCountryCode);
    if (code == null || code == _lastSyncedCountryCode) {
      return;
    }
    _lastSyncedCountryCode = code;
    unawaited(
      hotspotManager.setCountryCode(code).catchError((Object _) {
        if (_lastSyncedCountryCode == code) {
          _lastSyncedCountryCode = null;
        }
        return null;
      }),
    );
  }

  /// A reload reads every installed app's label, and a round of store updates
  /// reports each app on its own.
  void _reloadPackages() {
    if (ref.read(packagesProvider).isEmpty) {
      return;
    }
    debouncer.call(FunctionTag.reloadPackages, () async {
      if (mounted) {
        await ref.read(systemActionProvider.notifier).getPackages();
      }
    }, duration: const Duration(seconds: 2));
  }

  @override
  void dispose() {
    _autoMatchSubscription?.cancel();
    _exitCountrySubscription?.close();
    if (_watchingOutbound) {
      _watchingOutbound = false;
      _outboundProbe?.unwatch(routedOutbound);
    }
    debouncer.cancel(FunctionTag.reloadPackages);
    if (app?.onPackagesChanged == _reloadPackages) {
      app?.onPackagesChanged = null;
    }
    service?.removeListener(this);
    super.dispose();
  }

  @override
  void onServiceEvent(CoreEvent event) {
    coreEventManager.sendEvent(event);
    super.onServiceEvent(event);
  }

  @override
  void onServiceStopped([ServiceStartFailure? failure]) {
    unawaited(ref.read(setupActionProvider.notifier).setRunning(false));
    if (failure != null) {
      dialogs.showBanner(failure.label, level: BannerLevel.error);
    }
    super.onServiceStopped(failure);
  }

  @override
  Widget build(BuildContext context) {
    return widget.child;
  }
}
