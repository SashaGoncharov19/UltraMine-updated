package org.ultramine.server.chunk.alloc.unsafe;

import org.ultramine.server.chunk.alloc.ChunkAllocService;
import org.ultramine.server.chunk.alloc.MemSlotContractTest;

/**
 * The default backend: one 12 KiB off-heap slot per chunk section, addressed
 * through {@code sun.misc.Unsafe}. This is what UltraMine exists for - chunk
 * data that costs the garbage collector nothing - so the contract has to hold
 * here first.
 */
public class UnsafeMemSlotTest extends MemSlotContractTest
{
	@Override
	protected ChunkAllocService createAlloc()
	{
		return new UnsafeChunkAlloc();
	}
}
