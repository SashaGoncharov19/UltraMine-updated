package net.minecraft.world.gen.structure;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldSavedData;

import java.util.Map;

public class MapGenStructureData extends WorldSavedData
{
	private NBTTagCompound field_143044_a = new NBTTagCompound();
	private static final String __OBFID = "CL_00000510";

	public MapGenStructureData(String p_i43001_1_)
	{
		super(p_i43001_1_);
	}

	public void readFromNBT(NBTTagCompound p_76184_1_)
	{
		this.field_143044_a = p_76184_1_.getCompoundTag("Features");
	}

	public void writeToNBT(NBTTagCompound p_76187_1_)
	{
		if(this.field_143044_a != null)
		{
			p_76187_1_.setTag("Features", this.field_143044_a);
			return;
		}

		/*
		 * ultramine: once the structures have been handed over as a map the NBT
		 * form is dropped, and the core saves through UMHooks instead - MapStorage
		 * routes MapGenStructureData there before it ever gets here. Throwing on
		 * this path assumed nothing else would ever call it, and that assumption
		 * does not survive contact with a modpack: a coremod that replaces
		 * MapStorage.saveData calls writeToNBT directly, the exception leaves
		 * saveAllWorlds on the first world, and every world after it goes unsaved.
		 *
		 * A public vanilla method has to answer, so build the compound the NBT form
		 * would have held. Same key layout as UMHooks.writeStructureMap, so either
		 * writer produces a file the other can read back.
		 */
		NBTTagCompound features = new NBTTagCompound();

		if(structureMap != null)
		{
			for(Map.Entry<Long, StructureStart> ent : structureMap.entrySet())
			{
				long key = ent.getKey();
				int x = (int)(key & 0xFFFFFFFFL);
				int z = (int)(key >> 32);
				features.setTag(func_143042_b(x, z), ent.getValue().func_143021_a(x, z));
			}
		}

		p_76187_1_.setTag("Features", features);
	}

	public void func_143043_a(NBTTagCompound p_143043_1_, int p_143043_2_, int p_143043_3_)
	{
		if(this.field_143044_a != null)
			this.field_143044_a.setTag(func_143042_b(p_143043_2_, p_143043_3_), p_143043_1_);
	}

	public static String func_143042_b(int p_143042_0_, int p_143042_1_)
	{
		return "[" + p_143042_0_ + "," + p_143042_1_ + "]";
	}

	public NBTTagCompound func_143041_a()
	{
		return this.field_143044_a;
	}

	/*======================================== ULTRAMINE START =====================================*/

	private Map<Long, StructureStart> structureMap;

	public void replaceNbtWithStrictureMap(Map<Long, StructureStart> structureMap)
	{
		this.structureMap = structureMap;
		this.field_143044_a = null;
	}

	public Map<Long, StructureStart> getStructureMap()
	{
		return structureMap;
	}
}