package org.ultramine.server.chunk.alloc.heap;

import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

import org.ultramine.server.chunk.alloc.ChunkAllocService;
import org.ultramine.server.chunk.alloc.MemSlot;

/**
 * Hands out heap-backed chunk sections. This is the compatibility chunk storage
 * backend, selected at startup when coremods that patch vanilla's chunk arrays
 * have to work - see {@link HeapMemSlot} for why they cannot work off-heap.
 *
 * <p>There is no pool and no free list: sections are ordinary objects, and the
 * garbage collector reclaims them once a chunk drops them. That is the cost of
 * this mode, and the reason it is not the default.
 */
@ThreadSafe
public class HeapChunkAlloc implements ChunkAllocService
{
	/** what one section costs: 4096 ids + four packed nibble arrays */
	static final long SLOT_SIZE = HeapMemSlot.LSB_SIZE + 4L * HeapMemSlot.NIBBLE_SIZE;

	private final AtomicLong liveSlots = new AtomicLong();

	@Nonnull
	@Override
	public MemSlot allocateSlot()
	{
		liveSlots.incrementAndGet();
		return new HeapMemSlot(this);
	}

	void releaseSlot()
	{
		liveSlots.decrementAndGet();
	}

	/** Nothing is off-heap in this mode; the sections show up in the heap figures. */
	@Override
	public long getOffHeapTotalMemory()
	{
		return 0;
	}

	@Override
	public long getOffHeapUsedMemory()
	{
		return 0;
	}

	/** How much heap the live chunk sections hold, for diagnostics. */
	public long getHeapChunkMemory()
	{
		return liveSlots.get() * SLOT_SIZE;
	}
}
