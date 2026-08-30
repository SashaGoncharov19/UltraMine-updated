package org.ultramine.server.chunk.alloc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ultramine.server.chunk.alloc.unsafe.UnsafeChunkAlloc;

/**
 * Off-heap chunk storage is the core's most load-bearing piece of memory layout:
 * every block, its metadata and both light values of a 16x16x16 section live in
 * one packed slot. These tests pin the layout down - what a slot stores, where
 * the boundaries are, and that neighbouring coordinates never bleed into each
 * other - so that changing the format (widening block ids, for one) has to prove
 * itself against the behaviour that exists today.
 */
public class MemSlotTest
{
	/** vanilla's ceiling: 8 bits of LSB plus a 4-bit MSB nibble */
	private static final int MAX_BLOCK_ID = 4095;
	private static final int MAX_NIBBLE = 15;

	private UnsafeChunkAlloc alloc;
	private MemSlot slot;

	@Before
	public void setUp()
	{
		alloc = new UnsafeChunkAlloc();
		slot = alloc.allocateSlot();
		assertNotNull("allocator returned no slot", slot);
		slot.zerofillAll();
	}

	@After
	public void tearDown()
	{
		if(slot != null)
			slot.release();
	}

	@Test
	public void storesEveryBlockIdUpToTheVanillaCeiling()
	{
		for(int id = 0; id <= MAX_BLOCK_ID; id++)
		{
			slot.setBlockId(1, 2, 3, id);
			assertEquals("block id round-trip", id, slot.getBlockId(1, 2, 3));
		}
	}

	@Test
	public void storesEveryMetadataValue()
	{
		for(int meta = 0; meta <= MAX_NIBBLE; meta++)
		{
			slot.setMeta(4, 5, 6, meta);
			assertEquals("metadata round-trip", meta, slot.getMeta(4, 5, 6));
		}
	}

	@Test
	public void storesBothLightValuesIndependently()
	{
		for(int light = 0; light <= MAX_NIBBLE; light++)
		{
			slot.setBlocklight(7, 8, 9, light);
			slot.setSkylight(7, 8, 9, MAX_NIBBLE - light);
			assertEquals("block light round-trip", light, slot.getBlocklight(7, 8, 9));
			assertEquals("sky light round-trip", MAX_NIBBLE - light, slot.getSkylight(7, 8, 9));
		}
	}

	@Test
	public void blockIdAndMetadataShareACoordinateWithoutColliding()
	{
		slot.setBlockIdAndMeta(10, 11, 12, MAX_BLOCK_ID, MAX_NIBBLE);
		assertEquals(MAX_BLOCK_ID, slot.getBlockId(10, 11, 12));
		assertEquals(MAX_NIBBLE, slot.getMeta(10, 11, 12));
		//the packed accessor keeps metadata above the 12 id bits
		assertEquals(MAX_BLOCK_ID | (MAX_NIBBLE << 12), slot.getBlockIdAndMeta(10, 11, 12));
	}

	/**
	 * Writing one coordinate must not disturb its neighbours - the nibble-packed
	 * halves (two coordinates per byte) are where that goes wrong first.
	 */
	@Test
	public void neighbouringCoordinatesDoNotBleedIntoEachOther()
	{
		for(int x = 0; x < 16; x++)
		{
			slot.setBlockIdAndMeta(x, 0, 0, x + 100, x % 16);
			slot.setBlocklight(x, 0, 0, x % 16);
			slot.setSkylight(x, 0, 0, (15 - x) % 16);
		}
		for(int x = 0; x < 16; x++)
		{
			assertEquals("block id at x=" + x, x + 100, slot.getBlockId(x, 0, 0));
			assertEquals("metadata at x=" + x, x % 16, slot.getMeta(x, 0, 0));
			assertEquals("block light at x=" + x, x % 16, slot.getBlocklight(x, 0, 0));
			assertEquals("sky light at x=" + x, (15 - x) % 16, slot.getSkylight(x, 0, 0));
		}
	}

	/** Every coordinate of the section must be addressable and independent. */
	@Test
	public void addressesTheWholeSection()
	{
		for(int y = 0; y < 16; y++)
			for(int z = 0; z < 16; z++)
				for(int x = 0; x < 16; x++)
					slot.setBlockId(x, y, z, (x + z * 16 + y * 256) % (MAX_BLOCK_ID + 1));

		for(int y = 0; y < 16; y++)
			for(int z = 0; z < 16; z++)
				for(int x = 0; x < 16; x++)
					assertEquals("block id at " + x + "," + y + "," + z,
							(x + z * 16 + y * 256) % (MAX_BLOCK_ID + 1), slot.getBlockId(x, y, z));
	}

	/**
	 * Documents today's ceiling rather than endorsing it: ids are stored in 12
	 * bits, so anything above 4095 is silently truncated. Modern large packs need
	 * more than this, and when the format grows this test is the one that has to
	 * change - deliberately.
	 */
	@Test
	public void blockIdsAboveTheCeilingAreTruncatedToTwelveBits()
	{
		slot.setBlockId(0, 0, 0, 4096);
		assertEquals("4096 does not fit in 12 bits", 0, slot.getBlockId(0, 0, 0));

		slot.setBlockId(0, 0, 0, 10617); // the id GT New Horizons asks for
		assertEquals("10617 wraps within 12 bits", 10617 & 0xFFF, slot.getBlockId(0, 0, 0));
	}

	@Test
	public void zerofillClearsEverything()
	{
		slot.setBlockIdAndMeta(3, 3, 3, 1234, 7);
		slot.setBlocklight(3, 3, 3, 9);
		slot.setSkylight(3, 3, 3, 11);

		slot.zerofillAll();

		assertEquals(0, slot.getBlockId(3, 3, 3));
		assertEquals(0, slot.getMeta(3, 3, 3));
		assertEquals(0, slot.getBlocklight(3, 3, 3));
		assertEquals(0, slot.getSkylight(3, 3, 3));
	}

	@Test
	public void copiedSlotsAreIndependent()
	{
		slot.setBlockIdAndMeta(5, 5, 5, 2048, 3);

		MemSlot copy = alloc.allocateSlot();
		try
		{
			copy.copyFrom(slot);
			assertEquals("copy carries the data", 2048, copy.getBlockId(5, 5, 5));
			assertEquals(3, copy.getMeta(5, 5, 5));

			copy.setBlockId(5, 5, 5, 7);
			assertEquals("writing the copy must not touch the original", 2048, slot.getBlockId(5, 5, 5));
		}
		finally
		{
			copy.release();
		}
	}
}
