/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Ordinastie
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package net.malisis.doors.tileentity;

import java.util.List;

import org.apache.commons.lang3.ArrayUtils;

import net.malisis.core.util.TileEntityUtils;
import net.malisis.core.util.Timer;
import net.malisis.core.util.syncer.Sync;
import net.malisis.core.util.syncer.Syncable;
import net.malisis.core.util.syncer.Syncer;
import net.malisis.doors.DoorDescriptor;
import net.malisis.doors.DoorState;
import net.malisis.doors.block.Door;
import net.malisis.doors.movement.IDoorMovement;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumFacing.Axis;
import net.minecraft.util.ITickable;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * @author Ordinastie
 *
 */
@Syncable("TileEntity")
public class DoorTileEntity extends TileEntity implements ITickable
{
	protected DoorDescriptor descriptor;
	protected int lastMetadata = -1;
	protected Timer timer = new Timer(0);
	protected DoorState state = DoorState.CLOSED;
	protected boolean moving;
	protected boolean centered = false;
	protected PropertyBool openProperty = BlockDoor.OPEN;

	//#region Getter/Setter
	public DoorDescriptor getDescriptor()
	{
		if (descriptor == null || descriptor.getMovement() == null)
		{
			if (getBlockType() == null)
				return new DoorDescriptor(); //prevent crashes

			if (getBlockType() instanceof Door)
				descriptor = ((Door) getBlockType()).getDescriptor();
		}
		//prevents NPE ?
		return descriptor != null ? descriptor : new DoorDescriptor();
	}

	public void setDescriptor(DoorDescriptor descriptor)
	{
		this.descriptor = descriptor;
	}

	public Timer getTimer()
	{
		return timer;
	}

	@Sync("state")
	public DoorState getState()
	{
		return state;
	}

	public void setState(DoorState state)
	{
		this.state = state;
	}

	public boolean isMoving()
	{
		return moving;
	}

	public void setMoving(boolean moving)
	{
		this.moving = moving;
	}

	public IDoorMovement getMovement()
	{
		return getDescriptor() != null ? getDescriptor().getMovement() : null;
	}

	public int getOpeningTime()
	{
		return getDescriptor() != null ? getDescriptor().getOpeningTime() : 6;
	}

	public IBlockState getBlockState()
	{
		IBlockState state = world.getBlockState(pos);
		if (state.getBlock() != getBlockType() || getBlockType() == null)
			return null;

		return state.getActualState(world, pos);
	}

	public EnumFacing getDirection()
	{
		return BlockDoor.getFacing(world, pos);
	}

	public boolean isTopBlock(BlockPos pos)
	{
		return this.pos.up().equals(pos);
	}

	public boolean isOpened()
	{
		IBlockState state = getBlockState();
		return state != null && state.getValue(BlockDoor.OPEN);
	}

	public boolean isHingeLeft()
	{
		IBlockState state = getBlockState();
		if (state == null)
			return false;
		return state.getBlock() instanceof Door && state.getValue(BlockDoor.HINGE) == BlockDoor.EnumHingePosition.LEFT;
	}

	public boolean isPowered()
	{
		return getWorld().isBlockIndirectlyGettingPowered(pos) + getWorld().isBlockIndirectlyGettingPowered(pos.up()) != 0;
	}

	public boolean isDoubleDoorPowered()
	{
		DoorTileEntity te = getDoubleDoor();
		return te != null && te.isPowered();
	}

	public boolean isCentered()
	{
		return centered;
	}

	public boolean shouldCenter()
	{
		if (getMovement() == null /*|| !getMovement().canCenter()*/)
			return false;

		EnumFacing offset = getDirection().rotateY();
		Block b1 = world.getBlockState(pos.offset(offset, 1)).getBlock();
		Block b2 = world.getBlockState(pos.offset(offset, -1)).getBlock();

		return ArrayUtils.contains(Door.centerBlocks, b1) || ArrayUtils.contains(Door.centerBlocks, b2);
	}

	public boolean setCentered(boolean centered)
	{
		this.centered = centered;
		TileEntityUtils.notifyUpdate(this);
		return centered;
	}

	public ItemStack getItemStack()
	{
		ItemStack itemStack = new ItemStack(getDescriptor().getItem());
		//don't write descriptor if it's the default one so their still stackable
		if (getDescriptor() != ((Door) getBlockType()).getDescriptor())
		{
			NBTTagCompound nbt = new NBTTagCompound();
			getDescriptor().writeNBT(nbt);
			itemStack.setTagCompound(nbt);
		}

		return itemStack;
	}

	//#end Getter/Setter

	public void onBlockPlaced(Door door, ItemStack itemStack)
	{
		DoorDescriptor desc = itemStack.getTagCompound() != null ? new DoorDescriptor(itemStack.getTagCompound()) : door.getDescriptor();
		setDescriptor(desc);
	}

	/**
	 * Open or close this DoorTileEntity
	 */
	public void openOrCloseDoor()
	{
		if (state == DoorState.OPENED)
			close();
		else
			open();
	}

	public boolean open()
	{
		if (state == DoorState.OPENING || state == DoorState.OPENED)
			return false;

		setDoorState(DoorState.OPENING);
		DoorTileEntity te = getDoubleDoor();
		if (te != null)
			te.setDoorState(DoorState.OPENING);

		return true;
	}

	public boolean close()
	{
		if (state == DoorState.CLOSING || state == DoorState.CLOSED)
			return false;

		setDoorState(DoorState.CLOSING);
		DoorTileEntity te = getDoubleDoor();
		if (te != null)
			te.setDoorState(DoorState.CLOSING);

		return true;
	}

