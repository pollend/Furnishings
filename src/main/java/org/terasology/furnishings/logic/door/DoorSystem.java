// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.furnishings.logic.door;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.audio.AudioManager;
import org.terasology.engine.audio.events.PlaySoundEvent;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.event.ReceiveEvent;
import org.terasology.engine.entitySystem.metadata.EntitySystemLibrary;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterMode;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.engine.logic.common.ActivateEvent;
import org.terasology.engine.logic.inventory.ItemComponent;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.math.Region3i;
import org.terasology.engine.math.Side;
import org.terasology.engine.registry.In;
import org.terasology.engine.rendering.logic.MeshComponent;
import org.terasology.engine.utilities.Assets;
import org.terasology.engine.world.BlockEntityRegistry;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockComponent;
import org.terasology.engine.world.block.entity.placement.PlaceBlocks;
import org.terasology.engine.world.block.regions.BlockRegionComponent;
import org.terasology.math.geom.Vector3f;
import org.terasology.math.geom.Vector3i;

import java.util.HashMap;
import java.util.Map;

@RegisterSystem(RegisterMode.AUTHORITY)
public class DoorSystem extends BaseComponentSystem {
    private static final Logger logger = LoggerFactory.getLogger(DoorSystem.class);

    @In
    private WorldProvider worldProvider;
    @In
    private BlockEntityRegistry blockEntityRegistry;
    @In
    private EntityManager entityManager;
    @In
    private AudioManager audioManager;
    @In
    private EntitySystemLibrary entitySystemLibrary;

    @ReceiveEvent(components = {DoorComponent.class, ItemComponent.class})
    public void placeDoor(ActivateEvent event, EntityRef entity) {
        DoorComponent door = entity.getComponent(DoorComponent.class);
        BlockComponent targetBlockComp = event.getTarget().getComponent(BlockComponent.class);
        if (targetBlockComp == null) {
            event.consume();
            return;
        }

        Vector3f horizDir = new Vector3f(event.getDirection());
        horizDir.y = 0;
        Side facingDir = Side.inDirection(horizDir);
        if (!facingDir.isHorizontal()) {
            event.consume();
            return;
        }

        Vector3f offset = new Vector3f(event.getHitPosition());
        offset.sub(targetBlockComp.position.toVector3f());
        Side offsetDir = Side.inDirection(offset);

        Vector3i primePos = new Vector3i(targetBlockComp.position);
        primePos.add(offsetDir.getVector3i());
        Block primeBlock = worldProvider.getBlock(primePos);
        if (!primeBlock.isReplacementAllowed()) {
            event.consume();
            return;
        }
        Block belowBlock = worldProvider.getBlock(primePos.x, primePos.y - 1, primePos.z);
        Block aboveBlock = worldProvider.getBlock(primePos.x, primePos.y + 1, primePos.z);

        // Determine top and bottom blocks
        Vector3i bottomBlockPos;
        Vector3i topBlockPos;
        if (belowBlock.isReplacementAllowed()) {
            bottomBlockPos = new Vector3i(primePos.x, primePos.y - 1, primePos.z);
            topBlockPos = primePos;
        } else if (aboveBlock.isReplacementAllowed()) {
            bottomBlockPos = primePos;
            topBlockPos = new Vector3i(primePos.x, primePos.y + 1, primePos.z);
        } else {
            event.consume();
            return;
        }

        Side attachSide = determineAttachSide(facingDir, offsetDir, bottomBlockPos, topBlockPos);
        if (attachSide == null) {
            event.consume();
            return;
        }

        Side closedSide = facingDir.reverse();
        if (closedSide == attachSide || closedSide.reverse() == attachSide) {
            closedSide = attachSide.yawClockwise(1);
        }

        Block newBottomBlock = door.bottomBlockFamily.getBlockForPlacement(bottomBlockPos, closedSide, Side.TOP);
        Block newTopBlock = door.topBlockFamily.getBlockForPlacement(bottomBlockPos, closedSide, Side.TOP);

        Map<Vector3i, Block> blockMap = new HashMap<>();
        blockMap.put(bottomBlockPos, newBottomBlock);
        blockMap.put(topBlockPos, newTopBlock);
        PlaceBlocks blockEvent = new PlaceBlocks(blockMap, event.getInstigator());
        worldProvider.getWorldEntity().send(blockEvent);

        if (!blockEvent.isConsumed()) {
            EntityRef newDoor = entityManager.create(door.doorRegionPrefab);
            entity.removeComponent(MeshComponent.class);
            newDoor.addComponent(new BlockRegionComponent(Region3i.createBounded(bottomBlockPos, topBlockPos)));
            Vector3f doorCenter = bottomBlockPos.toVector3f();
            doorCenter.y += 0.5f;
            newDoor.addComponent(new LocationComponent(doorCenter));
            DoorComponent newDoorComp = newDoor.getComponent(DoorComponent.class);
            newDoorComp.closedSide = closedSide;
            newDoorComp.openSide = attachSide.reverse();
            newDoorComp.isOpen = false;
            newDoor.saveComponent(newDoorComp);
            newDoor.send(new PlaySoundEvent(Assets.getSound("engine:PlaceBlock").get(), 0.5f));
            logger.info("Closed Side: {}", newDoorComp.closedSide);
            logger.info("Open Side: {}", newDoorComp.openSide);
            newDoor.send(new DoorPlacedEvent(event.getInstigator()));
        }
    }

