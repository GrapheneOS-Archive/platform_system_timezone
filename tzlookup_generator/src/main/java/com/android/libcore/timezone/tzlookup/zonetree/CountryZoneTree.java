/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.libcore.timezone.tzlookup.zonetree;

import com.android.libcore.timezone.tzlookup.proto.CountryZonesFile;
import com.android.libcore.timezone.tzlookup.proto.CountryZonesFile.Country;
import com.android.libcore.timezone.tzlookup.zonetree.ZoneOffsetPeriod.ZonePeriodsKey;
import com.ibm.icu.text.TimeZoneNames;
import com.ibm.icu.util.BasicTimeZone;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;

import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;

/**
 * A tree that holds all the zones for a country and records how they relate to each other over
 * time.
 */
public final class CountryZoneTree {

    /** A Visitor for visiting {@link ZoneNode} trees. */
    private interface ZoneNodeVisitor extends TreeNode.Visitor<ZoneNode> {}

    /** A specialist node for zone trees. */
    private static class ZoneNode extends TreeNode<ZoneNode> {

        private final int periodOffset;
        private final List<ZoneInfo> zoneInfos;

        private int periodCount;
        private ZoneInfo primaryZoneInfo;
        private boolean priorityClash;

        ZoneNode(String id, List<ZoneInfo> zoneInfos, int periodOffset, int periodCount) {
            super(id);
            this.periodOffset = periodOffset;
            this.zoneInfos = zoneInfos;
            this.periodCount = periodCount;
            initNodePriority();
        }

        private void initNodePriority() {
            ZoneInfo priorityCandidate = null;
            int priorityCount = 0;
            for (ZoneInfo zoneInfo : zoneInfos) {
                if (priorityCandidate == null
                        || priorityCandidate.getPriority() < zoneInfo.getPriority()) {
                    priorityCandidate = zoneInfo;
                    priorityCount = 1;
                } else if (priorityCandidate.getPriority() == zoneInfo.getPriority()) {
                    priorityCount++;
                }
            }
            primaryZoneInfo = priorityCandidate;
            // If more than one ZoneInfo has the same priority as the primaryZoneInfo then we
            // can't know which one is actually the primary.
            priorityClash = priorityCount > 1;
        }

        ZoneInfo getPrimaryZoneInfo() {
            if (priorityClash) {
                throw new IllegalStateException("No primary zone for " + getId()
                        + ": priority clash (between" + getZoneInfosString() + ")");
            }
            return primaryZoneInfo;
        }

        /** {@code true} if multiple zones have the same priority. */
        boolean hasPriorityClash() {
            return priorityClash;
        }

        List<ZoneInfo> getZoneInfos() {
            return zoneInfos;
        }

        String getZoneInfosString() {
            return zoneInfos.stream()
                    .map(z -> z.getZoneId() + "(" + z.getPriority() + ")")
                    .collect(toList()).toString();
        }

        Instant getStartInstant() {
            int offset = periodOffset + periodCount - 1;
            int index = primaryZoneInfo.getZoneOffsetPeriodCount() - offset;
            return primaryZoneInfo.getZoneOffsetPeriod(index).getStartInstant();
        }

        Instant getEndInstant() {
            int index = primaryZoneInfo.getZoneOffsetPeriodCount() - periodOffset;
            return primaryZoneInfo.getZoneOffsetPeriod(index).getEndInstant();
        }

        void adjustPeriodCount(int adjustment) {
            periodCount += adjustment;
        }

        int getPeriodOffset() {
            return periodOffset;
        }

        int getPeriodCount() {
            return periodCount;
        }
    }

    private final String countryIso;

    private final ZoneNode root;
    private final Instant startInclusive;
    private final Instant endExclusive;

    private CountryZoneTree(String countryIso, ZoneNode root,
            Instant startInclusive, Instant endExclusive) {
        this.countryIso = countryIso;
        this.root = root;
        this.startInclusive = startInclusive;
        this.endExclusive = endExclusive;
    }

