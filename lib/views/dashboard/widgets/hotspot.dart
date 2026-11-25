import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/ui/icons/icons.dart';
import 'package:fl_clash/views/hotspot.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter/widgets.dart';

import 'card.dart';

class HotspotCard extends StatelessWidget {
  const HotspotCard({super.key});

  @override
  Widget build(BuildContext context) {
    final localizations = context.appLocalizations;
    final color = context.colorScheme.onSurfaceVariant;
    return DashboardCard(
      onTap: () => pushNestedPage<void>(context, const HotspotView()),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: 6,
        children: [
          DashboardCardHeader(
            glyph: AppGlyphs.wifi,
            title: localizations.vpnHotspot,
            chevron: true,
          ),
          Text(
            localizations.vpnHotspotDesc,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: AppTextStyle.footnote.of(context).copyWith(color: color),
          ),
        ],
      ),
    );
  }
}
