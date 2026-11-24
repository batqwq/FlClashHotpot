import 'dart:async';

import 'package:fl_clash/common/common.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

enum HotspotMasqueradeMode {
  none,
  simple,
  netd;

  static HotspotMasqueradeMode fromWire(String? value) {
    return switch (value) {
      'none' => HotspotMasqueradeMode.none,
      'netd' => HotspotMasqueradeMode.netd,
      _ => HotspotMasqueradeMode.simple,
    };
  }
}

enum HotspotIpv6Mode {
  system,
  block,
  nat;

  static HotspotIpv6Mode fromWire(String? value) {
    return switch (value) {
      'system' => HotspotIpv6Mode.system,
      'nat' => HotspotIpv6Mode.nat,
      _ => HotspotIpv6Mode.block,
    };
  }
}

const _invalidCountryCodes = {'00', 'XX', 'ZZ', 'T1', 'EU'};

String? normalizeHotspotCountryCode(String? raw) {
  final code = raw?.trim().toUpperCase();
  if (code == null || code.length != 2) {
    return null;
  }
  for (var i = 0; i < 2; i++) {
    final unit = code.codeUnitAt(i);
    if (unit < 65 || unit > 90) {
      return null;
    }
  }
  if (_invalidCountryCodes.contains(code)) {
    return null;
  }
  return code;
}

@immutable
class HotspotInterface {
  const HotspotInterface({
    required this.name,
    required this.type,
    required this.managed,
    required this.monitored,
    required this.inactive,
    this.addresses = const [],
  });

  factory HotspotInterface.fromMap(Map<Object?, Object?> map) {
    return HotspotInterface(
      name: (map['name'] as String?) ?? '',
      type: (map['type'] as String?) ?? 'other',
      managed: map['managed'] == true,
      monitored: map['monitored'] == true,
      inactive: map['inactive'] == true,
      addresses: _stringList(map['addresses']),
    );
  }

  final String name;
  final String type;
  final bool managed;
  final bool monitored;
  final bool inactive;
  final List<String> addresses;

  @override
  bool operator ==(Object other) =>
      other is HotspotInterface &&
      other.name == name &&
      other.type == type &&
      other.managed == managed &&
      other.monitored == monitored &&
      other.inactive == inactive &&
      listEquals(other.addresses, addresses);

  @override
  int get hashCode => Object.hash(
    name,
    type,
    managed,
    monitored,
    inactive,
    Object.hashAll(addresses),
  );
}

@immutable
class HotspotClient {
  const HotspotClient({
    required this.mac,
    required this.type,
    this.iface,
    this.nickname = '',
    this.blocked = false,
    this.active = false,
    this.addresses = const [],
  });

  factory HotspotClient.fromMap(Map<Object?, Object?> map) {
    return HotspotClient(
      mac: (map['mac'] as String?) ?? '',
      iface: map['iface'] as String?,
      type: (map['type'] as String?) ?? 'other',
      nickname: (map['nickname'] as String?) ?? '',
      blocked: map['blocked'] == true,
      active: map['active'] == true,
      addresses: _stringList(map['addresses']),
    );
  }

  final String mac;
  final String? iface;
  final String type;
  final String nickname;
  final bool blocked;
  final bool active;
  final List<String> addresses;

  String get displayName => nickname.isNotEmpty ? nickname : mac;

  @override
  bool operator ==(Object other) =>
      other is HotspotClient &&
      other.mac == mac &&
      other.iface == iface &&
      other.type == type &&
      other.nickname == nickname &&
      other.blocked == blocked &&
      other.active == active &&
      listEquals(other.addresses, addresses);

  @override
  int get hashCode => Object.hash(
    mac,
    iface,
    type,
    nickname,
    blocked,
    active,
    Object.hashAll(addresses),
  );
}

@immutable
class HotspotSnapshot {
  const HotspotSnapshot({
    this.supported = false,
    this.vpnUpstream,
    this.fallbackUpstream,
    this.primaryPreference = '',
    this.fallbackPreference = '',
    this.masqueradeMode = HotspotMasqueradeMode.simple,
    this.ipv6Mode = HotspotIpv6Mode.block,
    this.tetherOffloadEnabled = false,
    this.autoStart = false,
    this.autoMatchCountryCode = false,
    this.currentCountryCode,
    this.appliedCountryCode,
    this.repeaterSupported = false,
    this.repeaterActive = false,
    this.repeaterInterface,
    this.repeaterNetworkName,
    this.localOnlyActive = false,
    this.localOnlyInterface,
    this.localOnlySsid,
    this.systemHotspotActive = false,
    this.usbTetheringActive = false,
    this.ethernetTetheringActive = false,
    this.ethernetSupported = false,
    this.interfaces = const [],
    this.clients = const [],
    this.activeClientCount = 0,
  });