    /**
     * Creates a tree for the time zones for a country.
     */
    public static CountryZoneTree create(
            Country country, Instant startInclusive, Instant endExclusive) {

        // We use the US English names for detecting time zone name clashes.
        TimeZoneNames timeZoneNames = TimeZoneNames.getInstance(ULocale.US);
        List<CountryZonesFile.TimeZoneMapping> timeZoneMappings = country.getTimeZoneMappingsList();

        // Create ZoneInfo objects for every time zone for the time range
        // startInclusive -> endExclusive.
        List<ZoneInfo> zoneInfos = new ArrayList<>();
        for (CountryZonesFile.TimeZoneMapping timeZoneMapping : timeZoneMappings) {
            int priority = timeZoneMapping.getPriority();
            TimeZone timeZone = TimeZone.getTimeZone(timeZoneMapping.getId());
            if (isInvalidZone(timeZone)) {
                throw new IllegalArgumentException(
                        "Unknown or unexpected type for zone id: " + timeZone.getID());
            }
            BasicTimeZone basicTimeZone = (BasicTimeZone) timeZone;
            ZoneInfo zoneInfo = ZoneInfo.create(
                    timeZoneNames, basicTimeZone, priority, startInclusive, endExclusive);
            zoneInfos.add(zoneInfo);
        }

        // The algorithm constructs a tree. The root of the tree contains all ZoneInfos, and at each
        // node the ZoneInfos can be split into subsets.
        return create(country.getIsoCode(), zoneInfos, startInclusive, endExclusive);
    }

    private static CountryZoneTree create(String countryIso, List<ZoneInfo> zoneInfos,
            Instant startInclusive, Instant endExclusive) {
        // Create a root node with all the information needed to grow the whole tree.
        ZoneNode root = new ZoneNode("0", zoneInfos, 0, 0);

        // We call growTree() to build all the branches and leaves from the root.
        growTree(root);

        // Now we compress the tree to remove unnecessary nodes.
        compressTree(root);

        // Wrap the root and return.
        return new CountryZoneTree(countryIso, root, startInclusive, endExclusive);
    }

    /**
     * Grows the zone tree from the root.
     *
     * <p>After this step, we have a tree represents a forest. The root node is just a convenience
     * for constructing and anchoring the trees in the forest. Below the root, each node
     * represents a single period of time where all the zones associated with the node agreed on
     * what the local time was and what it was called. The tree grows from the future (the root)
     * into the past (the leaves). If a node has a single child it means that the previous
     * period (the child) also had every zone in agreement. If a node has zero children it means
     * there are no more periods in the past to investigate. If a node has multiple children it
     * means that the zones disagreed in the past. Looking at a node with multiple children in
     * reverse, from the children to a parent (i.e. going forward in time), it means that
     * several zones that previously disagreed were standardized to be the same. The tzdb ID
     * exists forever, but if zones have standardized it means that fewer zones are needed to
     * represent all possible local times in a given country.
     */
    private static void growTree(ZoneNode root) {
        root.visitSelfThenChildrenRecursive((ZoneNodeVisitor) currentNode -> {
            // Increase the period offset by one so that the child will be for one period further
            // back.
            int newPeriodOffset = currentNode.getPeriodOffset() + 1;

            // Split the zoneinfo set into new sets for the new depth.
            List<ZoneInfo> zoneInfosToSplit = currentNode.getZoneInfos();

            // Generate all the child sets.
            Map<ZonePeriodsKey, List<ZoneInfo>> newSetsMap = new HashMap<>();
            for (ZoneInfo zoneInfo : zoneInfosToSplit) {
                int periodStartIndex = zoneInfo.getZoneOffsetPeriodCount() - newPeriodOffset;
                if (periodStartIndex < 0) {
                    // We've run out of ZoneOffsetPeriods. We could declare this a leaf node at this
                    // point but we continue for safety to allow the childZoneInfoCount check below.
                    continue;
                }
                // Create a zone key for the zoneInfo. We only need to look at one period each time
                // as we know all periods after this point have to agree (otherwise they wouldn't
                // have been lumped together in a single node).
                ZonePeriodsKey zoneKey =
                        zoneInfo.createZoneOffsetKey(periodStartIndex, periodStartIndex + 1);
                List<ZoneInfo> identicalTimeZones =
                        newSetsMap.computeIfAbsent(zoneKey, k -> new ArrayList<>());
                identicalTimeZones.add(zoneInfo);
            }

            // Construct any child nodes.
            int childZoneInfoCount = 0;
            int i = 1;
            for (Map.Entry<ZonePeriodsKey, List<ZoneInfo>> newSetEntry : newSetsMap.entrySet()) {
                List<ZoneInfo> newSet = newSetEntry.getValue();
                childZoneInfoCount += newSet.size();
                // The child ID is just the {parent ID}.{child number} so we create an easy-to-debug
                // address.
                String childId = currentNode.getId() + "." + i;
                ZoneNode e = new ZoneNode(childId, newSet, newPeriodOffset, 1 /* periodCount */);
                currentNode.addChild(e);
                i++;
            }

            // Assertion: a node should either have no nodes (be a leaf) or all zones should have
            // been split between the children.
            if (childZoneInfoCount != 0 && childZoneInfoCount != zoneInfosToSplit.size()) {
                // This implies some kind of data issue.
                throw new IllegalStateException();
            }
        });
    }

