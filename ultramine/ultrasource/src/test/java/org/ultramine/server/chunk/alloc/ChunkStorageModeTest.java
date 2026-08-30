package org.ultramine.server.chunk.alloc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.ultramine.server.chunk.alloc.heap.HeapChunkAlloc;
import org.ultramine.server.chunk.alloc.unsafe.UnsafeChunkAlloc;

/**
 * Chunk storage is picked once, from one string, before any world is opened. A
 * mode that silently resolved to the wrong thing would mean a pack running in
 * the shape it cannot run in - or, worse, an existing world being read by a
 * backend nobody chose.
 */
public class ChunkStorageModeTest
{
	@Test
	public void acceptsTheSpellingsAnAdminIsLikelyToWrite()
	{
		for(String s : new String[]{"offheap", "off-heap", "OFF_HEAP", " OffHeap ", "unsafe", "default"})
			assertEquals(s, ChunkStorageMode.OFF_HEAP, ChunkStorageMode.parse(s));

		for(String s : new String[]{"vanilla", "VANILLA", " heap ", "compat", "compatibility"})
			assertEquals(s, ChunkStorageMode.VANILLA, ChunkStorageMode.parse(s));
	}

	/**
	 * A typo must stop the launch, not pick a default. Falling back would run the
	 * server in the mode the admin was trying to leave, and they would find out
	 * from a crash hours later.
	 */
	@Test
	public void refusesAnythingElseRatherThanFallingBack()
	{
		for(String s : new String[]{"", "off heap", "vanila", "true", "yes", null})
		{
			try
			{
				ChunkStorageMode.parse(s);
				fail("must not accept '" + s + "'");
			}
			catch(IllegalArgumentException expected)
			{
				assertTrue("the message must say what to write instead",
						expected.getMessage().contains(ChunkStorageMode.PROPERTY));
			}
		}
	}

	@Test
	public void eachModeBuildsItsOwnBackend()
	{
		assertTrue(ChunkStorageMode.OFF_HEAP.createAlloc() instanceof UnsafeChunkAlloc);
		assertTrue(ChunkStorageMode.VANILLA.createAlloc() instanceof HeapChunkAlloc);
	}

	/** Off-heap unless someone says otherwise: existing servers must not move. */
	@Test
	public void defaultsToOffHeap()
	{
		assertEquals(ChunkStorageMode.OFF_HEAP, ChunkStorageMode.parse(System.getProperty(ChunkStorageMode.PROPERTY, "offheap")));
	}
}