  factory HotspotSnapshot.fromMap(Map<Object?, Object?> map) {
    final rawInterfaces = map['interfaces'];
    final interfaces = rawInterfaces is List
        ? rawInterfaces
              .whereType<Map<Object?, Object?>>()
              .map(HotspotInterface.fromMap)
              .toList()
        : const <HotspotInterface>[];
    final rawClients = map['clients'];
    final clients = rawClients is List
        ? rawClients
              .whereType<Map<Object?, Object?>>()
              .map(HotspotClient.fromMap)
              .toList()
        : const <HotspotClient>[];
    return HotspotSnapshot(
      supported: map['supported'] == true,
      vpnUpstream: map['vpnUpstream'] as String?,
      fallbackUpstream: map['fallbackUpstream'] as String?,
      primaryPreference: (map['primaryPreference'] as String?) ?? '',
      fallbackPreference: (map['fallbackPreference'] as String?) ?? '',
      masqueradeMode: HotspotMasqueradeMode.fromWire(
        map['masqueradeMode'] as String?,
      ),
      ipv6Mode: HotspotIpv6Mode.fromWire(map['ipv6Mode'] as String?),
      tetherOffloadEnabled: map['tetherOffloadEnabled'] == true,
      autoStart: map['autoStart'] == true,
      autoMatchCountryCode: map['autoMatchCountryCode'] == true,
      currentCountryCode: normalizeHotspotCountryCode(
        map['currentCountryCode'] as String?,
      ),
      appliedCountryCode: normalizeHotspotCountryCode(
        map['appliedCountryCode'] as String?,
      ),
      repeaterSupported: map['repeaterSupported'] == true,
      repeaterActive: map['repeaterActive'] == true,
      repeaterInterface: map['repeaterInterface'] as String?,
      repeaterNetworkName: map['repeaterNetworkName'] as String?,
      localOnlyActive: map['localOnlyActive'] == true,
      localOnlyInterface: map['localOnlyInterface'] as String?,
      localOnlySsid: map['localOnlySsid'] as String?,
      systemHotspotActive: map['systemHotspotActive'] == true,
      usbTetheringActive: map['usbTetheringActive'] == true,
      ethernetTetheringActive: map['ethernetTetheringActive'] == true,
      ethernetSupported: map['ethernetSupported'] == true,
      interfaces: interfaces,
      clients: clients,
      activeClientCount: (map['activeClientCount'] as num?)?.toInt() ?? 0,
    );
  }

  final bool supported;
  final String? vpnUpstream;
  final String? fallbackUpstream;
  final String primaryPreference;
  final String fallbackPreference;
  final HotspotMasqueradeMode masqueradeMode;
  final HotspotIpv6Mode ipv6Mode;
  final bool tetherOffloadEnabled;
  final bool autoStart;
  final bool autoMatchCountryCode;
  final String? currentCountryCode;
  final String? appliedCountryCode;
  final bool repeaterSupported;
  final bool repeaterActive;
  final String? repeaterInterface;
  final String? repeaterNetworkName;
  final bool localOnlyActive;
  final String? localOnlyInterface;
  final String? localOnlySsid;
  final bool systemHotspotActive;
  final bool usbTetheringActive;
  final bool ethernetTetheringActive;
  final bool ethernetSupported;
  final List<HotspotInterface> interfaces;
  final List<HotspotClient> clients;
  final int activeClientCount;

  @override
  bool operator ==(Object other) =>
      other is HotspotSnapshot &&
      other.supported == supported &&
      other.vpnUpstream == vpnUpstream &&
      other.fallbackUpstream == fallbackUpstream &&
      other.primaryPreference == primaryPreference &&
      other.fallbackPreference == fallbackPreference &&
      other.masqueradeMode == masqueradeMode &&
      other.ipv6Mode == ipv6Mode &&
      other.tetherOffloadEnabled == tetherOffloadEnabled &&
      other.autoStart == autoStart &&
      other.autoMatchCountryCode == autoMatchCountryCode &&
      other.currentCountryCode == currentCountryCode &&
      other.appliedCountryCode == appliedCountryCode &&
      other.repeaterSupported == repeaterSupported &&
      other.repeaterActive == repeaterActive &&
      other.repeaterInterface == repeaterInterface &&
      other.repeaterNetworkName == repeaterNetworkName &&
      other.localOnlyActive == localOnlyActive &&
      other.localOnlyInterface == localOnlyInterface &&
      other.localOnlySsid == localOnlySsid &&
      other.systemHotspotActive == systemHotspotActive &&
      other.usbTetheringActive == usbTetheringActive &&
      other.ethernetTetheringActive == ethernetTetheringActive &&
      other.ethernetSupported == ethernetSupported &&
      listEquals(other.interfaces, interfaces) &&
      listEquals(other.clients, clients) &&
      other.activeClientCount == activeClientCount;