    /**
     * Removes uninteresting nodes from the tree by merging them with their children where possible.
     * Uninteresting nodes are those that have a single child; having a single child implies the
     * node and its child have the same offsets and other information (they're just for an earlier
     * period). The resulting merged node has the same zones and depthInTree but a larger period
     * count.
     */
    private static void compressTree(ZoneNode root) {
        class CompressionVisitor implements ZoneNodeVisitor {

            @Override
            public void visit(ZoneNode node) {
                if (node.isRoot()) {
                    // Ignore the root.
                    return;
                }
                if (node.getChildrenCount() == 1) {
                    // The node only has a single child. Replace the node with its child.
                    ZoneNode child = node.getChildren().iterator().next();

                    // Remove the child from node.
                    node.removeChild(child);

                    int periodCountAdjustment = child.getPeriodCount();
                    ZoneNode descendant = child;
                    while (descendant.getChildrenCount() == 1) {
                        descendant = descendant.getChildren().iterator().next();
                        periodCountAdjustment += descendant.getPeriodCount();
                    }

                    // Add new children to this node.
                    for (ZoneNode newChild : descendant.getChildren()) {
                        node.addChild(newChild);
                    }
                    node.adjustPeriodCount(periodCountAdjustment);
                }
            }
        }
        root.visitSelfThenChildrenRecursive(new CompressionVisitor());
    }

    /** Validates the tree has no nodes with priority clashes. */
    public List<String> validateNoPriorityClashes() {
        class ValidationVisitor implements ZoneNodeVisitor {
            private final List<String> issues = new ArrayList<>();

            @Override
            public void visit(ZoneNode node) {
                if (node.isRoot()) {
                    // Ignore the root, it's not a "real" node and will usually clash in countries
                    // where there's more than one zone.
                    return;
                }

                if (node.hasPriorityClash()) {
                    String issue = node.getZoneInfosString();
                    issues.add(issue);
                }
            }

            public List<String> getIssues() {
                return issues;
            }
        }

        ValidationVisitor visitor = new ValidationVisitor();
        root.visitSelfThenChildrenRecursive(visitor);
        return visitor.getIssues();
    }

