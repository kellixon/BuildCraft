/**
 * Copyright (c) 2011-2014, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package buildcraft.api.gates;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import buildcraft.api.transport.IPipeTile;

public final class StatementManager {

	public static Map<String, IStatement> statements = new HashMap<String, IStatement>();
	public static Map<String, Class<? extends IStatementParameter>> parameters = new HashMap<String, Class<? extends IStatementParameter>>();
	private static List<ITriggerProvider> triggerProviders = new LinkedList<ITriggerProvider>();
	private static List<IActionProvider> actionProviders = new LinkedList<IActionProvider>();

	/**
	 * Deactivate constructor
	 */
	private StatementManager() {
	}

	public static void registerTriggerProvider(ITriggerProvider provider) {
		if (provider != null && !triggerProviders.contains(provider)) {
			triggerProviders.add(provider);
		}
	}

	public static void registerActionProvider(IActionProvider provider) {
		if (provider != null && !actionProviders.contains(provider)) {
			actionProviders.add(provider);
		}
	}

	public static void registerStatement(IStatement statement) {
		statements.put(statement.getUniqueTag(), statement);
	}

	public static void registerParameterClass(Class<? extends IStatementParameter> param) {
		parameters.put(createParameter(param).getUniqueTag(), param);
	}

	public static List<ITrigger> getExternalTriggers(ForgeDirection side, TileEntity entity) {
		List<ITrigger> result = new LinkedList<ITrigger>();

		for (ITriggerProvider provider : triggerProviders) {
			Collection<ITrigger> toAdd = provider.getExternalTriggers(side, entity);

			if (toAdd != null) {
				for (ITrigger t : toAdd) {
					if (!result.contains(t)) {
						result.add(t);
					}
				}
			}
		}

		return result;
	}

	public static List<IAction> getExternalActions(ForgeDirection side, TileEntity entity) {
		List<IAction> result = new LinkedList<IAction>();

		for (IActionProvider provider : actionProviders) {
			Collection<IAction> toAdd = provider.getExternalActions(side, entity);

			if (toAdd != null) {
				for (IAction t : toAdd) {
					if (!result.contains(t)) {
						result.add(t);
					}
				}
			}
		}

		return result;
	}

	public static List<ITrigger> getInternalTriggers(TileEntity tile) {
		List<ITrigger> result = new LinkedList<ITrigger>();

		for (ITriggerProvider provider : triggerProviders) {
			Collection<ITrigger> toAdd = provider.getInternalTriggers(tile);

			if (toAdd != null) {
				for (ITrigger t : toAdd) {
					if (!result.contains(t)) {
						result.add(t);
					}
				}
			}
		}

		return result;
	}

	public static List<IAction> getInternalActions(TileEntity tile) {
		List<IAction> result = new LinkedList<IAction>();

		for (IActionProvider provider : actionProviders) {
			Collection<IAction> toAdd = provider.getInternalActions(tile);

			if (toAdd != null) {
				for (IAction t : toAdd) {
					if (!result.contains(t)) {
						result.add(t);
					}
				}
			}
		}

		return result;
	}

	public static IStatementParameter createParameter(String kind) {
		return createParameter(parameters.get(kind));
	}
	
	private static IStatementParameter createParameter(Class<? extends IStatementParameter> param) {
		try {
			return param.newInstance();
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}

		return null;
	}
}