  @override
  int get hashCode => Object.hashAll([
    supported,
    vpnUpstream,
    fallbackUpstream,
    primaryPreference,
    fallbackPreference,
    masqueradeMode,
    ipv6Mode,
    tetherOffloadEnabled,
    autoStart,
    autoMatchCountryCode,
    currentCountryCode,
    appliedCountryCode,
    repeaterSupported,
    repeaterActive,
    repeaterInterface,
    repeaterNetworkName,
    localOnlyActive,
    localOnlyInterface,
    localOnlySsid,
    systemHotspotActive,
    usbTetheringActive,
    ethernetTetheringActive,
    ethernetSupported,
    Object.hashAll(interfaces),
    Object.hashAll(clients),
    activeClientCount,
  ]);
}

List<String> _stringList(Object? raw) {
  if (raw is! List) {
    return const [];
  }
  return [
    for (final item in raw)
      if (item is String) item,
  ];
}

abstract interface class HotspotHost {
  bool get isSupported;

  Future<HotspotSnapshot> getState();

  Stream<HotspotSnapshot> watchState();

  Stream<bool> watchAutoMatchCountryCode();

  Future<void> setInterfaceManaged(String iface, bool enabled);

  Future<void> setInterfaceMonitored(String iface, bool enabled);

  Future<void> setRepeaterEnabled(bool enabled);

  Future<void> setLocalOnlyHotspotEnabled(bool enabled);

  Future<bool> setSystemTetheringEnabled(String type, bool enabled);

  Future<void> setMasqueradeMode(HotspotMasqueradeMode mode);

  Future<void> setIpv6Mode(HotspotIpv6Mode mode);

  Future<void> setTetherOffloadEnabled(bool enabled);

  Future<void> setAutoStartEnabled(bool enabled);

  Future<void> setAutoMatchCountryCode(bool enabled);

  Future<String?> setCountryCode(String? countryCode);

  Future<void> cleanRouting();

  Future<void> openNativeUi();

  Future<void> openSystemTetheringSettings();
}

class AndroidHotspotHost implements HotspotHost {
  const AndroidHotspotHost([
    this._methodChannel = const MethodChannel('$packageName/hotspot'),
    this._stateEventChannel = const EventChannel('$packageName/hotspot/state'),
    this._autoMatchEventChannel = const EventChannel(
      '$packageName/hotspot/auto_match_country_code',
    ),
  ]);

  final MethodChannel _methodChannel;
  final EventChannel _stateEventChannel;
  final EventChannel _autoMatchEventChannel;

  @override
  bool get isSupported => true;

  @override
  Future<HotspotSnapshot> getState() async {
    final result = await _methodChannel.invokeMethod<Map<Object?, Object?>>(
      'getState',
    );
    return result == null
        ? const HotspotSnapshot(supported: true)
        : HotspotSnapshot.fromMap(result);
  }

  @override
  Stream<HotspotSnapshot> watchState() {
    return _stateEventChannel
        .receiveBroadcastStream()
        .where((event) => event is Map<Object?, Object?>)
        .map(
          (event) => HotspotSnapshot.fromMap(event as Map<Object?, Object?>),
        );
  }

  @override
  Stream<bool> watchAutoMatchCountryCode() {
    return _autoMatchEventChannel
        .receiveBroadcastStream()
        .map((event) => event == true);
  }

  @override
  Future<void> setInterfaceManaged(String iface, bool enabled) {
    return _methodChannel.invokeMethod<void>('setInterfaceManaged', {
      'iface': iface,
      'enabled': enabled,
    });
  }

  @override
  Future<void> setInterfaceMonitored(String iface, bool enabled) {
    return _methodChannel.invokeMethod<void>('setInterfaceMonitored', {
      'iface': iface,
      'enabled': enabled,
    });
  }

  @override
  Future<void> setRepeaterEnabled(bool enabled) {
    return _methodChannel.invokeMethod<void>('setRepeaterEnabled', {
      'enabled': enabled,
    });
  }

  @override
  Future<void> setLocalOnlyHotspotEnabled(bool enabled) {
    return _methodChannel.invokeMethod<void>('setLocalOnlyHotspotEnabled', {
      'enabled': enabled,
    });
  }

  @override
  Future<bool> setSystemTetheringEnabled(String type, bool enabled) async {
    return await _methodChannel.invokeMethod<bool>(
          'setSystemTetheringEnabled',
          {'type': type, 'enabled': enabled},
        ) ??
        false;
  }

