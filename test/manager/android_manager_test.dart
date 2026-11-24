import 'dart:async';
import 'dart:convert';

import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/common/service_probe.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/manager/android_manager.dart';
import 'package:fl_clash/models/models.dart';
import 'package:fl_clash/plugins/hotspot.dart';
import 'package:fl_clash/plugins/service.dart';
import 'package:fl_clash/providers/action.dart';
import 'package:fl_clash/providers/config.dart';
import 'package:fl_clash/providers/database.dart';
import 'package:fl_clash/providers/outbound_ip.dart';
import 'package:fl_clash/providers/routed_probe.dart';
import 'package:fl_clash/providers/state.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../helpers/preferences.dart';
import '../helpers/test_app.dart';
import '../helpers/test_profiles.dart';

class _RecordingSetupAction extends SetupAction {
  static final requests = <bool>[];

  @override
  Future<bool> setRunning(bool running, {bool initialize = false}) async {
    requests.add(running);
    return true;
  }
}

class _MutableOutboundProbe extends OutboundIpProbe {
  int watchCount = 0;

  @override
  RoutedProbeState<String, IpInfo> build() => const RoutedProbeState();

  @override
  void watch(String target) {
    watchCount++;
  }

  @override
  void unwatch(String target) {
    watchCount--;
  }

  void setCountry(String? code) {
    state = code == null
        ? const RoutedProbeState()
        : RoutedProbeState({
            routedOutbound: ProbeEntry(
              phase: ProbePhase.fresh,
              value: IpInfo(ip: '198.51.100.1', countryCode: code),
            ),
          });
  }
}

class _FakeCountryHotspotHost implements HotspotHost {
  _FakeCountryHotspotHost({this.supported = false});

  final bool supported;
  final appliedCodes = <String?>[];
  final autoMatchController = StreamController<bool>.broadcast();

  void close() {
    unawaited(autoMatchController.close());
  }

  @override
  bool get isSupported => supported;

  @override
  Future<HotspotSnapshot> getState() async =>
      HotspotSnapshot(supported: supported);

  @override
  Stream<HotspotSnapshot> watchState() => const Stream.empty();

  @override
  Stream<bool> watchAutoMatchCountryCode() => autoMatchController.stream;

  @override
  Future<void> setInterfaceManaged(String iface, bool enabled) async {}

  @override
  Future<void> setInterfaceMonitored(String iface, bool enabled) async {}

  @override
  Future<void> setRepeaterEnabled(bool enabled) async {}

  @override
  Future<void> setLocalOnlyHotspotEnabled(bool enabled) async {}

  @override
  Future<bool> setSystemTetheringEnabled(String type, bool enabled) async =>
      false;

  @override
  Future<void> setMasqueradeMode(HotspotMasqueradeMode mode) async {}

  @override
  Future<void> setIpv6Mode(HotspotIpv6Mode mode) async {}

  @override
  Future<void> setTetherOffloadEnabled(bool enabled) async {}

  @override
  Future<void> setAutoStartEnabled(bool enabled) async {}

  @override
  Future<void> setAutoMatchCountryCode(bool enabled) async {}

  @override
  Future<String?> setCountryCode(String? countryCode) async {
    appliedCodes.add(countryCode);
    return countryCode;
  }

  @override
  Future<void> cleanRouting() async {}

  @override
  Future<void> setAlwaysShareAll(bool enabled) async {}

