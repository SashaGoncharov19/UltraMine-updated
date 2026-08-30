package org.ultramine.server.chunk.alloc.heap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.Test;
import org.ultramine.server.chunk.alloc.ChunkAllocService;
import org.ultramine.server.chunk.alloc.MemSlot;
import org.ultramine.server.chunk.alloc.MemSlotContractTest;

/**
 * The compatibility backend. It stores a section in the same five heap arrays
 * vanilla uses, so that coremods which patch chunk storage - ChunkAPI and its
 * clients EndlessIDs/NEID, and Phosphor's lighting - have the arrays they
 * expect to shadow, extend and replace. It must satisfy the same contract as
 * the off-heap backend, and on top of that expose those arrays *live*: a mod
 * writing through the array must be writing the section itself, not a copy.
 */
public class HeapMemSlotTest extends MemSlotContractTest
{
	@Override
	protected ChunkAllocService createAlloc()
	{
		return new HeapChunkAlloc();
	}

	private HeapMemSlot heapSlot()
	{
		return (HeapMemSlot)slot();
	}

	@Test
	public void exposesArraysOfExactlyTheVanillaSizes()
	{
		assertEquals("vanilla's blockLSBArray", 4096, heapSlot().lsbArray().length);
		assertEquals("vanilla's blockMSBArray nibbles", 2048, heapSlot().msbArray().length);
		assertEquals("vanilla's blockMetadataArray nibbles", 2048, heapSlot().metaArray().length);
		assertEquals("vanilla's blocklightArray nibbles", 2048, heapSlot().blocklightArray().length);
		assertEquals("vanilla's skylightArray nibbles", 2048, heapSlot().skylightArray().length);
	}

	/** A write through the array is a write to the section. */
	@Test
	public void writesThroughTheExposedArraysAreVisibleToTheSlot()
	{
		int index = 5 << 8 | 6 << 4 | 7; // y=5, z=6, x=7

		heapSlot().lsbArray()[index] = (byte)0x2A;
		setNibble(heapSlot().msbArray(), index, 3);
		setNibble(heapSlot().metaArray(), index, 9);
		setNibble(heapSlot().blocklightArray(), index, 12);
		setNibble(heapSlot().skylightArray(), index, 4);

		assertEquals("block id must see the array write", 0x2A | (3 << 8), slot().getBlockId(7, 5, 6));
		assertEquals(9, slot().getMeta(7, 5, 6));
		assertEquals(12, slot().getBlocklight(7, 5, 6));
		assertEquals(4, slot().getSkylight(7, 5, 6));
	}

	/** And the other way round: the slot's writes land in the same arrays. */
	@Test
	public void slotWritesAreVisibleThroughTheExposedArrays()
	{
		slot().setBlockIdAndMeta(1, 2, 3, 0x1FF, 6);
		slot().setBlocklight(1, 2, 3, 8);
		slot().setSkylight(1, 2, 3, 2);

		int index = 2 << 8 | 3 << 4 | 1;
		assertEquals((byte)0xFF, heapSlot().lsbArray()[index]);
		assertEquals(1, nibble(heapSlot().msbArray(), index));
		assertEquals(6, nibble(heapSlot().metaArray(), index));
		assertEquals(8, nibble(heapSlot().blocklightArray(), index));
		assertEquals(2, nibble(heapSlot().skylightArray(), index));
	}

	/** The same array object every time - a mod caches it and keeps writing. */
	@Test
	public void theExposedArraysAreStableAcrossCalls()
	{
		assertSame(heapSlot().lsbArray(), heapSlot().lsbArray());
		assertSame(heapSlot().msbArray(), heapSlot().msbArray());
		assertSame(heapSlot().metaArray(), heapSlot().metaArray());
		assertSame(heapSlot().blocklightArray(), heapSlot().blocklightArray());
		assertSame(heapSlot().skylightArray(), heapSlot().skylightArray());
	}

	/**
	 * Vanilla's setters store the array the caller passed - {@code
	 * setBlockLSBArray(byte[])} makes that array the section. Mods rely on it:
	 * they hand over an array they intend to keep writing to.
	 */
	@Test
	public void adoptedArraysBecomeTheSectionRatherThanBeingCopiedIn()
	{
		byte[] lsb = new byte[4096];
		byte[] msb = new byte[2048];
		byte[] meta = new byte[2048];
		byte[] blockLight = new byte[2048];
		byte[] skyLight = new byte[2048];

		heapSlot().adoptLSB(lsb);
		heapSlot().adoptMSB(msb);
		heapSlot().adoptBlockMetadata(meta);
		heapSlot().adoptBlocklight(blockLight);
		heapSlot().adoptSkylight(skyLight);

		assertSame(lsb, heapSlot().lsbArray());
		assertSame(msb, heapSlot().msbArray());
		assertSame(meta, heapSlot().metaArray());
		assertSame(blockLight, heapSlot().blocklightArray());
		assertSame(skyLight, heapSlot().skylightArray());

		int index = 15 << 8 | 15 << 4 | 15;
		lsb[index] = (byte)0x11;
		setNibble(meta, index, 5);
		assertEquals("a later write to the adopted array is a write to the section", 0x11, slot().getBlockId(15, 15, 15));
		assertEquals(5, slot().getMeta(15, 15, 15));
	}

	/** {@code setLSB} still copies - it is how a chunk is read off disk. */
	@Test
	public void bulkSettersStillCopyRatherThanAdopt()
	{
		byte[] lsb = pattern(4096, 3);
		slot().setLSB(lsb);

		assertNotSame("setLSB must not adopt the caller's array", lsb, heapSlot().lsbArray());
		assertArrayEquals(lsb, heapSlot().lsbArray());

		lsb[0] = (byte)~lsb[0];
		assertEquals("the section must not follow the caller's array afterwards",
				pattern(4096, 3)[0] & 255, heapSlot().lsbArray()[0] & 255);
	}

	/** Copying a section must copy its arrays, not share them. */
	@Test
	public void copiedSectionsDoNotShareArrays()
	{
		MemSlot copy = alloc().allocateSlot();
		try
		{
			copy.copyFrom(slot());
			assertNotSame(heapSlot().lsbArray(), ((HeapMemSlot)copy).lsbArray());
			assertNotSame(heapSlot().msbArray(), ((HeapMemSlot)copy).msbArray());
			assertNotSame(heapSlot().metaArray(), ((HeapMemSlot)copy).metaArray());
			assertNotSame(heapSlot().blocklightArray(), ((HeapMemSlot)copy).blocklightArray());
			assertNotSame(heapSlot().skylightArray(), ((HeapMemSlot)copy).skylightArray());
		}
		finally
		{
			copy.release();
		}
	}

	private static void setNibble(byte[] arr, int index, int value)
	{
		int i = index >> 1;
		if((index & 1) == 0)
			arr[i] = (byte)(arr[i] & 240 | value & 15);
		else
			arr[i] = (byte)(arr[i] & 15 | (value & 15) << 4);
	}
}
