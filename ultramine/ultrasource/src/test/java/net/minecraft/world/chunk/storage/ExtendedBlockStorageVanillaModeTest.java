package net.minecraft.world.chunk.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import net.minecraft.world.chunk.NibbleArray;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.ultramine.server.chunk.alloc.ChunkAllocService;
import org.ultramine.server.chunk.alloc.ChunkStorageMode;
import org.ultramine.server.chunk.alloc.heap.HeapChunkAlloc;

/**
 * Compatibility mode, where a chunk section is vanilla's five arrays. Its whole
 * purpose is that a coremod can take one of those arrays and write blocks
 * through it - so what is tested here is that the arrays and the section are
 * genuinely the same data, in both directions, including after a mod swaps one
 * of the arrays out. Anything less and a pack would appear to work while the
 * world quietly disagreed with itself.
 *
 * <p>The mode is a JVM-wide startup decision, so this class sets it before the
 * classes that read it are loaded, and the test task forks per class.
 */
public class ExtendedBlockStorageVanillaModeTest
{
	static
	{
		System.setProperty(ChunkStorageMode.PROPERTY, "vanilla");
	}

	private ExtendedBlockStorage storage;

	@BeforeClass
	public static void useHeapStorage() throws Exception
	{
		assertTrue("this test needs a JVM that has not already resolved the chunk storage mode",
				ChunkStorageMode.isVanillaShaped());
		injectAlloc(new HeapChunkAlloc());
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
	public void publishesVanillasArrays()
	{
		assertNotNull("blockLSBArray", storage.getBlockLSBArray());
		assertNotNull("blockMSBArray", storage.getBlockMSBArray());
		assertNotNull("blockMetadataArray", storage.getMetadataArray());
		assertNotNull("blocklightArray", storage.getBlocklightArray());
		assertNotNull("skylightArray", storage.getSkylightArray());

		assertEquals(4096, storage.getBlockLSBArray().length);
		assertEquals(2048, storage.getMetadataArray().data.length);
	}

	/** The array a mod is handed is the section, not a snapshot of it. */
	@Test
	public void theArraysAreTheSectionRatherThanACopy()
	{
		assertSame("handed out twice, the same array", storage.getBlockLSBArray(), storage.getBlockLSBArray());

		storage.getBlockLSBArray()[2 << 8 | 3 << 4 | 4] = (byte)0x7B;
		storage.getMetadataArray().set(4, 2, 3, 11);
		storage.getSkylightArray().set(4, 2, 3, 6);
		storage.getBlocklightArray().set(4, 2, 3, 13);

		assertEquals("a write through the array is a write to the section", 0x7B, storage.getSlot().getBlockId(4, 2, 3));
		assertEquals(11, storage.getExtBlockMetadata(4, 2, 3));
		assertEquals(6, storage.getExtSkylightValue(4, 2, 3));
		assertEquals(13, storage.getExtBlocklightValue(4, 2, 3));
	}

	@Test
	public void theSectionsWritesAreVisibleThroughTheArrays()
	{
		storage.setExtBlockMetadata(1, 2, 3, 9);
		storage.setExtSkylightValue(1, 2, 3, 5);
		storage.setExtBlocklightValue(1, 2, 3, 2);

		assertEquals(9, storage.getMetadataArray().get(1, 2, 3));
		assertEquals(5, storage.getSkylightArray().get(1, 2, 3));
		assertEquals(2, storage.getBlocklightArray().get(1, 2, 3));
		assertEquals("and through the raw slot the bulk paths use", 9, storage.getSlot().getMeta(1, 2, 3));
	}

	/**
	 * Mods replace whole arrays - that is how the block-id ceiling gets lifted.
	 * After a swap the section must be the new array, on every path, including
	 * the raw slot that chunk saving and the chunk packet read.
	 */
	@Test
	public void replacingAnArrayReplacesTheSection()
	{
		byte[] lsb = new byte[4096];
		lsb[5 << 8 | 6 << 4 | 7] = (byte)0x21;
		storage.setBlockLSBArray(lsb);

		assertSame(lsb, storage.getBlockLSBArray());
		assertEquals(0x21, storage.getSlot().getBlockId(7, 5, 6));

		NibbleArray meta = new NibbleArray(4096, 4);
		meta.set(7, 5, 6, 4);
		storage.setBlockMetadataArray(meta);
		assertSame(meta, storage.getMetadataArray());
		assertEquals(4, storage.getExtBlockMetadata(7, 5, 6));
		assertEquals(4, storage.getSlot().getMeta(7, 5, 6));

		NibbleArray sky = new NibbleArray(4096, 4);
		sky.set(7, 5, 6, 14);
		storage.setSkylightArray(sky);
		assertEquals(14, storage.getExtSkylightValue(7, 5, 6));
		assertEquals(14, storage.getSlot().getSkylight(7, 5, 6));

		//and a later write through the mod's own reference still lands
		lsb[5 << 8 | 6 << 4 | 7] = (byte)0x22;
		assertEquals(0x22, storage.getSlot().getBlockId(7, 5, 6));
	}

	/** A bigger array than vanilla's is how mods widen storage; it must be taken. */
	@Test
	public void acceptsAnOversizedArrayFromAMod()
	{
		byte[] wide = new byte[8192];
		wide[1 << 8 | 1 << 4 | 1] = (byte)0x33;
		storage.setBlockLSBArray(wide);

		assertSame(wide, storage.getBlockLSBArray());
		assertEquals(0x33, storage.getSlot().getBlockId(1, 1, 1));
	}

	/**
	 * Vanilla reads a null MSB array as "no block above id 255 here". The raw
	 * slot still owns those bits, so it has to be cleared with the field, or the
	 * next chunk save would write high bits nothing points at any more.
	 */
	@Test
	public void droppingTheHighBitArrayClearsThemInTheRawSlotToo()
	{
		storage.getBlockMSBArray().set(2, 2, 2, 7);
		assertEquals(7 << 8, storage.getSlot().getBlockId(2, 2, 2));

		storage.setBlockMSBArray(new NibbleArray(4096, 4));
		assertEquals("a fresh array means no high bits", 0, storage.getSlot().getBlockId(2, 2, 2));
	}

	@Test
	public void copyingASectionCopiesItsArraysRatherThanSharingThem()
	{
		storage.setExtBlockMetadata(3, 3, 3, 12);
		storage.getBlockLSBArray()[3 << 8 | 3 << 4 | 3] = (byte)0x44;

		ExtendedBlockStorage copy = storage.copy();
		try
		{
			assertEquals(12, copy.getExtBlockMetadata(3, 3, 3));
			assertEquals(0x44, copy.getSlot().getBlockId(3, 3, 3));
			assertNotSame(storage.getBlockLSBArray(), copy.getBlockLSBArray());

			copy.setExtBlockMetadata(3, 3, 3, 1);
			assertEquals("writing the copy must not touch the original", 12, storage.getExtBlockMetadata(3, 3, 3));
		}
		finally
		{
			copy.release();
		}
	}

	@Test
	public void reportsTheYPositionItWasBuiltFor()
	{
		assertEquals(0, storage.getYLocation());
		assertEquals(48, new ExtendedBlockStorage(48, true).getYLocation());
	}
}