    /**
     * Creates a {@link CountryZoneUsage} object from the tree.
     */
    public CountryZoneUsage calculateCountryZoneUsage() {
        class CountryZoneVisibilityVisitor implements ZoneNodeVisitor {
            private final CountryZoneUsage zoneUsage = new CountryZoneUsage(countryIso);

            @Override
            public void visit(ZoneNode node) {
                // We ignore the root.
                if (node.isRoot()) {
                    return;
                }

                if (node.hasPriorityClash()) {
                    throw new IllegalStateException(
                            "Cannot calculate zone usage with priority clashes present");
                }

                Instant endInstant = node.getEndInstant();
                if (node.getParent().isRoot()) {
                    // If the parent is the root node, we can say that the end instant is actually
                    // the point in time we stopped generating the periods, not when the last period
                    // for that zone is. This matters for zones with DST.
                    endInstant = CountryZoneTree.this.endExclusive;
                }

                if (!node.isLeaf()) {
                    ZoneInfo primaryZone = node.getPrimaryZoneInfo();
                    addZoneEntryIfMissing(endInstant, primaryZone);
                } else {
                    // In some rare cases (e.g. Canada: Swift_Current and Crestor) zones have agreed
                    // completely since 1970 so some leaves may have multiple zones. So, attempt to
                    // add all zones for leaves, not just the primary.
                    for (ZoneInfo zoneInfo : node.getZoneInfos()) {
                        addZoneEntryIfMissing(endInstant, zoneInfo);
                    }
                }
            }

            private void addZoneEntryIfMissing(Instant endInstant, ZoneInfo zoneInfo) {
                String zoneId = zoneInfo.getZoneId();
                if (!zoneUsage.hasEntry(zoneId)) {
                    zoneUsage.addEntry(zoneId, endInstant);
                }
            }

            CountryZoneUsage getCountryZoneUsage() {
                return zoneUsage;
            }
        }

        CountryZoneVisibilityVisitor visitor = new CountryZoneVisibilityVisitor();
        root.visitSelfThenChildrenRecursive(visitor);
        return visitor.getCountryZoneUsage();
    }

    /**
     * Creates a Graphviz file for visualizing the tree.
     */
    public void createGraphvizFile(String outputFile) throws IOException {
        class DotFileVisitor implements ZoneNodeVisitor {
            private StringBuilder graphStringBuilder = new StringBuilder();

            @Override
            public void visit(ZoneNode node) {
                if (node.isRoot()) {
                    // Don't draw the root - make the tree look like a forest.
                    return;
                }

                String nodeName = enquote(node.getId());

                // Draw the node.
                Instant startInstant = node.getStartInstant();
                Instant endInstant = node.getEndInstant();
                boolean priorityClash = node.hasPriorityClash();

                String fromTimestamp = startInstant.toString();
                String toTimestamp = endInstant.toString();
                String optionalColor = priorityClash ? ",color=\"red\"" : "";
                String label = node.getZoneInfosString()
                        + "\nFrom=" + fromTimestamp + " to " + toTimestamp
                        + "\nPeriod count=" + node.getPeriodCount();

                writeLine(nodeName + "[label=\"" + label + "\"" + optionalColor + "];");

                // Link the node to its children.
                for (ZoneNode child : node.getChildren()) {
                    writeLine(nodeName + " -> " + enquote(child.getId()) + ";");
                }
            }

            private String enquote(String toQuote) {
                return "\"" + toQuote + "\"";
            }

            private void writeLine(String s) {
                graphStringBuilder.append(s);
                graphStringBuilder.append('\n');
            }
        }

        DotFileVisitor dotFileVisitor = new DotFileVisitor();
        root.visitSelfThenChildrenRecursive(dotFileVisitor);

        try (FileWriter fileWriter = new FileWriter(outputFile)) {
            writeLine(fileWriter, "strict digraph " + countryIso + " {");
            writeLine(fileWriter, dotFileVisitor.graphStringBuilder.toString());
            writeLine(fileWriter, "}");
        }
    }

    private static void writeLine(Appendable appendable, String s) throws IOException {
        appendable.append(s);
        appendable.append('\n');
    }

    private static boolean isInvalidZone(TimeZone timeZone) {
        return !(timeZone instanceof BasicTimeZone)
                || timeZone.getID().equals(TimeZone.UNKNOWN_ZONE_ID);
    }
}
