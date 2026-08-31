package org.ultramine.server.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.minecraft.world.ChunkCoordIntPair;
import net.openhft.koloboke.collect.set.IntSet;
import net.openhft.koloboke.collect.set.hash.HashIntSets;

import org.junit.Before;
import org.junit.Test;
import org.ultramine.server.chunk.ChunkHash;

/**
 * The core keys chunks by a packed int; mods reach for vanilla's
 * {@code Set<Long>} of packed longs. This is the adapter between them, and it
 * is the unload queue: a mod that adds a chunk to it expects that chunk to
 * unload, and one that removes a chunk expects it to stay. If the two encodings
 * ever drift, that stops being true silently - the wrong chunk unloads.
 */
public class VanillaChunkHashSetTest
{
	private IntSet backing;
	private VanillaChunkHashSet set;

	@Before
	public void setUp()
	{
		backing = HashIntSets.newMutableSet();
		set = new VanillaChunkHashSet(backing);
	}

	private static long vanillaKey(int x, int z)
	{
		return ChunkCoordIntPair.chunkXZ2Int(x, z);
	}

	@Test
	public void startsEmpty()
	{
		assertTrue(set.isEmpty());
		assertEquals(0, set.size());
		assertFalse(set.contains(Long.valueOf(vanillaKey(0, 0))));
	}

	/** What a mod adds, the core sees - under the core's own key. */
	@Test
	public void additionsThroughTheVanillaViewLandInTheBackingSet()
	{
		set.add(Long.valueOf(vanillaKey(3, -7)));

		assertEquals(1, backing.size());
		assertTrue("the core's key for the same chunk", backing.contains(ChunkHash.chunkToKey(3, -7)));
		assertTrue(set.contains(Long.valueOf(vanillaKey(3, -7))));
	}

	/** And what the core adds, a mod sees - as vanilla's key. */
	@Test
	public void additionsToTheBackingSetAreVisibleThroughTheVanillaView()
	{
		backing.add(ChunkHash.chunkToKey(-12, 40));

		assertEquals(1, set.size());
		assertTrue(set.contains(Long.valueOf(vanillaKey(-12, 40))));

		Iterator<Long> it = set.iterator();
		assertTrue(it.hasNext());
		assertEquals("iterated as vanilla's packed long", vanillaKey(-12, 40), it.next().longValue());
		assertFalse(it.hasNext());
	}

	@Test
	public void removalThroughTheVanillaViewClearsTheBackingKey()
	{
		set.add(Long.valueOf(vanillaKey(5, 5)));
		assertTrue(set.remove(Long.valueOf(vanillaKey(5, 5))));

		assertTrue(backing.isEmpty());
		assertFalse("removing again reports nothing removed", set.remove(Long.valueOf(vanillaKey(5, 5))));
	}

	/**
	 * Vanilla's Set contract: a key of the wrong type is simply absent, not a
	 * ClassCastException. Mods do pass odd things to contains().
	 */
	@Test
	public void foreignKeyTypesAreAbsentRatherThanFatal()
	{
		set.add(Long.valueOf(vanillaKey(1, 1)));

		assertFalse(set.contains("not a key"));
		assertFalse(set.contains(Integer.valueOf(0)));
		assertFalse(set.remove("not a key"));
		assertEquals(1, set.size());
	}

	@Test
	public void everyCoordinateSurvivesTheRoundTripBothWays()
	{
		int[] coords = {0, 1, -1, 31, -31, 511, -512, 32767, -32768};
		for(int x : coords)
			for(int z : coords)
			{
				backing.clear();
				set.add(Long.valueOf(vanillaKey(x, z)));
				Long back = set.iterator().next();
				assertEquals("round trip at " + x + "," + z, vanillaKey(x, z), back.longValue());
			}
	}

	@Test
	public void bulkOperationsAgreeWithTheSingleOnes()
	{
		Set<Long> keys = new HashSet<Long>(Arrays.asList(
				Long.valueOf(vanillaKey(1, 1)), Long.valueOf(vanillaKey(2, 2)), Long.valueOf(vanillaKey(3, 3))));

		assertTrue(set.addAll(keys));
		assertEquals(3, set.size());
		assertTrue(set.containsAll(keys));

		assertTrue(set.removeAll(new HashSet<Long>(Arrays.asList(Long.valueOf(vanillaKey(2, 2))))));
		assertEquals(2, set.size());
		assertFalse(set.contains(Long.valueOf(vanillaKey(2, 2))));

		assertTrue(set.retainAll(new HashSet<Long>(Arrays.asList(Long.valueOf(vanillaKey(3, 3))))));
		assertEquals(1, set.size());
		assertTrue(set.contains(Long.valueOf(vanillaKey(3, 3))));
	}

	@Test
	public void toArrayReturnsTheVanillaKeys()
	{
		set.add(Long.valueOf(vanillaKey(8, -8)));

		Object[] array = set.toArray();
		assertEquals(1, array.length);
		assertEquals(Long.valueOf(vanillaKey(8, -8)), array[0]);
	}

	@Test
	public void clearEmptiesBothViews()
	{
		set.add(Long.valueOf(vanillaKey(4, 4)));
		set.clear();

		assertTrue(set.isEmpty());
		assertTrue(backing.isEmpty());
	}
}
