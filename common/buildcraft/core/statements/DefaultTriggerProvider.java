/**
 * Copyright (c) 2011-2014, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package buildcraft.core.statements;

import java.util.LinkedList;

import net.minecraft.block.Block;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;

import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import cofh.api.energy.IEnergyHandler;

import buildcraft.BuildCraftCore;
import buildcraft.api.gates.ITrigger;
import buildcraft.api.gates.ITriggerProvider;
import buildcraft.api.transport.IPipeTile;
import buildcraft.core.IMachine;

public class DefaultTriggerProvider implements ITriggerProvider {

	@Override
	public LinkedList<ITrigger> getExternalTriggers(ForgeDirection side, TileEntity tile) {
		LinkedList<ITrigger> res = new LinkedList<ITrigger>();

		if (tile instanceof IInventory && ((IInventory) tile).getSizeInventory() > 0) {
			res.add(BuildCraftCore.triggerEmptyInventory);
			res.add(BuildCraftCore.triggerContainsInventory);
			res.add(BuildCraftCore.triggerSpaceInventory);
			res.add(BuildCraftCore.triggerFullInventory);
			res.add(BuildCraftCore.triggerInventoryBelow25);
			res.add(BuildCraftCore.triggerInventoryBelow50);
			res.add(BuildCraftCore.triggerInventoryBelow75);
		}

		if (tile instanceof IFluidHandler) {
			FluidTankInfo[] tanks = ((IFluidHandler) tile).getTankInfo(side.getOpposite());
			if (tanks != null && tanks.length > 0) {
				res.add(BuildCraftCore.triggerEmptyFluid);
				res.add(BuildCraftCore.triggerContainsFluid);
				res.add(BuildCraftCore.triggerSpaceFluid);
				res.add(BuildCraftCore.triggerFullFluid);
				res.add(BuildCraftCore.triggerFluidContainerBelow25);
				res.add(BuildCraftCore.triggerFluidContainerBelow50);
				res.add(BuildCraftCore.triggerFluidContainerBelow75);
			}
		}

		if (tile instanceof IMachine) {
			res.add(BuildCraftCore.triggerMachineActive);
			res.add(BuildCraftCore.triggerMachineInactive);
		}

		if (tile instanceof IEnergyHandler && ((IEnergyHandler) tile).getMaxEnergyStored(side.getOpposite()) > 0) {
			res.add(BuildCraftCore.triggerEnergyHigh);
			res.add(BuildCraftCore.triggerEnergyLow);
		}

		return res;
	}

	@Override
	public LinkedList<ITrigger> getInternalTriggers(TileEntity pipe) {
		return null;
	}
}