  @override
  Future<void> openSystemTetheringSettings() async {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late ProviderContainer container;
  late SharedPreferences store;

  setUp(() async {
    await mockPreferences();
    store = await SharedPreferences.getInstance();
    _RecordingSetupAction.requests.clear();
    container = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(TestProfiles.new),
        setupActionProvider.overrideWith(_RecordingSetupAction.new),
        // Bypassing private addresses computes the routes in Isolate.run, which
        // the fake clock never lets finish.
        networkSettingProvider.overrideWithBuild(
          (_, _) => const NetworkProps(bypassPrivateRoute: false),
        ),
      ],
    );
    globalState.container = container;
  });

  tearDown(() => container.dispose());

  Future<void> pumpAndroidManager(WidgetTester tester) async {
    await container.read(vpnOptionsProvider.future);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const TestApp(
          child: AndroidManager(child: SizedBox(key: Key('child'))),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('renders its child unchanged', (tester) async {
    await pumpAndroidManager(tester);

    expect(find.byKey(const Key('child')), findsOneWidget);
    expect(tester.takeException(), null);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a stop the service reports turns the running state off', (
    tester,
  ) async {
    await pumpAndroidManager(tester);
    final listener =
        tester.state(find.byType(AndroidManager)) as ServiceListener;

    listener.onServiceStopped();
    await tester.pump();

    expect(_RecordingSetupAction.requests, [false]);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('a start the service gave up says why it turned off', (
    tester,
  ) async {
    loadAppLocalizations(const Locale('en'));
    await pumpAndroidManager(tester);
    final listener =
        tester.state(find.byType(AndroidManager)) as ServiceListener;

    listener.onServiceStopped(ServiceStartFailure.vpnPermission);
    await tester.pump();
    await tester.pump();

    expect(_RecordingSetupAction.requests, [false]);
    expect(
      find.text(currentAppLocalizations.vpnPermissionNotGranted),
      findsOneWidget,
    );

    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 10));
  });

  testWidgets('persists shared state after the debounce window', (
    tester,
  ) async {
    await pumpAndroidManager(tester);
    expect(store.getString('sharedState'), isNull);

    container.read(patchClashConfigProvider.notifier).value =
        const PatchClashConfig(testUrl: 'http://shared.test');
    await tester.pump();
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpAndSettle();

    final raw = store.getString('sharedState');
    expect(raw, isNotNull);
    final restored = SharedState.fromJson(
      json.decode(raw!) as Map<String, Object?>,
    );
    expect(restored.onlyStatisticsProxy, isFalse);
    expect(tester.takeException(), null);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('does not persist when shared state is unchanged', (
    tester,
  ) async {
    await pumpAndroidManager(tester);

    container.read(appSettingProvider.notifier).value = const AppSettingProps();
    await tester.pump();
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpAndSettle();

    expect(store.getString('sharedState'), isNull);

    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('syncs hotspot country code when exit IP country changes', (
    tester,
  ) async {
    final fakeHost = _FakeCountryHotspotHost(supported: true);
    final probe = _MutableOutboundProbe();
    addTearDown(() {
      fakeHost.close();
      hotspotManager.resetForTesting(_FakeCountryHotspotHost(supported: false));
    });
    hotspotManager.resetForTesting(fakeHost);

    final localContainer = ProviderContainer(
      overrides: [
        profilesProvider.overrideWith(TestProfiles.new),
        setupActionProvider.overrideWith(_RecordingSetupAction.new),
        outboundIpProbeProvider.overrideWith(() => probe),
        networkSettingProvider.overrideWithBuild(
          (_, _) => const NetworkProps(bypassPrivateRoute: false),
        ),
      ],
    );
    addTearDown(localContainer.dispose);
    globalState.container = localContainer;
    await localContainer.read(vpnOptionsProvider.future);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: localContainer,
        child: const TestApp(
          child: AndroidManager(child: SizedBox(key: Key('child'))),
        ),
      ),
    );
    await tester.pumpAndSettle();

    fakeHost.autoMatchController.add(true);
    await tester.pump();
    expect(probe.watchCount, 1);

    probe.setCountry('jp');
    await tester.pump();
    expect(fakeHost.appliedCodes, ['JP']);

    probe.setCountry('JP');
    await tester.pump();
    expect(fakeHost.appliedCodes, ['JP']);

    probe.setCountry('us');
    await tester.pump();
    expect(fakeHost.appliedCodes, ['JP', 'US']);

    fakeHost.autoMatchController.add(false);
    await tester.pump();
    expect(probe.watchCount, 0);

    probe.setCountry('de');
    await tester.pump();
    expect(fakeHost.appliedCodes, ['JP', 'US']);

    await tester.pumpWidget(const SizedBox.shrink());
  });
}