  @override
  Future<void> setMasqueradeMode(HotspotMasqueradeMode mode) {
    return _methodChannel.invokeMethod<void>('setMasqueradeMode', {
      'mode': mode.name,
    });
  }

  @override
  Future<void> setIpv6Mode(HotspotIpv6Mode mode) {
    return _methodChannel.invokeMethod<void>('setIpv6Mode', {
      'mode': mode.name,
    });
  }

  @override
  Future<void> setTetherOffloadEnabled(bool enabled) {
    return _methodChannel.invokeMethod<void>('setTetherOffloadEnabled', {
      'enabled': enabled,
    });
  }

  @override
  Future<void> setAutoStartEnabled(bool enabled) {
    return _methodChannel.invokeMethod<void>('setAutoStartEnabled', {
      'enabled': enabled,
    });
  }

  @override
  Future<void> setAutoMatchCountryCode(bool enabled) {
    return _methodChannel.invokeMethod<void>('setAutoMatchCountryCode', {
      'enabled': enabled,
    });
  }

  @override
  Future<String?> setCountryCode(String? countryCode) async {
    final normalized = normalizeHotspotCountryCode(countryCode);
    final result = await _methodChannel.invokeMethod<String>('setCountryCode', {
      'countryCode': normalized,
    });
    return normalizeHotspotCountryCode(result);
  }

  @override
  Future<void> cleanRouting() {
    return _methodChannel.invokeMethod<void>('cleanRouting');
  }

  @override
  Future<void> openNativeUi() {
    return _methodChannel.invokeMethod<void>('openNativeUi');
  }

  @override
  Future<void> openSystemTetheringSettings() {
    return _methodChannel.invokeMethod<void>('openSystemTetheringSettings');
  }
}

class _UnsupportedHotspotHost implements HotspotHost {
  const _UnsupportedHotspotHost();

  @override
  bool get isSupported => false;

  @override
  Future<HotspotSnapshot> getState() async => const HotspotSnapshot();

  @override
  Stream<HotspotSnapshot> watchState() => const Stream<HotspotSnapshot>.empty();

  @override
  Stream<bool> watchAutoMatchCountryCode() => const Stream<bool>.empty();

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
  Future<String?> setCountryCode(String? countryCode) async => null;

  @override
  Future<void> cleanRouting() async {}

  @override
  Future<void> openNativeUi() async {}

  @override
  Future<void> openSystemTetheringSettings() async {}
}

class HotspotManager {
  HotspotManager._();

  static final instance = HotspotManager._();

  HotspotHost _host = system.isAndroid
      ? const AndroidHotspotHost()
      : const _UnsupportedHotspotHost();

  @visibleForTesting
  void resetForTesting(HotspotHost host) {
    _host = host;
  }

  bool get isSupported => _host.isSupported;

  Future<HotspotSnapshot> getState() => _host.getState();

  Stream<HotspotSnapshot> watchState() => _host.watchState();

  Stream<bool> watchAutoMatchCountryCode() => _host.watchAutoMatchCountryCode();

  Future<void> setInterfaceManaged(String iface, bool enabled) =>
      _host.setInterfaceManaged(iface, enabled);

  Future<void> setInterfaceMonitored(String iface, bool enabled) =>
      _host.setInterfaceMonitored(iface, enabled);

  Future<void> setRepeaterEnabled(bool enabled) =>
      _host.setRepeaterEnabled(enabled);

  Future<void> setLocalOnlyHotspotEnabled(bool enabled) =>
      _host.setLocalOnlyHotspotEnabled(enabled);

  Future<bool> setSystemTetheringEnabled(String type, bool enabled) =>
      _host.setSystemTetheringEnabled(type, enabled);

  Future<void> setMasqueradeMode(HotspotMasqueradeMode mode) =>
      _host.setMasqueradeMode(mode);

  Future<void> setIpv6Mode(HotspotIpv6Mode mode) => _host.setIpv6Mode(mode);

  Future<void> setTetherOffloadEnabled(bool enabled) =>
      _host.setTetherOffloadEnabled(enabled);

  Future<void> setAutoStartEnabled(bool enabled) =>
      _host.setAutoStartEnabled(enabled);

  Future<void> setAutoMatchCountryCode(bool enabled) =>
      _host.setAutoMatchCountryCode(enabled);

  Future<String?> setCountryCode(String? countryCode) =>
      _host.setCountryCode(countryCode);

  Future<void> cleanRouting() => _host.cleanRouting();

  Future<void> openNativeUi() => _host.openNativeUi();

  Future<void> openSystemTetheringSettings() =>
      _host.openSystemTetheringSettings();
}

final hotspotManager = HotspotManager.instance;
