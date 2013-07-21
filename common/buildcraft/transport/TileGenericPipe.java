/**
 * Copyright (c) SpaceToad, 2011 http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public License
 * 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package buildcraft.transport;

import buildcraft.BuildCraftCore;
import buildcraft.BuildCraftTransport;
import buildcraft.api.core.IIconProvider;
import buildcraft.api.core.Position;
import buildcraft.api.core.SafeTimeTracker;
import buildcraft.api.gates.IOverrideDefaultTriggers;
import buildcraft.api.gates.ITrigger;
import buildcraft.api.power.IPowerReceptor;
import buildcraft.api.power.PowerHandler;
import buildcraft.api.power.PowerHandler.PowerReceiver;
import buildcraft.api.transport.IPipe;
import buildcraft.api.transport.IPipeConnection;
import buildcraft.api.transport.IPipeEntry;
import buildcraft.api.transport.IPipeTile;
import buildcraft.api.transport.IPipedItem;
import buildcraft.api.transport.ISolidSideTile;
import buildcraft.core.DefaultProps;
import buildcraft.core.EntityPassiveItem;
import buildcraft.core.IDropControlInventory;
import buildcraft.core.ITileBufferHolder;
import buildcraft.core.TileBuffer;
import buildcraft.core.network.IClientState;
import buildcraft.core.network.ISyncedTile;
import buildcraft.core.network.PacketTileState;
import buildcraft.core.utils.Utils;
import buildcraft.transport.Gate.GateKind;
import buildcraft.transport.network.PipeRenderStatePacket;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.logging.Level;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.packet.Packet;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

public class TileGenericPipe extends TileEntity implements IPowerReceptor, IFluidHandler, IPipeEntry, IPipeTile, IOverrideDefaultTriggers, ITileBufferHolder,
		IPipeConnection, IDropControlInventory, IPipeRenderState, ISyncedTile, ISolidSideTile {

	private class CoreState implements IClientState {

		public int pipeId = -1;
		public int gateKind = 0;

		@Override
		public void writeData(DataOutputStream data) throws IOException {
			data.writeInt(pipeId);
			data.writeInt(gateKind);
		}

		@Override
		public void readData(DataInputStream data) throws IOException {
			pipeId = data.readInt();
			gateKind = data.readInt();
		}
	}
	private PipeRenderState renderState = new PipeRenderState();
	private CoreState coreState = new CoreState();
	private boolean deletePipe = false;
	private TileBuffer[] tileBuffer;
	public boolean[] pipeConnectionsBuffer = new boolean[6];
	public SafeTimeTracker networkSyncTracker = new SafeTimeTracker();
	public Pipe pipe;
	private boolean blockNeighborChange = false;
	private boolean refreshRenderState = false;
	private boolean pipeBound = false;
	private int[] facadeBlocks = new int[ForgeDirection.VALID_DIRECTIONS.length];
	private int[] facadeMeta = new int[ForgeDirection.VALID_DIRECTIONS.length];
	private boolean[] plugs = new boolean[ForgeDirection.VALID_DIRECTIONS.length];

	public TileGenericPipe() {
	}

	@Override
	public void writeToNBT(NBTTagCompound nbttagcompound) {
		super.writeToNBT(nbttagcompound);

		if (pipe != null) {
			nbttagcompound.setInteger("pipeId", pipe.itemID);
			pipe.writeToNBT(nbttagcompound);
		} else {
			nbttagcompound.setInteger("pipeId", coreState.pipeId);
		}

		for (int i = 0; i < ForgeDirection.VALID_DIRECTIONS.length; i++) {
			nbttagcompound.setInteger("facadeBlocks[" + i + "]", facadeBlocks[i]);
			nbttagcompound.setInteger("facadeMeta[" + i + "]", facadeMeta[i]);
			nbttagcompound.setBoolean("plug[" + i + "]", plugs[i]);
		}

	}

	@Override
	public void readFromNBT(NBTTagCompound nbttagcompound) {
		super.readFromNBT(nbttagcompound);

		coreState.pipeId = nbttagcompound.getInteger("pipeId");
		pipe = BlockGenericPipe.createPipe(coreState.pipeId);

		if (pipe != null) {
			pipe.readFromNBT(nbttagcompound);
		} else {
			BuildCraftCore.bcLog.log(Level.WARNING, "Pipe failed to load from NBT at {0},{1},{2}", new Object[]{xCoord, yCoord, zCoord});
			deletePipe = true;
		}

		for (int i = 0; i < ForgeDirection.VALID_DIRECTIONS.length; i++) {
			facadeBlocks[i] = nbttagcompound.getInteger("facadeBlocks[" + i + "]");
			facadeMeta[i] = nbttagcompound.getInteger("facadeMeta[" + i + "]");
			plugs[i] = nbttagcompound.getBoolean("plug[" + i + "]");
		}

	}

	@Override
	public void invalidate() {
		initialized = false;
		tileBuffer = null;
		if (pipe != null) {
			pipe.invalidate();
		}
		super.invalidate();
	}

	@Override
	public void validate() {
		super.validate();
		tileBuffer = null;
		bindPipe();
		if (pipe != null) {
			pipe.validate();
		}
	}
	public boolean initialized = false;

	@Override
	public void updateEntity() {

		if (deletePipe) {
			worldObj.setBlockToAir(xCoord, yCoord, zCoord);
		}

		if (pipe == null)
			return;

		if (!initialized) {
			initialize(pipe);
		}

		if (!BlockGenericPipe.isValid(pipe))
			return;

		if (blockNeighborChange) {

			computeConnections();
			pipe.onNeighborBlockChange(0);
			blockNeighborChange = false;
			refreshRenderState = true;
		}

		if (refreshRenderState) {
			refreshRenderState();
			refreshRenderState = false;
		}

		PowerReceiver provider = getPowerReceiver(null);

		if (provider != null) {
			provider.update();
		}

		if (pipe != null) {
			pipe.updateEntity();
		}
	}

	// PRECONDITION: worldObj must not be null
	private void refreshRenderState() {

		// Only done on server/SSP
		if (worldObj.isRemote)
			return;

		// Pipe connections;
		for (ForgeDirection o : ForgeDirection.VALID_DIRECTIONS) {
			renderState.pipeConnectionMatrix.setConnected(o, this.pipeConnectionsBuffer[o.ordinal()]);
		}

		// Pipe Textures
		for (ForgeDirection o : ForgeDirection.values()) {
			renderState.textureMatrix.setIconIndex(o, pipe.getIconIndex(o));
		}

		// WireState
		for (IPipe.WireColor color : IPipe.WireColor.values()) {
			renderState.wireMatrix.setWire(color, pipe.wireSet[color.ordinal()]);
			for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
				renderState.wireMatrix.setWireConnected(color, direction, pipe.isWireConnectedTo(this.getTile(direction), color));
			}
			boolean lit = pipe.signalStrength[color.ordinal()] > 0;

			switch (color) {
				case Red:
					renderState.wireMatrix.setWireIndex(color, lit ? WireIconProvider.Texture_Red_Lit : WireIconProvider.Texture_Red_Dark);
					break;
				case Blue:
					renderState.wireMatrix.setWireIndex(color, lit ? WireIconProvider.Texture_Blue_Lit : WireIconProvider.Texture_Blue_Dark);
					break;
				case Green:
					renderState.wireMatrix.setWireIndex(color, lit ? WireIconProvider.Texture_Green_Lit : WireIconProvider.Texture_Green_Dark);
					break;
				case Yellow:
					renderState.wireMatrix.setWireIndex(color, lit ? WireIconProvider.Texture_Yellow_Lit : WireIconProvider.Texture_Yellow_Dark);
					break;
				default:
					break;

			}
		}

		// Gate Textures
		renderState.setHasGate(pipe.hasGate());
		renderState.setGateIconIndex(!pipe.hasGate() ? 0 : pipe.gate.getTextureIconIndex(pipe.gate.isGateActive()));

		// Facades
		for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
			int blockId = this.facadeBlocks[direction.ordinal()];
			renderState.facadeMatrix.setFacade(direction, blockId, this.facadeMeta[direction.ordinal()]);
		}

		//Plugs
		for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
			renderState.plugMatrix.setConnected(direction, plugs[direction.ordinal()]);
		}

		if (renderState.isDirty()) {
			worldObj.markBlockForUpdate(this.xCoord, this.yCoord, this.zCoord);
			renderState.clean();
		}
	}

	public void initialize(Pipe pipe) {

		this.blockType = getBlockType();

		if (pipe == null) {
			BuildCraftCore.bcLog.log(Level.WARNING, "Pipe failed to initialize at {0},{1},{2}, deleting", new Object[]{xCoord, yCoord, zCoord});
			worldObj.setBlockToAir(xCoord, yCoord, zCoord);
			return;
		}

		this.pipe = pipe;

		for (ForgeDirection o : ForgeDirection.VALID_DIRECTIONS) {
			TileEntity tile = getTile(o);

			if (tile instanceof ITileBufferHolder) {
				((ITileBufferHolder) tile).blockCreated(o, BuildCraftTransport.genericPipeBlock.blockID, this);
			}
			if (tile instanceof TileGenericPipe) {
				((TileGenericPipe) tile).scheduleNeighborChange();
			}
		}

		bindPipe();

		computeConnections();
		scheduleRenderUpdate();

		if (pipe.needsInit())
			pipe.initialize();

		initialized = true;
	}

	private void bindPipe() {

		if (!pipeBound && pipe != null) {

			pipe.setTile(this);

			coreState.pipeId = pipe.itemID;
			pipeBound = true;
		}
	}

	@Override
	public IPipe getPipe() {
		return pipe;
	}

	@Override
	public boolean isInitialized() {
		return initialized;
	}

	@Override
	public PowerHandler.PowerReceiver getPowerReceiver(ForgeDirection side) {
		if (BlockGenericPipe.isValid(pipe) && pipe instanceof IPowerReceptor)
			return ((IPowerReceptor) pipe).getPowerReceiver(null);
		else
			return null;
	}

	@Override
	public void doWork(PowerHandler workProvider) {
		if (BlockGenericPipe.isValid(pipe) && pipe instanceof IPowerReceptor) {
			((IPowerReceptor) pipe).doWork(workProvider);
		}
	}

	public void scheduleNeighborChange() {
		blockNeighborChange = true;
	}

	/* IPIPEENTRY */
	@Override
	public void entityEntering(IPipedItem item, ForgeDirection orientation) {
		if (BlockGenericPipe.isValid(pipe)) {
			pipe.transport.entityEntering(item, orientation);
		}
	}

	@Override
	public void entityEntering(ItemStack payload, ForgeDirection orientation) {

		/* FIXME: This is untested guesswork */
		Position itemPos = new Position(xCoord, yCoord, zCoord, orientation);
		itemPos.moveBackwards(1.0);

		itemPos.x += 0.5;
		itemPos.y += 0.25;
		itemPos.z += 0.5;
		itemPos.moveForwards(0.5);

		EntityPassiveItem pipedItem = new EntityPassiveItem(worldObj, itemPos.x, itemPos.y, itemPos.z, payload);
		entityEntering(pipedItem, orientation);
	}

	@Override
	public boolean acceptItems() {
		if (BlockGenericPipe.isValid(pipe))
			return pipe.transport.acceptItems();
		else
			return false;
	}

	/* SMP */
	public void handleDescriptionPacket(PipeRenderStatePacket packet) {
		if (worldObj.isRemote) {
			if (pipe == null && packet.getPipeId() != 0) {
				initialize(BlockGenericPipe.createPipe(packet.getPipeId()));
			}
			renderState = packet.getRenderState();
			worldObj.markBlockForRenderUpdate(xCoord, yCoord, zCoord);
		}
	}

	@Override
	public Packet getDescriptionPacket() {
		bindPipe();

		PacketTileState packet = new PacketTileState(this.xCoord, this.yCoord, this.zCoord);
		if (pipe != null && pipe.gate != null) {
			coreState.gateKind = pipe.gate.kind.ordinal();
		} else {
			coreState.gateKind = 0;
		}

		if (pipe != null && pipe.transport != null) {
			pipe.transport.sendDescriptionPacket();
		}

		packet.addStateForSerialization((byte) 0, coreState);
		packet.addStateForSerialization((byte) 1, renderState);
		if (pipe instanceof IClientState) {
			packet.addStateForSerialization((byte) 2, (IClientState) pipe);
		}
		return packet.getPacket();
	}

	@Override
	public LinkedList<ITrigger> getTriggers() {
		LinkedList<ITrigger> result = new LinkedList<ITrigger>();

		if (BlockGenericPipe.isFullyDefined(pipe) && pipe.hasGate()) {
			result.add(BuildCraftCore.triggerRedstoneActive);
			result.add(BuildCraftCore.triggerRedstoneInactive);
		}

		return result;
	}

	@Override
	public void blockRemoved(ForgeDirection from) {
		// TODO Auto-generated method stub
	}

	private TileBuffer[] getTileCache() {
		if (tileBuffer == null && pipe != null)
			tileBuffer = TileBuffer.makeBuffer(worldObj, xCoord, yCoord, zCoord, pipe.transport.delveIntoUnloadedChunks());
		return tileBuffer;
	}

	@Override
	public void blockCreated(ForgeDirection from, int blockID, TileEntity tile) {
		TileBuffer[] cache = getTileCache();
		if (cache != null)
			cache[from.getOpposite().ordinal()].set(blockID, tile);
	}

	@Override
	public int getBlockId(ForgeDirection to) {
		TileBuffer[] cache = getTileCache();
		if (cache != null)
			return cache[to.ordinal()].getBlockID();
		else
			return 0;
	}

	@Override
	public TileEntity getTile(ForgeDirection to) {
		TileBuffer[] cache = getTileCache();
		if (cache != null)
			return cache[to.ordinal()].getTile();
		else
			return null;
	}

	/**
	 * Checks if this tile is connected to another tile
	 *
	 * @param with - The other Tile
	 * @param side - The orientation to get to the other tile ('with')
	 * @return true if pipes are considered connected
	 */
	protected boolean arePipesConnected(TileEntity with, ForgeDirection side) {
		Pipe pipe1 = pipe;

		if (hasPlug(side))
			return false;

		if (!BlockGenericPipe.isValid(pipe1))
			return false;

		if (with instanceof TileGenericPipe) {
			if (((TileGenericPipe) with).hasPlug(side.getOpposite()))
				return false;
			Pipe pipe2 = ((TileGenericPipe) with).pipe;

			if (!BlockGenericPipe.isValid(pipe2))
				return false;

			if (!pipe2.canPipeConnect(this, side.getOpposite()))
				return false;
		}

		return pipe1 != null ? pipe1.canPipeConnect(with, side) : false;
	}

	private void computeConnections() {
		TileBuffer[] cache = getTileCache();
		if (cache != null) {
			pipeConnectionsBuffer = new boolean[6];

			for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
				TileBuffer t = cache[side.ordinal()];
				t.refresh();

				if (t.getTile() != null) {
					pipeConnectionsBuffer[side.ordinal()] = arePipesConnected(t.getTile(), side);
				}
			}
		}
	}

	@Override
	public boolean isPipeConnected(ForgeDirection with) {
		return pipeConnectionsBuffer[with.ordinal()];
	}

	@Override
	public boolean doDrop() {
		if (BlockGenericPipe.isValid(pipe))
			return pipe.doDrop();
		else
			return false;
	}

	@Override
	public void onChunkUnload() {
		if (pipe != null) {
			pipe.onChunkUnload();
		}
	}

	/**
	 * ITankContainer implementation *
	 */
	@Override
	public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
		if (BlockGenericPipe.isValid(pipe) && pipe.transport instanceof IFluidHandler && !hasPlug(from))
			return ((IFluidHandler) pipe.transport).fill(from, resource, doFill);
		else
			return 0;
	}

	@Override
	public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
		if (BlockGenericPipe.isValid(pipe) && pipe.transport instanceof IFluidHandler && !hasPlug(from))
			return ((IFluidHandler) pipe.transport).drain(from, maxDrain, doDrain);
		else
			return null;
	}

	@Override
	public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
		if (BlockGenericPipe.isValid(pipe) && pipe.transport instanceof IFluidHandler && !hasPlug(from))
			return ((IFluidHandler) pipe.transport).drain(from, resource, doDrain);
		else
			return null;
	}

	@Override
	public boolean canFill(ForgeDirection from, Fluid fluid) {
		if (BlockGenericPipe.isValid(pipe) && pipe.transport instanceof IFluidHandler && !hasPlug(from))
			return ((IFluidHandler) pipe.transport).canFill(from, fluid);
		else
			return false;
	}

	@Override
	public boolean canDrain(ForgeDirection from, Fluid fluid) {
		if (BlockGenericPipe.isValid(pipe) && pipe.transport instanceof IFluidHandler && !hasPlug(from))
			return ((IFluidHandler) pipe.transport).canDrain(from, fluid);
		else
			return false;
	}

	@Override
	public FluidTankInfo[] getTankInfo(ForgeDirection from) {
		return null;
	}

	public void scheduleRenderUpdate() {
		refreshRenderState = true;
	}

	public boolean addFacade(ForgeDirection direction, int blockid, int meta) {
		if (this.worldObj.isRemote)
			return false;
		if (this.facadeBlocks[direction.ordinal()] == blockid)
			return false;

		if (hasFacade(direction)) {
			dropFacadeItem(direction);
		}

		this.facadeBlocks[direction.ordinal()] = blockid;
		this.facadeMeta[direction.ordinal()] = meta;
		worldObj.notifyBlockChange(this.xCoord, this.yCoord, this.zCoord, getBlockId());
		scheduleRenderUpdate();
		return true;
	}

	public boolean hasFacade(ForgeDirection direction) {
		if (this.worldObj.isRemote)
			return renderState.facadeMatrix.getFacadeBlockId(direction) != 0;
		return (this.facadeBlocks[direction.ordinal()] != 0);
	}

	private void dropFacadeItem(ForgeDirection direction) {
		Utils.dropItems(worldObj, ItemFacade.getStack(this.facadeBlocks[direction.ordinal()], this.facadeMeta[direction.ordinal()]), this.xCoord, this.yCoord, this.zCoord);
	}

	public void dropFacade(ForgeDirection direction) {
		if (this.worldObj.isRemote)
			return;
		if (!hasFacade(direction))
			return;
		dropFacadeItem(direction);
		this.facadeBlocks[direction.ordinal()] = 0;
		this.facadeMeta[direction.ordinal()] = 0;
		worldObj.notifyBlockChange(this.xCoord, this.yCoord, this.zCoord, getBlockId());
		scheduleRenderUpdate();
	}

	/**
	 * IPipeRenderState implementation *
	 */
	@Override
	public PipeRenderState getRenderState() {
		return renderState;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public IIconProvider getPipeIcons() {
		if (pipe == null)
			return null;
		return pipe.getIconProvider();
	}

	@Override
	public IClientState getStateInstance(byte stateId) {
		switch (stateId) {
			case 0:
				return coreState;
			case 1:
				return renderState;
			case 2:
				return (IClientState) pipe;
		}
		throw new RuntimeException("Unknown state requested: " + stateId + " this is a bug!");
	}

	@Override
	public void afterStateUpdated(byte stateId) {
		if (!worldObj.isRemote)
			return;

		switch (stateId) {
			case 0:
				if (pipe == null && coreState.pipeId != 0) {
					initialize(BlockGenericPipe.createPipe(coreState.pipeId));
				}
				if (pipe != null && coreState.gateKind != GateKind.None.ordinal()) {
					if (pipe.gate == null) {
						pipe.gate = new GateVanilla(pipe);
					}
					pipe.gate.kind = GateKind.values()[coreState.gateKind];
				}
				break;
		}
		worldObj.markBlockForRenderUpdate(xCoord, yCoord, zCoord);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public double getMaxRenderDistanceSquared() {
		return DefaultProps.PIPE_CONTENTS_RENDER_DIST * DefaultProps.PIPE_CONTENTS_RENDER_DIST;
	}

	@Override
	public boolean shouldRefresh(int oldID, int newID, int oldMeta, int newMeta, World world, int x, int y, int z) {
		return oldID != newID;
	}

	@Override
	public boolean isSolidOnSide(ForgeDirection side) {
		if (hasFacade(side))
			return true;

		if (BlockGenericPipe.isValid(pipe) && pipe instanceof ISolidSideTile) {
			if (((ISolidSideTile) pipe).isSolidOnSide(side))
				return true;
		}
		return false;
	}

	public boolean hasPlug(ForgeDirection side) {
		if(side == null || side == ForgeDirection.UNKNOWN)
			return false;
		if (this.worldObj.isRemote)
			return renderState.plugMatrix.isConnected(side);
		return plugs[side.ordinal()];
	}

	public void removeAndDropPlug(ForgeDirection side) {
		if (!hasPlug(side))
			return;

		plugs[side.ordinal()] = false;
		Utils.dropItems(worldObj, new ItemStack(BuildCraftTransport.plugItem), this.xCoord, this.yCoord, this.zCoord);
		worldObj.notifyBlockChange(this.xCoord, this.yCoord, this.zCoord, getBlockId());
		scheduleNeighborChange(); //To force recalculation of connections
		scheduleRenderUpdate();
	}

	public boolean addPlug(ForgeDirection forgeDirection) {
		if (hasPlug(forgeDirection))
			return false;

		plugs[forgeDirection.ordinal()] = true;
		worldObj.notifyBlockChange(this.xCoord, this.yCoord, this.zCoord, getBlockId());
		scheduleNeighborChange(); //To force recalculation of connections
		scheduleRenderUpdate();
		return true;
	}

	public int getBlockId() {
		Block block = getBlockType();
		if (block != null) {
			return block.blockID;
		}
		return 0;
	}

	@Override
	public World getWorld() {
		return worldObj;
	}

	public boolean isUseableByPlayer(EntityPlayer player) {
		return worldObj.getBlockTileEntity(xCoord, yCoord, zCoord) == this;
	}
}
