package com.bergerkiller.bukkit.coasters.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.bergerkiller.bukkit.coasters.TCCoasters;
import com.bergerkiller.bukkit.coasters.particles.TrackParticleWorld;
import com.bergerkiller.bukkit.coasters.rails.TrackRailsWorld;
import com.bergerkiller.bukkit.coasters.tracks.TrackCoaster;
import com.bergerkiller.bukkit.coasters.tracks.TrackConnection;
import com.bergerkiller.bukkit.coasters.tracks.TrackNode;
import com.bergerkiller.bukkit.coasters.tracks.TrackNodeSearchPath;
import com.bergerkiller.bukkit.coasters.tracks.TrackWorld;
import com.bergerkiller.bukkit.coasters.world.CoasterWorldAccess;
import com.bergerkiller.bukkit.common.config.FileConfiguration;
import com.bergerkiller.bukkit.common.math.Matrix4x4;
import com.bergerkiller.bukkit.common.utils.LogicUtil;

/**
 * The track editing state of a single player.
 * Note that this state is only ever valid for the World the player is on.
 * When the player changed world, any edited nodes are automatically cleared.
 */
public class PlayerEditState implements CoasterWorldAccess {
    private static final int EDIT_AUTO_TIMEOUT = 5;
    private static final int EDIT_CANCEL_TIMEOUT = 8;
    private final TCCoasters plugin;
    private final Player player;
    private final Set<TrackNode> editedNodes = new HashSet<TrackNode>();
    private CoasterWorldAccess cachedCoasterWorld = null;
    private TrackNode lastEdited = null;
    private long lastEditTime = System.currentTimeMillis();
    private Mode editMode = Mode.DISABLED;
    private Mode afterEditMode = null;
    private int editTimeoutCtr = 0;
    private int heldDownTicks = 0;
    private boolean changed = false;
    private Location editStartPos = null;
    private Vector editRotInfo = new Vector(); // virtual coordinate of the vector

    public PlayerEditState(TCCoasters plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    public void load() {
        FileConfiguration config = this.plugin.getPlayerConfig(this.player);
        if (config.exists()) {
            config.load();

            this.editMode = config.get("mode", Mode.DISABLED);
            this.editedNodes.clear();
            List<String> editNodePositions = config.getList("editedNodes", String.class);
            if (editNodePositions != null && !editNodePositions.isEmpty()) {
                for (String nodeStr : editNodePositions) {
                    String[] coords = nodeStr.split("_");
                    if (coords.length == 3) {
                        try {
                            double x, y, z;
                            x = Double.parseDouble(coords[0]);
                            y = Double.parseDouble(coords[1]);
                            z = Double.parseDouble(coords[2]);
                            TrackNode node = getTracks().findNodeExact(new Vector(x, y, z));
                            if (node != null) {
                                this.editedNodes.add(node);
                            }
                        } catch (NumberFormatException ex) {}
                    }
                }
            }
        }
        this.changed = false;
    }

    public void save() {
        if (!this.changed) {
            return;
        }
        this.changed = false;
        FileConfiguration config = this.plugin.getPlayerConfig(this.player);
        config.set("mode", this.editMode);
        List<String> editedNodeNames = new ArrayList<String>(this.editedNodes.size());
        for (TrackNode node : this.editedNodes) {
            Vector p = node.getPosition();
            editedNodeNames.add(p.getX() + "_" + p.getY() + "_" + p.getZ());
        }
        config.set("editedNodes", editedNodeNames);
        config.save();
    }

    public Player getPlayer() {
        return this.player;
    }

    public long getLastEditTime(TrackNode node) {
        if (this.lastEdited != node) {
            return Long.MAX_VALUE;
        } else {
            return System.currentTimeMillis() - this.lastEditTime;
        }
    }

    public void clearEditedNodes() {
        if (!this.editedNodes.isEmpty()) {
            ArrayList<TrackNode> oldNodes = new ArrayList<TrackNode>(this.editedNodes);
            this.editedNodes.clear();
            this.lastEdited = null;
            this.changed = true;
            for (TrackNode oldNode : oldNodes) {
                oldNode.onStateUpdated(this.player);
            }
        }
    }

    /**
     * Finds the track node the player is currently looking at exactly
     * 
     * @return node being looked at
     */
    public TrackNode findLookingAt() {
        // Create an inverted camera transformation of the player's view direction
        Matrix4x4 cameraTransform = new Matrix4x4();
        cameraTransform.translateRotate(this.player.getEyeLocation());
        cameraTransform.invert();

        // Go by all selected track nodes, and pick those close in view on the same world
        // The transformed point is a projective view of the Minecart in the player's vision
        // X/Y is left-right/up-down and Z is depth after the transformation is applied
        TrackNode bestNode = null;
        double bestDistance = 0.3;
        for (TrackNode node : this.getEditedNodes()) {
            double distance = node.getViewDistance(cameraTransform);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestNode = node;
            }
        }
        return bestNode;
    }

