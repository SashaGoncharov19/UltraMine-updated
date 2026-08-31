package org.ultramine.server.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.DataWatcher.WatchableObject;

import org.junit.Before;
import org.junit.Test;

/**
 * This storage has two audiences that must never disagree: the core reads it as
 * an id-indexed array on the per-entity path, while mods reach the same data
 * through the vanilla-shaped {@code Map} field (CoreTweaks' diagnostics shadow it
 * as one, and fail the whole DataWatcher class if it is not there). It also has
 * to grow past vanilla's 32 ids for mods that lift that ceiling.
 */
public class WatchableObjectMapTest
{
	private WatchableObjectMap map;

	@Before
	public void setUp()
	{
		map = new WatchableObjectMap();
	}

	private static WatchableObject obj(int id, Object value)
	{
		return new WatchableObject(0, id, value);
	}

	@Test
	public void startsEmpty()
	{
		assertEquals(0, map.size());
		assertTrue(map.isEmpty());
		assertNull(map.getById(0));
	}

	@Test
	public void readsBackWhatItStores()
	{
		map.putById(0, obj(0, "first"));
		map.putById(31, obj(31, "last"));

		assertEquals("first", map.getById(0).getObject());
		assertEquals("last", map.getById(31).getObject());
		assertEquals(2, map.size());
	}

	/** What a mod sees: the vanilla Map contract over the same data. */
	@Test
	public void behavesAsAMapForMods()
	{
		map.putById(5, obj(5, "value"));
		Map<Integer, WatchableObject> asMap = map;

		assertTrue(asMap.containsKey(Integer.valueOf(5)));
		assertFalse(asMap.containsKey(Integer.valueOf(6)));
		assertEquals("value", asMap.get(Integer.valueOf(5)).getObject());
		assertNull(asMap.get(Integer.valueOf(6)));
		assertEquals(1, asMap.size());
	}

	@Test
	public void mapWritesAndRemovalsGoThroughToTheArray()
	{
		Map<Integer, WatchableObject> asMap = map;
		asMap.put(Integer.valueOf(7), obj(7, "via map"));
		assertEquals("via map", map.getById(7).getObject());

		assertEquals("remove returns the previous value", "via map", asMap.remove(Integer.valueOf(7)).getObject());
		assertNull(map.getById(7));
		assertEquals(0, asMap.size());
	}

	/** Mods such as EndlessIDs raise the id ceiling well past vanilla's 32. */
	@Test
	public void growsPastTheVanillaCeilingKeepingWhatItHeld()
	{
		map.putById(0, obj(0, "low"));
		map.putById(31, obj(31, "edge"));

		map.putById(200, obj(200, "high"));

		assertEquals("low", map.getById(0).getObject());
		assertEquals("edge", map.getById(31).getObject());
		assertEquals("high", map.getById(200).getObject());
		assertEquals(3, map.size());
		assertTrue("array must have grown to hold id 200", map.array().length > 200);
	}

	@Test
	public void overwritingAnIdDoesNotChangeTheCount()
	{
		map.putById(3, obj(3, "before"));
		map.putById(3, obj(3, "after"));

		assertEquals("after", map.getById(3).getObject());
		assertEquals(1, map.size());
	}

	@Test
	public void entrySetIteratesOnlyOccupiedIdsInOrder()
	{
		map.putById(2, obj(2, "a"));
		map.putById(9, obj(9, "b"));
		map.putById(40, obj(40, "c"));

		Set<Integer> seen = new HashSet<Integer>();
		int previous = -1;
		for(Map.Entry<Integer, WatchableObject> e : map.entrySet())
		{
			assertTrue("entrySet must be ordered by id", e.getKey().intValue() > previous);
			previous = e.getKey().intValue();
			seen.add(e.getKey());
		}

		assertEquals(3, seen.size());
		assertTrue(seen.contains(Integer.valueOf(2)) && seen.contains(Integer.valueOf(9)) && seen.contains(Integer.valueOf(40)));
	}

	/** The core's fast path: a plain array walk, nulls for empty ids. */
	@Test
	public void arrayFastPathSeesTheSameEntries()
	{
		map.putById(1, obj(1, "x"));
		map.putById(4, obj(4, "y"));

		int found = 0;
		for(WatchableObject o : map.array())
			if(o != null)
				found++;

		assertEquals(2, found);
	}

	@Test
	public void negativeIdsAreRejectedRatherThanCorruptingMemory()
	{
		try
		{
			map.putById(-1, obj(0, "nope"));
			org.junit.Assert.fail("a negative id must not be accepted");
		}
		catch(IndexOutOfBoundsException expected)
		{
			// the point: it throws instead of writing outside the array
		}
		assertNull(map.getById(-1));
	}
}
