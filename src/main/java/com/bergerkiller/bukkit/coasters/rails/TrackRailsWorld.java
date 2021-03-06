package com.bergerkiller.bukkit.coasters.rails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import com.bergerkiller.bukkit.coasters.tracks.TrackCoaster;
import com.bergerkiller.bukkit.coasters.tracks.TrackConnection;
import com.bergerkiller.bukkit.coasters.tracks.TrackNode;
import com.bergerkiller.bukkit.coasters.world.CoasterWorldAccess;
import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.common.utils.LogicUtil;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.tc.controller.components.RailPath;

/**
 * Tracks the lookup of rails information from block positions on a single world
 */
public class TrackRailsWorld extends CoasterWorldAccess.Component {
    private final Map<IntVector3, List<TrackRailsSection>> sectionsByRails = new HashMap<IntVector3, List<TrackRailsSection>>();
    private final Map<IntVector3, List<TrackRailsSection>> sectionsByBlock = new HashMap<IntVector3, List<TrackRailsSection>>();

    public TrackRailsWorld(CoasterWorldAccess world) {
        super(world);
    }

    public void clear() {
        this.sectionsByBlock.clear();
        this.sectionsByRails.clear();
    }

    public List<TrackRailsSection> findAtBlock(Block block) {
        return LogicUtil.fixNull(sectionsByBlock.get(new IntVector3(block)), Collections.<TrackRailsSection>emptyList());
    }

    public List<TrackRailsSection> findAtRails(Block railsBlock) {
        return LogicUtil.fixNull(sectionsByRails.get(new IntVector3(railsBlock)), Collections.<TrackRailsSection>emptyList());
    }

    public List<TrackRailsSection> findAtRails(int x, int y, int z) {
        return LogicUtil.fixNull(sectionsByRails.get(new IntVector3(x, y, z)), Collections.<TrackRailsSection>emptyList());
    }

    /**
     * Rebuilds all the rail information
     */
    public void rebuild() {
        clear();
        for (TrackCoaster coaster : getTracks().getCoasters()) {
            for (TrackNode node : coaster.getNodes()) {
                store(node);
            }
        }
    }

    /**
     * Removes all track information for a particular node
     * 
     * @param node
     */
    public void purge(TrackNode node) {
        purge(Collections.singletonList(node));
    }

    /**
     * Removes all track information for all nodes specified
     * 
     * @param nodes
     */
    public void purge(Collection<TrackNode> nodes) {
        removeFromMap(sectionsByRails, nodes);
        removeFromMap(sectionsByBlock, nodes);
    }

    public void store(TrackNode node) {
        // If no connections, don't map it in the world at all - it does nothing
        if (node.getConnections().isEmpty()) {
            return;
        }

        // First 1 or 2 connections, which connect to each other and are selected
        addSectionToMap(new TrackRailsSection(node, node.buildPath(), true));

        // All other kinds of connections lead to their best fit
        List<TrackConnection> connections = node.getConnections();
        if (connections.size() > 2) {
            Vector dir0 = connections.get(0).getDirection(node);
            Vector dir1 = connections.get(1).getDirection(node);
            for (int i = 2; i < connections.size(); i++) {
                TrackConnection conn = connections.get(i);
                TrackConnection other;
                Vector dir = conn.getDirection(node);
                if (dir0.dot(dir) > dir1.dot(dir)) {
                    other = connections.get(1);
                } else {
                    other = connections.get(0);
                }
                addSectionToMap(new TrackRailsSection(node, node.buildPath(conn, other), false));
            }
        }
    }

    private final void addSectionToMap(TrackRailsSection section) {
        addToMap(sectionsByRails, section.rails, section);

        // For all segments of the path, store the block positions being covered in the lookup table
        for (RailPath.Segment segment : section.path.getSegments()) {
            double x = section.rails.x + segment.p0.x;
            double y = section.rails.y + segment.p0.y;
            double z = section.rails.z + segment.p0.z;
            int numSteps = MathUtil.ceil(segment.l / 0.1);
            if (numSteps <= 0) {
                continue;
            }

            if (numSteps == 1) {
                addToMap(sectionsByBlock, new IntVector3(x, y, z), section);
            } else {
                IntVector3 last_pos = null;
                for (int i = 0; i < numSteps; i++) {
                    double m = (double) i / (double) numSteps;
                    IntVector3 pos = new IntVector3(x + m * segment.dt.x,
                                                    y + m * segment.dt.y,
                                                    z + m * segment.dt.z);
                    if (!pos.equals(last_pos)) {
                        last_pos = pos;
                        addToMap(sectionsByBlock, pos, section);
                    }
                }
            }
            addToMap(sectionsByBlock, new IntVector3(
                    section.rails.x + segment.p1.x,
                    section.rails.y + segment.p1.y,
                    section.rails.z + segment.p1.z), section);
        }
    }

    private static void removeFromMap(Map<IntVector3, List<TrackRailsSection>> map, Collection<TrackNode> nodes) {
        Iterator<List<TrackRailsSection>> iter = map.values().iterator();
        while (iter.hasNext()) {
            List<TrackRailsSection> sections = iter.next();
            if (sections.size() > 1) {
                // List is an ArrayList - simply remove entries that should be removed
                for (int i = sections.size() - 1; i >= 0; i--) {
                    if (nodes.contains(sections.get(i).node)) {
                        sections.remove(i);
                    }
                }
                if (sections.isEmpty()) {
                    iter.remove();
                }
            } else if (sections.isEmpty() || nodes.contains(sections.get(0).node)) {
                // Easy handling of already-empty lists or lists storing only one section that should be removed
                iter.remove();
            }
        }
    }

    private static boolean addToMap(Map<IntVector3, List<TrackRailsSection>> map, IntVector3 key, TrackRailsSection section) {
        List<TrackRailsSection> list = map.get(key);
        if (list == null) {
            map.put(key, Collections.singletonList(section));
            //System.out.println("SINGLETRACK AT " + key);
            return true;
        } else if (!list.contains(section)) {
            if (list.size() == 1) {
                // Make mutable
                list = new ArrayList<TrackRailsSection>(list);
                map.put(key, list);
            }
            list.add(section);
            return true;
        } else {
            return false;
        }
    }

    /*
    public void store(TrackRailsSection section) {
        this.sectionsByRails.put(section.rails, section);

        // For all segments of the path, store the block positions being covered in the lookup table
        for (RailPath.Segment segment : section.path.getSegments()) {
            double x = section.rails.x + segment.p0.x;
            double y = section.rails.y + segment.p0.y;
            double z = section.rails.z + segment.p0.z;
            int numSteps = MathUtil.ceil(segment.l / 0.1);
            if (numSteps <= 0) {
                continue;
            }

            if (numSteps == 1) {
                sectionsByBlock.put(new IntVector3(x, y, z), section);
            } else {
                for (int i = 0; i < numSteps; i++) {
                    double m = (double) i / (double) (numSteps - 1);
                    IntVector3 pos = new IntVector3(x + m * segment.dt.x,
                                                    y + m * segment.dt.y,
                                                    z + m * segment.dt.z);
                    sectionsByBlock.put(pos, section);
                }
            }
        }
    }
    */
}
