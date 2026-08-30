package org.ultramine.server.chunk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Every chunk in memory is keyed by one of these ints - the loaded-chunk map,
 * the unload queue, the save queue and the chunk GC all address chunks through
 * them. A packing mistake here does not fail loudly; it silently makes two
 * different chunks the same chunk.
 */
public class ChunkHashTest
{
	@Test
	public void chunkKeysRoundTripAcrossTheSignedRange()
	{
		int[] coords = {0, 1, -1, 15, -15, 255, -256, 1000, -1000, 32767, -32768};
		for(int x : coords)
			for(int z : coords)
			{
				int key = ChunkHash.chunkToKey(x, z);
				assertEquals("x round-trip at " + x + "," + z, x, ChunkHash.keyToX(key));
				assertEquals("z round-trip at " + x + "," + z, z, ChunkHash.keyToZ(key));
			}
	}

	@Test
	public void nearbyChunksNeverShareAKey()
	{
		Set<Integer> keys = new HashSet<Integer>();
		for(int x = -40; x <= 40; x++)
			for(int z = -40; z <= 40; z++)
				assertTrue("duplicate key at " + x + "," + z, keys.add(Integer.valueOf(ChunkHash.chunkToKey(x, z))));
		assertEquals(81 * 81, keys.size());
	}

	/**
	 * The boundary this packing has, stated rather than discovered later: a chunk
	 * coordinate is kept in 16 bits, so it holds -32768..32767 - about half a
	 * million blocks either side of origin. Past that, coordinates wrap and two
	 * genuinely different chunks map to one key. Minecraft's own limit is far
	 * beyond that, so a server that lets players travel past ~524k blocks needs a
	 * wider key, not a bigger world border.
	 */
	@Test
	public void chunkCoordinatesOutsideSixteenBitsAliasOntoEachOther()
	{
		assertEquals("32768 wraps onto -32768", ChunkHash.chunkToKey(-32768, 0), ChunkHash.chunkToKey(32768, 0));
		//chunk 62500 is block x = 1,000,000
		assertEquals(ChunkHash.chunkToKey(62500 - 65536, 7), ChunkHash.chunkToKey(62500, 7));
		assertEquals("and it reads back as the low coordinate", 62500 - 65536, ChunkHash.keyToX(ChunkHash.chunkToKey(62500, 7)));
	}

	@Test
	public void worldKeysSeparateDimensionsThatShareCoordinates()
	{
		assertNotEquals(ChunkHash.worldChunkToKey(0, 10, 20), ChunkHash.worldChunkToKey(-1, 10, 20));
		assertNotEquals(ChunkHash.worldChunkToKey(0, 10, 20), ChunkHash.worldChunkToKey(1, 10, 20));
		assertEquals(ChunkHash.worldChunkToKey(7, -3, 4), ChunkHash.worldChunkToKey(7, -3, 4));
	}

	/** Every position inside a section must get its own hash - all 65536 of them. */
	@Test
	public void inSectionHashesAreUnique()
	{
		Set<Short> seen = new HashSet<Short>();
		for(int y = 0; y < 256; y++)
			for(int z = 0; z < 16; z++)
				for(int x = 0; x < 16; x++)
					assertTrue("duplicate at " + x + "," + y + "," + z,
							seen.add(Short.valueOf(ChunkHash.chunkCoordToHash(x, y, z))));
		assertEquals(256 * 16 * 16, seen.size());
	}

	@Test
	public void blockKeysRoundTripIncludingNegativeCoordinates()
	{
		int[] horizontal = {0, 1, -1, 255, -255, 100000, -100000, 8388607, -8388608};
		int[] heights = {0, 1, 63, 128, 255};
		for(int x : horizontal)
			for(int z : horizontal)
				for(int y : heights)
				{
					long key = ChunkHash.blockCoordToHash(x, y, z);
					assertEquals("x at " + x + "," + y + "," + z, x, ChunkHash.blockKeyToX(key));
					assertEquals("y at " + x + "," + y + "," + z, y, ChunkHash.blockKeyToY(key));
					assertEquals("z at " + x + "," + y + "," + z, z, ChunkHash.blockKeyToZ(key));
				}
	}

	@Test
	public void distinctBlockPositionsGetDistinctKeys()
	{
		Set<Long> keys = new HashSet<Long>();
		for(int x = -3; x <= 3; x++)
			for(int y = 0; y < 256; y += 37)
				for(int z = -3; z <= 3; z++)
					assertTrue("duplicate block key at " + x + "," + y + "," + z,
							keys.add(Long.valueOf(ChunkHash.blockCoordToHash(x, y, z))));
	}
}
