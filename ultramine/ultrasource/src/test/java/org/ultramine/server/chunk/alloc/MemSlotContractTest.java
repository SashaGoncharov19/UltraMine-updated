package org.ultramine.server.chunk.alloc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The contract every chunk storage backend has to satisfy, whichever memory it
 * lives in. A section of 16x16x16 blocks is stored as one slot: block ids, their
 * metadata and both light values, packed. These tests pin that layout down -
 * what a slot stores, where the boundaries are, that neighbouring coordinates
 * never bleed into each other, and that the bulk array paths the chunk loader
 * and the chunk packet use agree with the per-coordinate accessors.
 *
 * <p>Written as an abstract class on purpose: the off-heap backend is what
 * UltraMine runs by default, and the heap-backed one exists so that coremods
 * which patch vanilla's chunk arrays can work. If the two ever disagree on any
 * of this, a world would read differently depending on a startup flag.
 */
public abstract class MemSlotContractTest
{
	/** vanilla's ceiling: 8 bits of LSB plus a 4-bit MSB nibble */
	protected static final int MAX_BLOCK_ID = 4095;
	protected static final int MAX_NIBBLE = 15;

	private ChunkAllocService alloc;
	private MemSlot slot;

	/** The backend under test. */
	protected abstract ChunkAllocService createAlloc();