    public Mode getMode() {
        return this.editMode;
    }

    public Set<TrackNode> getEditedNodes() {
        return this.editedNodes;
    }

    public boolean hasEditedNodes() {
        return !this.editedNodes.isEmpty();
    }

    public void setMode(Mode mode) {
        this.afterEditMode = null;
        if (this.editMode != mode) {
            this.editMode = mode;
            this.changed = true;
            for (TrackNode node : this.editedNodes) {
                node.onStateUpdated(this.player);
            }
        }
    }

    /**
     * Sets the mode to switch to after releasing right-click in the current mode
     * 
     * @param mode
     */
    public void setAfterEditMode(Mode mode) {
        this.afterEditMode = mode;
    }

    public void setEditing(TrackNode node, boolean editing) {
        if (LogicUtil.addOrRemove(this.editedNodes, node, editing)) {
            node.onStateUpdated(this.player);
            this.lastEdited = node;
            this.lastEditTime = System.currentTimeMillis();
            this.changed = true;
        }
    }

    public boolean isEditing(TrackNode node) {
        return this.editedNodes.contains(node);
    }

    public boolean onLeftClick() {
        // When holding right-click and clicking left-click in position mode, create a new node and drag that
        if (this.getMode() == Mode.POSITION && this.isHoldingRightClick()) {
            Vector pos;
            if (this.getEditedNodes().size() == 1) {
                pos = this.getEditedNodes().iterator().next().getPosition();
            } else {
                pos = this.getNewNodePos();
            }
            this.createNewNode(pos);
            return true;
        }

        // Create an inverted camera transformation of the player's view direction
        Matrix4x4 cameraTransform = new Matrix4x4();
        cameraTransform.translateRotate(player.getEyeLocation());
        cameraTransform.invert();

        // Go by all track nodes on the server, and pick those close in view on the same world
        // The transformed point is a projective view of the Minecart in the player's vision
        // X/Y is left-right/up-down and Z is depth after the transformation is applied
        TrackNode bestNode = null;
        TrackConnection bestJunction = null;
        double bestDistance = Double.MAX_VALUE;

        for (TrackCoaster coaster : getCoasterWorld().getTracks().getCoasters()) {
            for (TrackNode node : coaster.getNodes()) {
                // Node itself
                {
                    double distance = node.getViewDistance(cameraTransform);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestNode = node;
                        bestJunction = null;
                    }
                }

                // If node has multiple junctions, check if the player is clicking on any of the junction nodes
                // This follows very precise rules
                if (node.getConnections().size() > 2) {
                    for (TrackConnection conn : node.getConnections()) {
                        double distance = node.getJunctionViewDistance(cameraTransform, conn);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            bestNode = node;
                            bestJunction = conn;
                        }
                    }
                }
            }
        }

        if (bestNode == null) {
            if (!player.isSneaking()) {
                clearEditedNodes();
            }
            return false;
        }

        // Switch junction when clicking on one
        if (bestJunction != null) {
            bestNode.switchJunction(bestJunction);
            return true;
        }

        long lastEditTime = getLastEditTime(bestNode);
        if (lastEditTime > 300) {
            // Single node selection mode
            if (!player.isSneaking()) {
                clearEditedNodes();
            }

            // Toggle edit state
            setEditing(bestNode, !isEditing(bestNode));

        } else if (isEditing(bestNode)) {
            // Mass-selection mode
            if (player.isSneaking()) {
                // Select all nodes between the clicked node and the nearest other selected node
                this.floodSelectNearest(bestNode);
            } else {
                // Flood-fill select all nodes connected from bestNode
                this.floodSelect(bestNode);
            }
        }

        return true;
    }

    /**
     * Selects a node and all nodes connected to it, recursively.
     * This will select an entire coaster or piece of track, for example.
     * Any previous selection is cleared.
     * 
     * @param startNode to flood select from
     */
    public void floodSelect(TrackNode startNode) {
        this.clearEditedNodes();

        List<TrackNode> pending = new ArrayList<TrackNode>(2);
        pending.add(startNode);

        while (!pending.isEmpty()) {
            TrackNode node = pending.remove(0);
            setEditing(node, true);
            for (TrackNode neighbour : node.getNeighbours()) {
                if (!isEditing(neighbour)) {
                    pending.add(neighbour);
                }
            }
        }
    }

    /**
     * Attempts to find the closest node that is also selected, but not the start node,
     * and selects the nodes between them. The selected nodes are added and the previous
     * selection is kept.
     * 
     * @param startNode
     */
    public void floodSelectNearest(TrackNode startNode) {
        // Find shortest path from startNode to any other nodes being edited
        TrackNodeSearchPath bestPath = TrackNodeSearchPath.findShortest(startNode, this.getEditedNodes());

        // Now do stuff with the found path, if found
        if (bestPath != null) {
            for (TrackNode node : bestPath.path) {
                this.setEditing(node, true);
            }
        }
    }

    public boolean onRightClick() {
        this.editTimeoutCtr = (EDIT_AUTO_TIMEOUT + EDIT_CANCEL_TIMEOUT);
        return true;
    }

    public boolean isHoldingRightClick() {
        return editTimeoutCtr > 0 && this.plugin.isHoldingEditTool(this.player);
    }

    public void update() {
        if (this.isHoldingRightClick()) {
            this.editTimeoutCtr--;
            this.changed = true;
            if (this.editTimeoutCtr >= EDIT_AUTO_TIMEOUT) {
                if (this.heldDownTicks == 0 || this.editMode.autoActivate(this.heldDownTicks)) {
                    this.updateEditing();
                }
                this.heldDownTicks++;
            }
        } else {
            if (this.heldDownTicks > 0) {
                this.onEditingFinished();
                this.heldDownTicks = 0;
            }
            this.editTimeoutCtr = 0;
            if (this.afterEditMode != null) {
                this.setMode(this.afterEditMode);
            }
        }
    }

    private void updateEditing() {
        if (this.editMode == Mode.CREATE) {
            // Create new tracks
            createTrack();
        } else if (this.editMode == Mode.DELETE) {
            // Delete tracks
            deleteTrack();
        } else {
            // Position / Orientation logic
            changePositionOrientation();
        }
    }

    // when player releases the right-click mouse button
    private void onEditingFinished() {
        // When drag-dropping a node onto a node, 'merge' the two
        // Do so by connecting all other neighbours of the dragged node to the node
        if (this.getMode() == Mode.POSITION && this.getEditedNodes().size() == 1) {
            TrackWorld tracks = this.getTracks();
            TrackNode draggedNode = this.getEditedNodes().iterator().next();
            TrackNode droppedNode = null;

            // Get all nodes nearby the position, sorted from close to far
            final Vector pos = draggedNode.getPosition();
            List<TrackNode> nearby = tracks.findNodesNear(new ArrayList<TrackNode>(), pos, 0.3);
            Collections.sort(nearby, new Comparator<TrackNode>() {
                @Override
                public int compare(TrackNode o1, TrackNode o2) {
                    return Double.compare(o1.getPosition().distanceSquared(pos),
                                          o2.getPosition().distanceSquared(pos));
                }
            });

            // Pick first (closest) node that is not the node dragged
            for (TrackNode nearNode : nearby) {
                if (nearNode != draggedNode) {
                    droppedNode = nearNode;
                    break;
                }
            }

            // Merge if found
            if (droppedNode != null) {
                List<TrackNode> connectedNodes = draggedNode.getNeighbours();

                // Delete dragged node and connect all that was connected to it, with the one dropped on
                draggedNode.remove();
                for (TrackNode connected : connectedNodes) {
                    if (connected != droppedNode) {
                        tracks.connect(droppedNode, connected);
                    }
                }
            }
        }
    }

    /**
     * Deletes all selected tracks and selects the track that is connected
     * to the now-deleted tracks. This allows repeated deletion to 'walk' and delete.
     */
    public void deleteTrack() {
        // Defensive copy
        HashSet<TrackNode> toDelete = new HashSet<TrackNode>(this.getEditedNodes());

        // Disconnect nodes when adjacent nodes are selected
        boolean disconnectedNodes = false;
        for (TrackNode node : toDelete) {
            for (TrackNode neigh : node.getNeighbours()) {
                if (toDelete.contains(neigh)) {
                    node.getTracks().disconnect(node, neigh);
                    disconnectedNodes = true;
                }
            }
        }
        if (disconnectedNodes) {
            // Clean up empty nodes
            for (TrackNode node : toDelete) {
                if (node.getConnections().isEmpty()) {
                    this.setEditing(node, false);
                    node.remove();
                }
            }
            return;
        }

        // Backup all nodes to delete, then select all unselected neighbours of those nodes
        this.clearEditedNodes();
        for (TrackNode node : toDelete) {
            for (TrackNode neighbour : node.getNeighbours()) {
                if (!toDelete.contains(neighbour)) {
                    this.setEditing(neighbour, true);
                }
            }
        }

        // Delete all nodes to be deleted
        for (TrackNode node : toDelete) {
            node.remove();
        }
    }

    /**
     * Creates a new track node, connecting with already selected track nodes.
     * The created track node is selected to allow chaining create node calls.
     */
    public void createTrack() {
        // For the first click, check if the player is looking exactly at a particular connection or node
        // If so, create a node on this connection or node and switch mode to position adjustment
        // This allows for a kind of click-drag creation design
        // TODO: Find connection
        Location eyeLoc = getPlayer().getEyeLocation();
        TrackWorld tracks = getTracks();
        boolean dragAfterCreate = false;
        Vector pos = null;
        if (this.heldDownTicks == 0) {
            TrackNode lookingAt = findLookingAt();
            if (lookingAt != null) {
                this.clearEditedNodes();
                this.setEditing(lookingAt, true);
                pos = lookingAt.getPosition().clone();
                dragAfterCreate = true;
            }
        }

        if (pos == null) {
            pos = eyeLoc.toVector().add(eyeLoc.getDirection().multiply(0.5));
        }

        // First check no track already exists at this position
        if (!dragAfterCreate && !tracks.findNodesNear(new ArrayList<TrackNode>(2), pos, 0.1).isEmpty()) {
            return;
        }

        // Create the node and set as editing
        createNewNode(pos);

        // If drag mode, switch to POSITION mode and initiate the drag
        if (dragAfterCreate) {
            this.setMode(Mode.POSITION);
            this.editStartPos = eyeLoc;
            this.editRotInfo = this.editStartPos.toVector();
            this.setAfterEditMode(Mode.CREATE);
        }
    }

    private Vector getNewNodePos() {
        Location eyeLoc = getPlayer().getEyeLocation();
        return eyeLoc.toVector().add(eyeLoc.getDirection().multiply(0.5));
    }

    private void createNewNode(Vector pos) {
        // Create the node and set as editing
        TrackWorld tracks = getTracks();
        TrackNode newNode = null;
        if (hasEditedNodes()) {
            for (TrackNode node : getEditedNodes()) {
                if (newNode == null) {
                    newNode = tracks.addNode(node, pos);
                } else {
                    tracks.connect(node, newNode);
                }
            }
        } else {
            newNode = tracks.createNew(pos).getNodes().get(0);
        }
        clearEditedNodes();
        setEditing(newNode, true);
    }

    public void changePositionOrientation() {
        if (!this.hasEditedNodes()) {
            return;
        }

        // First click?
        if (this.heldDownTicks == 0) {
            this.editStartPos = this.player.getEyeLocation();
            this.editRotInfo = this.editStartPos.toVector();

            TrackNode lookingAt = this.findLookingAt();
            if (lookingAt != null) {
                double distanceTo = lookingAt.getPosition().distance(this.editRotInfo);
                this.editRotInfo.add(this.editStartPos.getDirection().multiply(distanceTo));
            }
        }

        // Calculate the transformation performed as a result of the player 'looking'
        Location currPos = this.player.getEyeLocation();
        Matrix4x4 oldTransform = new Matrix4x4();
        Matrix4x4 newTransform = new Matrix4x4();
        oldTransform.translateRotate(this.editStartPos);
        newTransform.translateRotate(currPos);

        Matrix4x4 changes = new Matrix4x4();
        changes.multiply(newTransform);
        oldTransform.invert();
        changes.multiply(oldTransform);

        if (this.getMode() == Mode.ORIENTATION) {
            changes.transformPoint(this.editRotInfo);
            for (TrackNode node : this.getEditedNodes()) {
                node.setOrientation(this.editRotInfo.clone().subtract(node.getPosition()));
            }
        } else {
            for (TrackNode node : this.getEditedNodes()) {
                Vector v = node.getPosition().clone();
                changes.transformPoint(v);
                node.setPosition(v);
            }
        }

        this.editStartPos = currPos;
    }

    public static enum Mode {
        DISABLED("Disabled (hidden)", 0, 1),
        CREATE("Create Track", 10, 3),
        POSITION("Change Position", 0, 1),
        ORIENTATION("Change Orientation", 0, 1),
        DELETE("Delete Track", 10, 6);

        private final int _autoInterval;
        private final int _autoDelay;
        private final String _name;

        // name: displayed in the UI
        // autoDelay: how many ticks of holding right-click until continuously activating
        // autoInterval: tick interval of activation while holding right-click
        private Mode(String name, int autoDelay, int autoInterval) {
            this._name = name;
            this._autoDelay = autoDelay;
            this._autoInterval = autoInterval;
        }

        public boolean autoActivate(int tick) {
            tick -= this._autoDelay;
            return tick >= 0 && (_autoInterval <= 0 || (tick % _autoInterval) == 0);
        }

        public String getName() {
            return this._name;
        }

        public static Mode fromName(String name) {
            for (Mode mode : values()) {
                if (mode.getName().equals(name)) {
                    return mode;
                }
            }
            return CREATE;
        }
    }

    // CoasterWorldAccess

    /**
     * Gets the coaster world the player is currently on
     * 
     * @return coaster world
     */
    public CoasterWorldAccess getCoasterWorld() {
        World bukkitWorld = this.player.getWorld();
        if (this.cachedCoasterWorld == null || this.cachedCoasterWorld.getWorld() != bukkitWorld) {
            this.cachedCoasterWorld = this.plugin.getCoasterWorld(bukkitWorld);
        }
        return this.cachedCoasterWorld;
    }

    @Override
    public TCCoasters getPlugin() {
        return this.plugin;
    }

    @Override
    public World getWorld() {
        return this.player.getWorld();
    }

    @Override
    public TrackWorld getTracks() {
        return this.getCoasterWorld().getTracks();
    }

    @Override
    public TrackParticleWorld getParticles() {
        return this.getCoasterWorld().getParticles();
    }

    @Override
    public TrackRailsWorld getRails() {
        return this.getCoasterWorld().getRails();
    }
}
