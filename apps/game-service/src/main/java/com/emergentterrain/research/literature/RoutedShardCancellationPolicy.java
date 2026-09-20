package com.emergentterrain.research.literature;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative Phase 45 early-cancellation rule for routed primary shards.
 *
 * <p>It may stop only a suffix of the deterministic shard order after every earlier shard has
 * completed and those completed outputs already provide all three answer slots.  Therefore a
 * cancelled shard cannot alter emitted key order or replace an evidence line.
 */
final class RoutedShardCancellationPolicy {
    private static final int MAX_RETURNED_KEYS = 3;
    private static final Pattern KEY_LINE = Pattern.compile("(?m)^- \\[?(HF[0-9A-F]+)\\]?.*$",
            Pattern.CASE_INSENSITIVE);

    private RoutedShardCancellationPolicy() {}

    static boolean completedPrefixFillsAnswer(List<String> orderedShardNames, Map<String, String> completedOutputs) {
        Set<String> distinctKeys = new LinkedHashSet<>();
        for (String shardName : orderedShardNames) {
            String output = completedOutputs.get(shardName);
            if (output == null) return false;
            Matcher matcher = KEY_LINE.matcher(output);
            while (matcher.find()) distinctKeys.add(matcher.group(1).toUpperCase(java.util.Locale.ROOT));
            if (distinctKeys.size() >= MAX_RETURNED_KEYS) return true;
        }
        return false;
    }
}