	@Before
	public void setUp()
	{
		alloc = createAlloc();
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

	protected final ChunkAllocService alloc()
	{
		return alloc;
	}

	protected final MemSlot slot()
	{
		return slot;
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
	 * more than this and lift it from the mod side (EndlessIDs/NEID) by replacing
	 * the arrays a section is built from - which is what the heap-backed backend
	 * exists to make possible. If the packed format itself ever grows, this test
	 * is the one that has to change, deliberately.
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

	//
	// The bulk array paths: how chunks are loaded from disk and sent to clients.
	//

	/**
	 * {@code AnvilChunkLoader} hands a section its five arrays straight out of
	 * NBT; the chunk packet reads them straight back out. Whatever a backend does
	 * internally, that round trip has to be lossless and has to agree with the
	 * per-coordinate accessors, or a saved world reads back as a different world.
	 */
	@Test
	public void bulkArraysRoundTripLosslessly()
	{
		byte[] lsb = pattern(4096, 1);
		byte[] msb = pattern(2048, 2);
		byte[] meta = pattern(2048, 3);
		byte[] blockLight = pattern(2048, 4);
		byte[] skyLight = pattern(2048, 5);

		slot.setData(lsb, msb, meta, blockLight, skyLight);

		assertArrayEquals("LSB round-trip", lsb, slot.copyLSB());
		assertArrayEquals("MSB round-trip", msb, slot.copyMSB());
		assertArrayEquals("metadata round-trip", meta, slot.copyBlockMetadata());
		assertArrayEquals("block light round-trip", blockLight, slot.copyBlocklight());
		assertArrayEquals("sky light round-trip", skyLight, slot.copySkylight());
	}

	/** The bulk arrays and the per-coordinate accessors are the same data. */
	@Test
	public void bulkArraysAgreeWithPerCoordinateAccessors()
	{
		byte[] lsb = pattern(4096, 7);
		byte[] msb = pattern(2048, 11);
		byte[] meta = pattern(2048, 13);
		byte[] blockLight = pattern(2048, 17);
		byte[] skyLight = pattern(2048, 19);

		slot.setData(lsb, msb, meta, blockLight, skyLight);

		for(int y = 0; y < 16; y += 5)
			for(int z = 0; z < 16; z += 3)
				for(int x = 0; x < 16; x += 2)
				{
					int index = y << 8 | z << 4 | x;
					int expectedId = (lsb[index] & 255) | (nibble(msb, index) << 8);
					assertEquals("block id at " + x + "," + y + "," + z, expectedId, slot.getBlockId(x, y, z));
					assertEquals("metadata at " + x + "," + y + "," + z, nibble(meta, index), slot.getMeta(x, y, z));
					assertEquals("block light at " + x + "," + y + "," + z, nibble(blockLight, index), slot.getBlocklight(x, y, z));
					assertEquals("sky light at " + x + "," + y + "," + z, nibble(skyLight, index), slot.getSkylight(x, y, z));
				}
	}

	/**
	 * The chunk packet packs many sections into one buffer, and the chunk loader
	 * reads them back out of one: every bulk accessor takes an offset, and must
	 * touch exactly its own window of the caller's array.
	 */
	@Test
	public void bulkAccessorsHonourTheOffsetAndStayInsideTheirWindow()
	{
		byte[] lsb = pattern(4096, 23);
		byte[] meta = pattern(2048, 29);

		byte[] source = new byte[16 + 4096 + 16];
		System.arraycopy(lsb, 0, source, 16, 4096);
		slot.setLSB(source, 16);
		byte[] metaSource = new byte[8 + 2048 + 8];
		System.arraycopy(meta, 0, metaSource, 8, 2048);
		slot.setBlockMetadata(metaSource, 8);

		byte[] target = new byte[32 + 4096 + 32];
		Arrays.fill(target, (byte)0x5A);
		slot.copyLSB(target, 32);
		assertArrayEquals("LSB written at the offset", lsb, Arrays.copyOfRange(target, 32, 32 + 4096));
		for(int i = 0; i < 32; i++)
			assertEquals("byte before the window at " + i, (byte)0x5A, target[i]);
		for(int i = 32 + 4096; i < target.length; i++)
			assertEquals("byte after the window at " + i, (byte)0x5A, target[i]);

		byte[] metaTarget = new byte[4 + 2048 + 4];
		Arrays.fill(metaTarget, (byte)0x5A);
		slot.copyBlockMetadata(metaTarget, 4);
		assertArrayEquals("metadata written at the offset", meta, Arrays.copyOfRange(metaTarget, 4, 4 + 2048));
	}

	@Test
	public void setDataWithoutMSBOrSkylightClearsThem()
	{
		slot.setData(pattern(4096, 31), pattern(2048, 37), pattern(2048, 41), pattern(2048, 43), pattern(2048, 47));

		slot.setData(pattern(4096, 31), null, pattern(2048, 41), pattern(2048, 43), null);

		assertArrayEquals("MSB cleared when the chunk has no Add array", new byte[2048], slot.copyMSB());
		assertArrayEquals("sky light cleared when the dimension has none", new byte[2048], slot.copySkylight());
	}

	@Test
	public void zerofillMSBClearsOnlyTheHighIdBits()
	{
		byte[] lsb = pattern(4096, 53);
		byte[] meta = pattern(2048, 59);
		byte[] blockLight = pattern(2048, 61);
		byte[] skyLight = pattern(2048, 67);
		slot.setData(lsb, pattern(2048, 71), meta, blockLight, skyLight);

		slot.zerofillMSB();

		assertArrayEquals(new byte[2048], slot.copyMSB());
		assertArrayEquals("LSB untouched", lsb, slot.copyLSB());
		assertArrayEquals("metadata untouched", meta, slot.copyBlockMetadata());
		assertArrayEquals("block light untouched", blockLight, slot.copyBlocklight());
		assertArrayEquals("sky light untouched", skyLight, slot.copySkylight());
	}

	@Test
	public void zerofillSkylightClearsOnlySkylight()
	{
		byte[] lsb = pattern(4096, 73);
		byte[] msb = pattern(2048, 79);
		byte[] meta = pattern(2048, 83);
		byte[] blockLight = pattern(2048, 89);
		slot.setData(lsb, msb, meta, blockLight, pattern(2048, 97));

		slot.zerofillSkylight();

		assertArrayEquals(new byte[2048], slot.copySkylight());
		assertArrayEquals("LSB untouched", lsb, slot.copyLSB());
		assertArrayEquals("MSB untouched", msb, slot.copyMSB());
		assertArrayEquals("metadata untouched", meta, slot.copyBlockMetadata());
		assertArrayEquals("block light untouched", blockLight, slot.copyBlocklight());
	}

	/**
	 * A short array here means a corrupted chunk on disk or a truncated packet.
	 * Failing loudly beats writing past the end of a section.
	 */
	@Test
	public void bulkSettersRejectArraysThatAreTooShort()
	{
		try
		{
			slot.setLSB(new byte[4095]);
			fail("a 4095-byte LSB array must be rejected");
		}
		catch(IllegalArgumentException expected)
		{
			// the point
		}

		try
		{
			slot.setBlockMetadata(new byte[2047]);
			fail("a 2047-byte metadata array must be rejected");
		}
		catch(IllegalArgumentException expected)
		{
			// the point
		}

		try
		{
			slot.setSkylight(new byte[2048], 1);
			fail("an offset that runs past the end must be rejected");
		}
		catch(IllegalArgumentException expected)
		{
			// the point
		}
	}

	/** Deterministic, non-trivial bytes: every value occurs, no run is uniform. */
	protected static byte[] pattern(int length, int seed)
	{
		byte[] arr = new byte[length];
		for(int i = 0; i < length; i++)
			arr[i] = (byte)((i * 31 + seed * 17) & 0xFF);
		return arr;
	}

	protected static int nibble(byte[] arr, int index)
	{
		byte b = arr[index >> 1];
		return (index & 1) == 0 ? b & 15 : b >> 4 & 15;
	}
}
