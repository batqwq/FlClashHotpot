import 'dart:async';

import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/plugins/hotspot.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/state.dart';
import 'package:fl_clash/views/hotspot.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import '../helpers/test_app.dart';

class _TestOutboundIpProbe extends OutboundIpProbe {
  _TestOutboundIpProbe(this._countryCode);

  final String? _countryCode;
  int refreshCalls = 0;

  @override
  RoutedProbeState<String, IpInfo> build() => RoutedProbeState({
    if (_countryCode != null)
      routedOutbound: ProbeEntry(
        phase: ProbePhase.fresh,
        value: IpInfo(ip: '203.0.113.10', countryCode: _countryCode),
      ),
  });

  @override
  void watch(String target) {}

  @override
  void unwatch(String target) {}

  @override
  void refresh([Iterable<String>? targets]) {
    refreshCalls++;
  }
}

class _FakeHotspotHost implements HotspotHost {
  _FakeHotspotHost({
    required this.supported,
    this.snapshot = const HotspotSnapshot(supported: true),
  });

  final bool supported;
  HotspotSnapshot snapshot;
  final calls = <String>[];
  Exception? nextError;
  final _stateController = StreamController<HotspotSnapshot>.broadcast();
  final _autoMatchController = StreamController<bool>.broadcast();

  void emit(HotspotSnapshot value) {
    snapshot = value;
    _stateController.add(value);
  }

  void close() {
    unawaited(_stateController.close());
    unawaited(_autoMatchController.close());
  }

  void _throwIfNeeded() {
    final error = nextError;
    if (error != null) {
      nextError = null;
      throw error;
    }
  }

  @override
  bool get isSupported => supported;

  @override
  Future<HotspotSnapshot> getState() async {
    calls.add('getState');
    _throwIfNeeded();
    return snapshot;
  }

  @override
  Stream<HotspotSnapshot> watchState() => _stateController.stream;

  @override
  Stream<bool> watchAutoMatchCountryCode() => _autoMatchController.stream;

  @override
  Future<void> setInterfaceManaged(String iface, bool enabled) async {
    _throwIfNeeded();
    calls.add('managed:$iface:$enabled');
  }

  @override
  Future<void> setInterfaceMonitored(String iface, bool enabled) async {
    _throwIfNeeded();
    calls.add('monitored:$iface:$enabled');
  }

  @override
  Future<void> setRepeaterEnabled(bool enabled) async {
    _throwIfNeeded();
    calls.add('repeater:$enabled');
  }

  @override
  Future<void> setLocalOnlyHotspotEnabled(bool enabled) async {
    _throwIfNeeded();
    calls.add('localOnly:$enabled');
  }

  @override
  Future<bool> setSystemTetheringEnabled(String type, bool enabled) async {
    _throwIfNeeded();
    calls.add('system:$type:$enabled');
    return true;
  }

  @override
  Future<void> setMasqueradeMode(HotspotMasqueradeMode mode) async {
    _throwIfNeeded();
    calls.add('masquerade:${mode.name}');
  }

  @override
  Future<void> setIpv6Mode(HotspotIpv6Mode mode) async {
    _throwIfNeeded();
    calls.add('ipv6:${mode.name}');
  }

  @override
  Future<void> setTetherOffloadEnabled(bool enabled) async {
    _throwIfNeeded();
    calls.add('offload:$enabled');
  }

  @override
  Future<void> setAutoStartEnabled(bool enabled) async {
    _throwIfNeeded();
    calls.add('autoStart:$enabled');
  }

  @override
  Future<void> setAutoMatchCountryCode(bool enabled) async {
    _throwIfNeeded();
    calls.add('autoMatch:$enabled');
  }

  @override
  Future<String?> setCountryCode(String? countryCode) async {
    _throwIfNeeded();
    calls.add('countryCode:$countryCode');
    return normalizeHotspotCountryCode(countryCode);
  }

  @override
  Future<void> cleanRouting() async {
    _throwIfNeeded();
    calls.add('cleanRouting');
  }

  @override
  Future<void> setAlwaysShareAll(bool enabled) async {
    _throwIfNeeded();
    calls.add('alwaysShareAll:$enabled');
  }

  @override
  Future<void> setMacWiredMode(bool enabled) async {
    _throwIfNeeded();
    calls.add('macWiredMode:$enabled');
  }

