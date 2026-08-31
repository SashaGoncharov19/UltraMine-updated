package net.minecraft.world.gen.structure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

/**
 * This core swaps structure data from an NBT compound to a
 * {@code Map<Long, StructureStart>} right after load, and saves it through its own
 * writer. {@code writeToNBT} was left throwing a bare {@code IllegalStateException}
 * on the assumption that nothing else would ever call it.
 *
 * <p>A modpack broke that assumption: a coremod replacing {@code MapStorage.saveData}
 * called it directly, the exception left {@code saveAllWorlds} on the first world,
 * and the sixty dimensions behind it were never saved. A public vanilla method has
 * to answer on every path that reaches it, so what is pinned here is that it
 * answers at all.
 */
public class MapGenStructureDataTest
{
	private static MapGenStructureData inMapMode()
	{
		MapGenStructureData data = new MapGenStructureData("Village");
		data.replaceNbtWithStrictureMap(new HashMap<Long, StructureStart>());
		return data;
	}

	@Test
	public void writeToNBTAnswersWhileTheMapFormIsInUse()
	{
		NBTTagCompound out = new NBTTagCompound();
		inMapMode().writeToNBT(out);
		assertTrue("writeToNBT must produce Features on every path, not throw", out.hasKey("Features"));
	}

	/** What it writes has to be what readFromNBT expects, or the save is unreadable. */
	@Test
	public void whatItWritesReadsBack()
	{
		NBTTagCompound out = new NBTTagCompound();
		inMapMode().writeToNBT(out);

		MapGenStructureData reloaded = new MapGenStructureData("Village");
		reloaded.readFromNBT(out);
		assertEquals(0, reloaded.func_143041_a().func_150296_c().size());
	}

	/** The NBT form still takes precedence when it is the one in use. */
	@Test
	public void theNbtFormIsStillPreferredWhenPresent()
	{
		NBTTagCompound features = new NBTTagCompound();
		features.setTag(MapGenStructureData.func_143042_b(3, -7), new NBTTagCompound());

		NBTTagCompound stored = new NBTTagCompound();
		stored.setTag("Features", features);

		MapGenStructureData data = new MapGenStructureData("Village");
		data.readFromNBT(stored);

		NBTTagCompound out = new NBTTagCompound();
		data.writeToNBT(out);
		assertTrue(out.getCompoundTag("Features").hasKey("[3,-7]"));
	}
}
