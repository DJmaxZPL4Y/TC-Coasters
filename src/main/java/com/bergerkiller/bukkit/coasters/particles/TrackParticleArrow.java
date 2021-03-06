package com.bergerkiller.bukkit.coasters.particles;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

import com.bergerkiller.bukkit.common.math.Quaternion;
import com.bergerkiller.bukkit.common.protocol.PacketType;
import com.bergerkiller.bukkit.common.utils.EntityUtil;
import com.bergerkiller.bukkit.common.utils.PacketUtil;
import com.bergerkiller.bukkit.common.wrappers.DataWatcher;
import com.bergerkiller.bukkit.tc.Util;
import com.bergerkiller.generated.net.minecraft.server.EntityArmorStandHandle;
import com.bergerkiller.generated.net.minecraft.server.EntityHandle;
import com.bergerkiller.generated.net.minecraft.server.PacketPlayOutEntityEquipmentHandle;
import com.bergerkiller.generated.net.minecraft.server.PacketPlayOutEntityMetadataHandle;
import com.bergerkiller.generated.net.minecraft.server.PacketPlayOutEntityTeleportHandle;
import com.bergerkiller.generated.net.minecraft.server.PacketPlayOutSpawnEntityHandle;

/**
 * A particle consisting of a floating arrow pointing into a certain direction.
 * Uses an armor stand with a lever item rotated into the direction. The direction
 * is along which is rotated, the up vector indicates the roll around this vector.
 */
public class TrackParticleArrow extends TrackParticle {
    private static final double VIEW_RADIUS = 64.0;
    private final ProtocolPosition prot = new ProtocolPosition();
    private TrackParticleItemType itemType = TrackParticleItemType.LEVER;
    private Vector position;
    private Quaternion orientation;
    private boolean positionChanged = false;
    private boolean itemChanged = false;
    private int entityId = -1;

    protected TrackParticleArrow(TrackParticleWorld world, Vector position, Quaternion orientation) {
        super(world);
        this.position = position.clone();
        this.orientation = orientation.clone();
    }

    public void setPosition(Vector position) {
        if (!position.equals(this.position)) {
            this.position = position.clone();
            this.positionChanged = true;
        }
    }

    public void setDirection(Vector direction, Vector up) {
        this.setOrientation(Quaternion.fromLookDirection(direction, up));
    }

    public void setOrientation(Quaternion orientation) {
        if (!orientation.equals(this.orientation)) {
            this.orientation = orientation.clone();
            this.positionChanged = true;
        }
    }

    public void setItemType(TrackParticleItemType itemType) {
        if (!this.itemType.equals(itemType)) {
            this.itemType = itemType;
            this.itemChanged = true;
        }
    }

    @Override
    public double distanceSquared(Vector viewerPosition) {
        return this.position.distanceSquared(viewerPosition);
    }

    @Override
    public double getViewDistance() {
        return VIEW_RADIUS;
    }

    @Override
    public void updateAppearance() {
        if (this.positionChanged) {
            this.positionChanged = false;

            if (this.entityId != -1) {
                this.prot.calculate(this.position, this.orientation);

                PacketPlayOutEntityTeleportHandle tpPacket = PacketPlayOutEntityTeleportHandle.createNew(
                        this.entityId,
                        this.prot.posX,  this.prot.posY,  this.prot.posZ,
                        0.0f, 0.0f, false);
                this.broadcastPacket(tpPacket);

                DataWatcher metadata = new DataWatcher();
                metadata.set(EntityArmorStandHandle.DATA_POSE_ARM_RIGHT, prot.rotation);
                PacketPlayOutEntityMetadataHandle metaPacket = PacketPlayOutEntityMetadataHandle.createNew(this.entityId, metadata, true);
                this.broadcastPacket(metaPacket);
            }
        }
        if (this.itemChanged) {
            this.itemChanged = false;

            for (Player viewer : this.getViewers()) {
                PacketPlayOutEntityEquipmentHandle equipPacket = PacketPlayOutEntityEquipmentHandle.createNew(
                        this.entityId, EquipmentSlot.HAND, this.itemType.getItem(this.getState(viewer)));
                PacketUtil.sendPacket(viewer, equipPacket);
            }
        }
    }

    @Override
    public void onStateUpdated(Player viewer) {
        super.onStateUpdated(viewer);

        PacketPlayOutEntityEquipmentHandle equipPacket = PacketPlayOutEntityEquipmentHandle.createNew(
                this.entityId, EquipmentSlot.HAND, this.itemType.getItem(this.getState(viewer)));
        PacketUtil.sendPacket(viewer, equipPacket);
    }

    @Override
    public void makeHiddenFor(Player viewer) {
        if (this.entityId != -1) {
            PacketUtil.sendPacket(viewer, PacketType.OUT_ENTITY_DESTROY.newInstance(this.entityId));
        }
    }

    @Override
    public void makeVisibleFor(Player viewer) {
        if (this.entityId == -1) {
            this.entityId = EntityUtil.getUniqueEntityId();
        }

        prot.calculate(this.position, this.orientation);

        PacketPlayOutSpawnEntityHandle spawnPacket = PacketPlayOutSpawnEntityHandle.T.newHandleNull();
        spawnPacket.setEntityId(this.entityId);
        spawnPacket.setEntityUUID(UUID.randomUUID());
        spawnPacket.setEntityTypeId(78);
        spawnPacket.setPosX(prot.posX);
        spawnPacket.setPosY(prot.posY);
        spawnPacket.setPosZ(prot.posZ);
        PacketUtil.sendPacket(viewer, spawnPacket);

        DataWatcher metadata = new DataWatcher();
        metadata.set(EntityHandle.DATA_NO_GRAVITY, true);
        metadata.set(EntityHandle.DATA_FLAGS, (byte) (EntityHandle.DATA_FLAG_FLYING | EntityHandle.DATA_FLAG_INVISIBLE));
        metadata.set(EntityArmorStandHandle.DATA_ARMORSTAND_FLAGS, (byte) EntityArmorStandHandle.DATA_FLAG_HAS_ARMS);
        metadata.set(EntityArmorStandHandle.DATA_POSE_ARM_RIGHT, prot.rotation);
        PacketPlayOutEntityMetadataHandle metaPacket = PacketPlayOutEntityMetadataHandle.createNew(this.entityId, metadata, true);
        PacketUtil.sendPacket(viewer, metaPacket);

        PacketPlayOutEntityEquipmentHandle equipPacket = PacketPlayOutEntityEquipmentHandle.createNew(
                this.entityId, EquipmentSlot.HAND, this.itemType.getItem(this.getState(viewer)));
        PacketUtil.sendPacket(viewer, equipPacket);
    }

    @Override
    public boolean usesEntityId(int entityId) {
        return this.entityId == entityId;
    }

    private static class ProtocolPosition {
        public double posX, posY, posZ;
        public Vector rotation;

        public void calculate(Vector position, Quaternion orientation) {
            // Use direction for rotX/rotZ, and up vector for rotY rotation around it
            // This creates an arrow that smoothly rotates around its center point using rotY
            this.rotation = Util.getArmorStandPose(orientation);
            this.rotation.setX(this.rotation.getX() - 90.0);

            // Absolute position
            this.posX = position.getX() + 0.315;
            this.posY = position.getY() - 1.35;
            this.posZ = position.getZ();

            // Cancel relative positioning of the item itself
            Vector upVector =  new Vector(0.05, -0.05, -0.56);
            orientation.transformPoint(upVector);
            this.posX += upVector.getX();
            this.posY += upVector.getY();
            this.posZ += upVector.getZ();
        }

    }
}
