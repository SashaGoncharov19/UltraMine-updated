package net.minecraft.world.chunk.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.ultramine.server.chunk.alloc.ChunkAllocService;
import org.ultramine.server.chunk.alloc.ChunkStorageMode;
import org.ultramine.server.chunk.alloc.unsafe.UnsafeChunkAlloc;

/**
 * The default mode, which must keep behaving exactly as it did before there was
 * a second one: sections live off-heap, so there are no arrays to hand out and
 * the accessors that promise one can only return a copy. That is a sharp edge
 * worth pinning down rather than leaving to be rediscovered - a mod writing
 * through such a copy loses the write - and it is the reason compatibility mode
 * exists.
 */
public class ExtendedBlockStorageOffHeapModeTest
{
	private ExtendedBlockStorage storage;

	@BeforeClass
	public static void useOffHeapStorage() throws Exception
	{
		assertTrue("the default mode must be off-heap", ChunkStorageMode.current() == ChunkStorageMode.OFF_HEAP);
		injectAlloc(new UnsafeChunkAlloc());
	}

	private static void injectAlloc(ChunkAllocService service) throws Exception
	{
		Field f = ExtendedBlockStorage.class.getDeclaredField("alloc");
		f.setAccessible(true);
		f.set(null, service);
	}

	@Before
	public void setUp()
	{
		storage = new ExtendedBlockStorage(0, true);
	}

	@Test
	public void storesBlocksMetadataAndLightOffHeap()
	{
		storage.getSlot().setBlockIdAndMeta(1, 2, 3, 2047, 5);
		storage.setExtSkylightValue(1, 2, 3, 8);
		storage.setExtBlocklightValue(1, 2, 3, 3);

		assertEquals(2047, storage.getSlot().getBlockId(1, 2, 3));
		assertEquals(5, storage.getExtBlockMetadata(1, 2, 3));
		assertEquals(8, storage.getExtSkylightValue(1, 2, 3));
		assertEquals(3, storage.getExtBlocklightValue(1, 2, 3));
	}

	/** There is no array to hand out, so what comes back is a detached copy. */
	@Test
	public void arrayAccessorsHandOutCopiesThatDoNotWriteBack()
	{
		storage.setExtBlockMetadata(4, 5, 6, 7);

		assertNotSame("each call materializes a new copy", storage.getBlockLSBArray(), storage.getBlockLSBArray());
		assertEquals("the copy carries the current data", 7, storage.getMetadataArray().get(4, 5, 6));

		storage.getMetadataArray().set(4, 5, 6, 2);
		assertEquals("writing the copy does not reach the section", 7, storage.getExtBlockMetadata(4, 5, 6));
	}

	/** The copying setters, by contrast, do reach it. */
	@Test
	public void arraySettersCopyIntoTheSection()
	{
		byte[] lsb = new byte[4096];
		lsb[6 << 8 | 7 << 4 | 8] = (byte)0x5C;
		storage.setBlockLSBArray(lsb);

		assertEquals(0x5C, storage.getSlot().getBlockId(8, 6, 7));

		lsb[6 << 8 | 7 << 4 | 8] = (byte)0x5D;
		assertEquals("the section does not follow the caller's array afterwards", 0x5C, storage.getSlot().getBlockId(8, 6, 7));
	}

	@Test
	public void copyingASectionIsIndependent()
	{
		storage.setExtBlockMetadata(2, 2, 2, 9);

		ExtendedBlockStorage copy = storage.copy();
		try
		{
			assertEquals(9, copy.getExtBlockMetadata(2, 2, 2));
			copy.setExtBlockMetadata(2, 2, 2, 1);
			assertEquals(9, storage.getExtBlockMetadata(2, 2, 2));
		}
		finally
		{
			copy.release();
		}
	}
}