	/**
	 * Change the current state of this DoorTileEntity.
	 *
	 * @param newState the new door state
	 */
	@Sync("state")
	public void setDoorState(DoorState newState)
	{
		if (state == newState)
			return;

		state = newState;
		if (getWorld() == null)
			return;

		if (state == DoorState.CLOSING || state == DoorState.OPENING)
		{
			if (moving)
			{
				long s = timer.elapsedTime() - Timer.tickToTime(getOpeningTime());
				timer.setRelativeStart(s);
			}
			else
			{
				timer.start();
				moving = true;
			}

			if (!world.isRemote)
				Syncer.sync(this, "state");

		}
		else
		{
			IBlockState state = getBlockState();
			if (state != null)
				world.setBlockState(pos, state.withProperty(openProperty, newState == DoorState.OPENED));
			moving = false;
		}

		playSound();
	}

	/**
	 * Play sound for the block
	 */
	public void playSound()
	{
		if (world.isRemote)
			return;

		SoundEvent sound = null;
		if (getDescriptor().getSound() != null)
			sound = getDescriptor().getSound().getSound(state);

		if (sound != null)
			getWorld().playSound(null, pos, sound, SoundCategory.BLOCKS, 1F, 1F);
	}

	/**
	 * Find the corresponding double door for this DoorTileEntity.
	 *
	 * @return the double door
	 */
	public DoorTileEntity getDoubleDoor()
	{
		if (!getDescriptor().isDoubleDoor())
			return null;

		EnumFacing offset = getDirection().rotateYCCW();
		if (isHingeLeft())
			offset = offset.getOpposite();

		DoorTileEntity te = Door.getDoor(world, pos.offset(offset));
		if (isMatchingDoubleDoor(te))
			return te;

		return null;
	}

	/**
	 * Is the DoorTileEntity passed a matching matching double door to this DoorTileEntity.
	 *
	 * @param te the te
	 * @return true, if is matching double door
	 */
	public boolean isMatchingDoubleDoor(DoorTileEntity te)
	{
		if (te == null)
			return false;

		if (getBlockType() != te.getBlockType()) // different block
			return false;

		if (getDirection() != te.getDirection()) // different direction
			return false;

		if (getMovement() != te.getMovement()) //different movement type
			return false;

		if (isOpened() != te.isOpened()) // different state
			return false;

		if (isHingeLeft() == te.isHingeLeft()) // handle same side
			return false;

		return true;
	}

	/**
	 * Change the state of this DoorTileEntity based on powered
	 */
	public void setPowered(boolean powered)
	{
		if (isOpened() == powered && !isMoving())
			return;

		DoorTileEntity te = getDoubleDoor();
		if (!powered && te != null && te.isPowered())
			return;

		DoorState newState = powered ? DoorState.OPENING : DoorState.CLOSING;
		setDoorState(newState);

		if (te != null)
			te.setDoorState(newState);
	}

	protected boolean hasPlayer()
	{
		//north-south axis
		boolean ns = getDirection().getAxis() == Axis.Z;
		int x = pos.getX();
		int y = pos.getY();
		int z = pos.getZ();

		AxisAlignedBB aabb = new AxisAlignedBB(x + (ns ? 0 : -2), y, z + (ns ? -2 : 0), x + (ns ? 3 : 1), y + 2, z + (ns ? 3 : 1));

		List<EntityPlayer> list = world.getEntitiesWithinAABB(EntityPlayer.class, aabb);
		return list != null && !list.isEmpty();
	}

	protected boolean doubleDoorHasPlayer()
	{
		DoorTileEntity te = getDoubleDoor();
		return te != null && te.hasPlayer();
	}

	@Override
	public void update()
	{
		//animation finished, update state (current door only)
		if (moving && timer.elapsedTick() > getOpeningTime())
			setDoorState(getState() == DoorState.CLOSING ? DoorState.CLOSED : DoorState.OPENED);

		//door is powered, open doors
		if (isPowered() || isDoubleDoorPowered())
		{
			open();
			return;
		}

		//door has player in proximity, open doors
		if (getDescriptor().hasProximityDetection() && (hasPlayer() || doubleDoorHasPlayer()))
		{
			open();
			return;
		}

		//time to auto-close, close doors
		if (getDescriptor().getAutoCloseTime() > 0 && timer.elapsedTick() > getDescriptor().getAutoCloseTime())
		{
			close();
			return;
		}
	}

	//#region NBT/Network
	@Override
	public void readFromNBT(NBTTagCompound nbt)
	{
		super.readFromNBT(nbt);

		//if (descriptor == null)
		descriptor = new DoorDescriptor(nbt);
		setDoorState(DoorState.values()[nbt.getInteger("state")]);
		setCentered(nbt.getBoolean("centered"));
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		super.writeToNBT(nbt);
		if (descriptor != null)
			descriptor.writeNBT(nbt);
		nbt.setInteger("state", state.ordinal());
		nbt.setBoolean("centered", centered);

		return nbt;
	}

	@Override
	public NBTTagCompound getUpdateTag()
	{
		return writeToNBT(new NBTTagCompound());
	}

	@Override
	public SPacketUpdateTileEntity getUpdatePacket()
	{
		NBTTagCompound nbt = new NBTTagCompound();
		this.writeToNBT(nbt);
		return new SPacketUpdateTileEntity(pos, 0, nbt);
	}

	@Override
	public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet)
	{
		this.readFromNBT(packet.getNbtCompound());
	}

	//#end NBT/Network

	/**
	 * Specify the bounding box ourselves otherwise, the block bounding box would be use. (And it should be at this point {0, 0, 0})
	 */
	@Override
	public AxisAlignedBB getRenderBoundingBox()
	{
		return new AxisAlignedBB(pos, pos.add(1, 2, 1));
	}

	@Override
	public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState)
	{
		return oldState.getBlock() != newState.getBlock();
	}
}