    private Side determineAttachSide(Side facingDir, Side offsetDir, Vector3i bottomBlockPos, Vector3i topBlockPos) {
        Side attachSide = null;
        if (offsetDir.isHorizontal()) {
            if (canAttachTo(topBlockPos, offsetDir.reverse()) && canAttachTo(bottomBlockPos, offsetDir.reverse())) {
                attachSide = offsetDir.reverse();
            }
        }
        if (attachSide == null) {
            Side clockwise = facingDir.yawClockwise(1);
            if (canAttachTo(topBlockPos, clockwise) && canAttachTo(bottomBlockPos, clockwise)) {
                attachSide = clockwise;
            }
        }
        if (attachSide == null) {
            Side anticlockwise = facingDir.yawClockwise(-1);
            if (canAttachTo(topBlockPos, anticlockwise) && canAttachTo(bottomBlockPos, anticlockwise)) {
                attachSide = anticlockwise;
            }
        }
        return attachSide;
    }

    private boolean canAttachTo(Vector3i doorPos, Side side) {
        Vector3i adjacentBlockPos = new Vector3i(doorPos);
        adjacentBlockPos.add(side.getVector3i());
        Block adjacentBlock = worldProvider.getBlock(adjacentBlockPos);
        return adjacentBlock.isAttachmentAllowed();
    }

    @ReceiveEvent(components = {DoorComponent.class, BlockRegionComponent.class, LocationComponent.class})
    public void onFrob(ActivateEvent event, EntityRef entity) {
        DoorComponent door = entity.getComponent(DoorComponent.class);
        if (door.isOpen) {
            event.getInstigator().send(new CloseDoorEvent(entity));
        } else {
            event.getInstigator().send(new OpenDoorEvent(entity));
        }
    }

    @ReceiveEvent
    public void closeDoor(CloseDoorEvent event, EntityRef player) {
        EntityRef entity = event.getDoorEntity();
        DoorComponent door = entity.getComponent(DoorComponent.class);
        Side newSide = door.closedSide;
        BlockRegionComponent regionComp = entity.getComponent(BlockRegionComponent.class);
        Block bottomBlock = door.bottomBlockFamily.getBlockForPlacement(regionComp.region.min(), newSide, Side.TOP);
        worldProvider.setBlock(regionComp.region.min(), bottomBlock);
        Block topBlock = door.topBlockFamily.getBlockForPlacement(regionComp.region.max(), newSide, Side.TOP);
        worldProvider.setBlock(regionComp.region.max(), topBlock);
        if (door.closeSound != null) {
            entity.send(new PlaySoundEvent(door.closeSound, 1f));
        }
        door.isOpen = false;
        entity.saveComponent(door);
    }

    @ReceiveEvent
    public void openDoor(OpenDoorEvent event, EntityRef player) {
        EntityRef entity = event.getDoorEntity();
        DoorComponent door = entity.getComponent(DoorComponent.class);
        Side newSide = door.openSide;
        BlockRegionComponent regionComp = entity.getComponent(BlockRegionComponent.class);
        Block bottomBlock = door.bottomBlockFamily.getBlockForPlacement(regionComp.region.min(), newSide, Side.TOP);
        worldProvider.setBlock(regionComp.region.min(), bottomBlock);
        Block topBlock = door.topBlockFamily.getBlockForPlacement(regionComp.region.max(), newSide, Side.TOP);
        worldProvider.setBlock(regionComp.region.max(), topBlock);
        if (door.openSound != null) {
            entity.send(new PlaySoundEvent(door.openSound, 1f));
        }
        door.isOpen = true;
    }
}
