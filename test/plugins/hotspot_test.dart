import 'dart:async';

import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/plugins/hotspot.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeHotspotHost implements HotspotHost {
  _FakeHotspotHost({this.supported = true});

  final bool supported;
  final calls = <String>[];
  HotspotSnapshot state = const HotspotSnapshot(supported: true);
  final stateController = StreamController<HotspotSnapshot>.broadcast();
  final autoMatchController = StreamController<bool>.broadcast();

  void close() {
    unawaited(stateController.close());
    unawaited(autoMatchController.close());
  }

  @override
  bool get isSupported => supported;

  @override
  Future<HotspotSnapshot> getState() async {
    calls.add('getState');
    return state;
  }

  @override
  Stream<HotspotSnapshot> watchState() => stateController.stream;

  @override
  Stream<bool> watchAutoMatchCountryCode() => autoMatchController.stream;

  @override
  Future<void> setInterfaceManaged(String iface, bool enabled) async {
    calls.add('setInterfaceManaged:$iface:$enabled');
  }

  @override
  Future<void> setInterfaceMonitored(String iface, bool enabled) async {
    calls.add('setInterfaceMonitored:$iface:$enabled');
  }

  @override
  Future<void> setRepeaterEnabled(bool enabled) async {
    calls.add('setRepeaterEnabled:$enabled');
  }

  @override
  Future<void> setLocalOnlyHotspotEnabled(bool enabled) async {
    calls.add('setLocalOnlyHotspotEnabled:$enabled');
  }

  @override
  Future<bool> setSystemTetheringEnabled(String type, bool enabled) async {
    calls.add('setSystemTetheringEnabled:$type:$enabled');
    return true;
  }

  @override
  Future<void> setMasqueradeMode(HotspotMasqueradeMode mode) async {
    calls.add('setMasqueradeMode:${mode.name}');
  }

  @override
  Future<void> setIpv6Mode(HotspotIpv6Mode mode) async {
    calls.add('setIpv6Mode:${mode.name}');
  }

  @override
  Future<void> setTetherOffloadEnabled(bool enabled) async {
    calls.add('setTetherOffloadEnabled:$enabled');
  }

  @override
  Future<void> setAutoStartEnabled(bool enabled) async {
    calls.add('setAutoStartEnabled:$enabled');
  }

  @override
  Future<void> setAutoMatchCountryCode(bool enabled) async {
    calls.add('setAutoMatchCountryCode:$enabled');
  }

  @override
  Future<String?> setCountryCode(String? countryCode) async {
    calls.add('setCountryCode:$countryCode');
    return normalizeHotspotCountryCode(countryCode);
  }

  @override
  Future<void> cleanRouting() async {
    calls.add('cleanRouting');
  }

  @override
  Future<void> setAlwaysShareAll(bool enabled) async {
    calls.add('setAlwaysShareAll:$enabled');
  }

  @override
  Future<void> openSystemTetheringSettings() async {
    calls.add('openSystemTetheringSettings');
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const methodChannel = MethodChannel('$packageName/hotspot');
  const stateEventChannel = EventChannel('$packageName/hotspot/state');
  const autoMatchEventChannel = EventChannel(
    '$packageName/hotspot/auto_match_country_code',
  );
  const host = AndroidHotspotHost();

  tearDown(() async {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(methodChannel, null);
    messenger.setMockStreamHandler(stateEventChannel, null);
    messenger.setMockStreamHandler(autoMatchEventChannel, null);
    hotspotManager.resetForTesting(_FakeHotspotHost(supported: false));
  });

  test('normalizeHotspotCountryCode validates ISO alpha-2 codes', () {
    expect(normalizeHotspotCountryCode('jp'), 'JP');
    expect(normalizeHotspotCountryCode(' us '), 'US');
    expect(normalizeHotspotCountryCode(null), isNull);
    expect(normalizeHotspotCountryCode(''), isNull);
    expect(normalizeHotspotCountryCode('00'), isNull);
    expect(normalizeHotspotCountryCode('XX'), isNull);
    expect(normalizeHotspotCountryCode('ZZ'), isNull);
    expect(normalizeHotspotCountryCode('T1'), isNull);
    expect(normalizeHotspotCountryCode('EU'), isNull);
    expect(normalizeHotspotCountryCode('USA'), isNull);
    expect(normalizeHotspotCountryCode('1A'), isNull);
  });

  test('models parse wire maps and implement value equality', () {
    const wire = <Object?, Object?>{
      'supported': true,
      'vpnUpstream': 'tun0',
      'fallbackUpstream': 'wlan0',
      'primaryPreference': '',
      'fallbackPreference': 'rmnet0',
      'masqueradeMode': 'netd',
      'ipv6Mode': 'nat',
      'tetherOffloadEnabled': true,
      'autoStart': true,
      'autoMatchCountryCode': true,
      'currentCountryCode': 'jp',
      'appliedCountryCode': 'jp',
      'repeaterSupported': true,
      'repeaterActive': true,
      'repeaterInterface': 'p2p-wlan0-0',
      'repeaterNetworkName': 'DIRECT-AB',
      'localOnlyActive': true,
      'localOnlyInterface': 'ap0',
      'localOnlySsid': 'TempAP',
      'systemHotspotActive': true,
      'usbTetheringActive': true,
      'ethernetTetheringActive': true,
      'ethernetSupported': true,
      'activeClientCount': 1,
      'interfaces': [
        {
          'name': 'wlan2',
          'type': 'wifi',
          'managed': true,
          'monitored': true,
          'inactive': false,
          'addresses': ['192.168.43.1/24'],
        },
      ],
      'clients': [
        {
          'mac': 'aa:bb:cc:dd:ee:ff',
          'iface': 'wlan2',
          'type': 'wifi',
          'nickname': 'Laptop',
          'blocked': false,
          'active': true,
          'addresses': ['192.168.43.10'],
        },
      ],
    };

    final a = HotspotSnapshot.fromMap(wire);
    final b = HotspotSnapshot.fromMap(wire);
    expect(a, b);
    expect(a.hashCode, b.hashCode);
    expect(a.masqueradeMode, HotspotMasqueradeMode.netd);
    expect(a.ipv6Mode, HotspotIpv6Mode.nat);
    expect(a.currentCountryCode, 'JP');
    expect(a.appliedCountryCode, 'JP');
    expect(a.interfaces.single.addresses, ['192.168.43.1/24']);
    expect(a.clients.single.displayName, 'Laptop');
    expect(
      const HotspotClient(mac: '11:22:33:44:55:66', type: 'wifi').displayName,
      '11:22:33:44:55:66',
    );
    expect(HotspotMasqueradeMode.fromWire('none'), HotspotMasqueradeMode.none);
    expect(
      HotspotMasqueradeMode.fromWire('other'),
      HotspotMasqueradeMode.simple,
    );
    expect(HotspotIpv6Mode.fromWire('system'), HotspotIpv6Mode.system);
    expect(HotspotIpv6Mode.fromWire('other'), HotspotIpv6Mode.block);
  });

  test('AndroidHotspotHost invokes every method channel contract', () async {
    final recorded = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          recorded.add(call);
          return switch (call.method) {
            'getState' => <Object?, Object?>{
              'supported': true,
              'vpnUpstream': 'tun0',
              'alwaysShareAll': true,
            },
            'setSystemTetheringEnabled' => true,
            'setCountryCode' => 'US',
            _ => null,
          };
        });

    expect(host.isSupported, isTrue);
    final state = await host.getState();
    expect(state.vpnUpstream, 'tun0');
    expect(state.alwaysShareAll, isTrue);
    await host.setInterfaceManaged('wlan2', true);
    await host.setInterfaceMonitored('wlan2', true);
    await host.setRepeaterEnabled(true);
    await host.setLocalOnlyHotspotEnabled(false);
    expect(await host.setSystemTetheringEnabled('wifi', true), isTrue);
    await host.setMasqueradeMode(HotspotMasqueradeMode.netd);
    await host.setIpv6Mode(HotspotIpv6Mode.nat);
    await host.setTetherOffloadEnabled(false);
    await host.setAutoStartEnabled(true);
    await host.setAutoMatchCountryCode(true);
    expect(await host.setCountryCode('us'), 'US');
    await host.cleanRouting();
    await host.setAlwaysShareAll(true);
    await host.openSystemTetheringSettings();

    expect(recorded.map((c) => c.method), [
      'getState',
      'setInterfaceManaged',
      'setInterfaceMonitored',
      'setRepeaterEnabled',
      'setLocalOnlyHotspotEnabled',
      'setSystemTetheringEnabled',
      'setMasqueradeMode',
      'setIpv6Mode',
      'setTetherOffloadEnabled',
      'setAutoStartEnabled',
      'setAutoMatchCountryCode',
      'setCountryCode',
      'cleanRouting',
      'setAlwaysShareAll',
      'openSystemTetheringSettings',
    ]);
    expect(recorded[recorded.length - 2].arguments, {'enabled': true});
  });

  test('AndroidHotspotHost streams state and auto-match updates', () async {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockStreamHandler(
      stateEventChannel,
      MockStreamHandler.inline(
        onListen: (_, sink) {
          sink.success(<Object?, Object?>{
            'supported': true,
            'vpnUpstream': 'tun0',
            'currentCountryCode': 'SG',
          });
          sink.endOfStream();
        },
      ),
    );
    messenger.setMockStreamHandler(
      autoMatchEventChannel,
      MockStreamHandler.inline(
        onListen: (_, sink) {
          sink.success(true);
          sink.endOfStream();
        },
      ),
    );

    final snapshot = await host.watchState().first;
    expect(snapshot.vpnUpstream, 'tun0');
    expect(snapshot.currentCountryCode, 'SG');
    expect(await host.watchAutoMatchCountryCode().first, isTrue);
  });

  test('HotspotManager forwards every operation to its host', () async {
    final fake = _FakeHotspotHost();
    addTearDown(fake.close);
    hotspotManager.resetForTesting(fake);

    expect(hotspotManager.isSupported, isTrue);
    await hotspotManager.getState();
    await hotspotManager.setInterfaceManaged('ap0', true);
    await hotspotManager.setInterfaceMonitored('ap0', false);
    await hotspotManager.setRepeaterEnabled(true);
    await hotspotManager.setLocalOnlyHotspotEnabled(true);
    await hotspotManager.setSystemTetheringEnabled('usb', true);
    await hotspotManager.setMasqueradeMode(HotspotMasqueradeMode.none);
    await hotspotManager.setIpv6Mode(HotspotIpv6Mode.system);
    await hotspotManager.setTetherOffloadEnabled(true);
    await hotspotManager.setAutoStartEnabled(false);
    await hotspotManager.setAutoMatchCountryCode(true);
    expect(await hotspotManager.setCountryCode('jp'), 'JP');
    await hotspotManager.cleanRouting();
    await hotspotManager.setAlwaysShareAll(false);
    await hotspotManager.openSystemTetheringSettings();

    expect(fake.calls, [
      'getState',
      'setInterfaceManaged:ap0:true',
      'setInterfaceMonitored:ap0:false',
      'setRepeaterEnabled:true',
      'setLocalOnlyHotspotEnabled:true',
      'setSystemTetheringEnabled:usb:true',
      'setMasqueradeMode:none',
      'setIpv6Mode:system',
      'setTetherOffloadEnabled:true',
      'setAutoStartEnabled:false',
      'setAutoMatchCountryCode:true',
      'setCountryCode:jp',
      'cleanRouting',
      'setAlwaysShareAll:false',
      'openSystemTetheringSettings',
    ]);
  });
}