  @override
  Future<void> openSystemTetheringSettings() async {
    _throwIfNeeded();
    calls.add('openSystemTetheringSettings');
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late ProviderContainer container;

  Future<void> pumpHotspot(
    WidgetTester tester,
    _FakeHotspotHost host, {
    String? exitCountryCode = 'JP',
  }) async {
    tester.view.physicalSize = const Size(480, 1400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(() {
      host.close();
      hotspotManager.resetForTesting(_FakeHotspotHost(supported: false));
    });

    hotspotManager.resetForTesting(host);
    container = ProviderContainer(
      overrides: [
        outboundIpProbeProvider.overrideWith(
          () => _TestOutboundIpProbe(exitCountryCode),
        ),
      ],
    );
    addTearDown(container.dispose);
    globalState.container = container;

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(locale: Locale('en'), child: HotspotView()),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('shows unsupported status when platform is not Android', (
    tester,
  ) async {
    final host = _FakeHotspotHost(supported: false);
    await pumpHotspot(tester, host);

    expect(
      find.text('VPN Hotspot is only available on Android 10+'),
      findsOneWidget,
    );
  });

  testWidgets('renders live hotspot state, interfaces, clients, and actions', (
    tester,
  ) async {
    const richState = HotspotSnapshot(
      supported: true,
      vpnUpstream: 'tun0',
      fallbackUpstream: 'wlan0',
      masqueradeMode: HotspotMasqueradeMode.simple,
      ipv6Mode: HotspotIpv6Mode.nat,
      tetherOffloadEnabled: false,
      autoStart: true,
      autoMatchCountryCode: false,
      currentCountryCode: 'US',
      repeaterSupported: true,
      repeaterActive: true,
      repeaterInterface: 'p2p-wlan0-0',
      repeaterNetworkName: 'DIRECT-FlClash',
      localOnlyActive: false,
      systemHotspotActive: true,
      usbTetheringActive: false,
      ncmSupported: true,
      ncmTetheringActive: false,
      ethernetSupported: true,
      ethernetTetheringActive: false,
      activeClientCount: 1,
      interfaces: [
        HotspotInterface(
          name: 'wlan2',
          type: 'wifi',
          managed: true,
          monitored: false,
          inactive: false,
          addresses: ['192.168.43.1/24'],
        ),
      ],
      clients: [
        HotspotClient(
          mac: 'aa:bb:cc:dd:ee:ff',
          iface: 'wlan2',
          type: 'wifi',
          nickname: 'MacBook',
          blocked: true,
          active: true,
          addresses: ['192.168.43.12'],
        ),
      ],
    );
    final host = _FakeHotspotHost(supported: true, snapshot: richState);
    await pumpHotspot(tester, host);

    expect(find.text('tun0'), findsOneWidget);
    expect(find.text('wlan0'), findsOneWidget);
    expect(find.text('US'), findsOneWidget);
    expect(find.text('Exit IP country code: JP'), findsOneWidget);

    expect(find.text('Advanced VPNHotspot'), findsNothing);

    await tester.tap(find.text('Auto-match exit IP country code'));
    await tester.pumpAndSettle();
    expect(host.calls, containsAll(['autoMatch:true', 'countryCode:JP']));

    await tester.tap(find.text('Sync exit country code now'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Reset to system default'));
    await tester.pumpAndSettle();
    expect(host.calls, contains('countryCode:null'));

    final macWired = find.text('Mac wired mode');
    await tester.ensureVisible(macWired);
    await tester.pumpAndSettle();
    await tester.tap(macWired);
    await tester.pumpAndSettle();
    expect(host.calls, contains('macWiredMode:true'));

    final usbNcm = find.text('USB NCM tethering (Mac)');
    await tester.ensureVisible(usbNcm);
    await tester.pumpAndSettle();
    await tester.tap(usbNcm);
    await tester.pumpAndSettle();
    expect(host.calls, contains('system:ncm:true'));

    await tester.drag(find.byType(Scrollable).first, const Offset(0, -600));
    await tester.pumpAndSettle();

    expect(find.text('wlan2'), findsWidgets);
    expect(find.text('MacBook'), findsOneWidget);
    expect(find.text('Blocked'), findsOneWidget);

    final allInterfaces = find.text('Select all');
    await tester.ensureVisible(allInterfaces);
    await tester.pumpAndSettle();
    await tester.tap(allInterfaces);
    await tester.pumpAndSettle();
    expect(host.calls, contains('alwaysShareAll:true'));

    final reconnect = find.text('Auto-enable on reconnect').last;
    await tester.ensureVisible(reconnect);
    await tester.pumpAndSettle();
    await tester.tap(reconnect);
    await tester.pumpAndSettle();
    expect(host.calls, contains('monitored:wlan2:true'));

    await tester.scrollUntilVisible(find.text('Clean routing rules'), 500);
    await tester.pumpAndSettle();

    await tester.tap(find.text('Clean routing rules'));
    await tester.pumpAndSettle();
    expect(host.calls, contains('cleanRouting'));

    await tester.tap(find.text('System tethering settings'));
    await tester.pumpAndSettle();
    expect(host.calls, contains('openSystemTetheringSettings'));
  });

  testWidgets('surfaces platform errors via banner without getting stuck', (
    tester,
  ) async {
    final host = _FakeHotspotHost(
      supported: true,
      snapshot: const HotspotSnapshot(supported: true),
    );
    await pumpHotspot(tester, host);

    host.nextError = PlatformException(
      code: 'HOTSPOT_ERROR',
      message: 'Root shell unavailable',
    );
    await tester.tap(find.text('Reset to system default'));
    await tester.pump();
    await tester.pump();

    expect(find.text('Root shell unavailable'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 10));
  });
}
