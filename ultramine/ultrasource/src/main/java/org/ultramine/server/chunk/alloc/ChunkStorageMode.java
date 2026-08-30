package org.ultramine.server.chunk.alloc;

import javax.annotation.Nonnull;

/**
 * Where chunk sections live, decided once per server start.
 *
 * <p>UltraMine's reason to exist is {@link #OFF_HEAP}: block ids, metadata and
 * light for a 16x16x16 section packed into one 12 KiB off-heap slot that costs
 * the garbage collector nothing. Nothing about that is visible to mods - until
 * a coremod tries to patch chunk storage, because every such coremod is written
 * against vanilla's heap arrays and there are none to patch. That is what
 * {@link #VANILLA} is for: the same layout, the same 12 KiB, in the five arrays
 * vanilla uses, so ChunkAPI and its clients (EndlessIDs, NEID) and Phosphor's
 * lighting can shadow, extend and replace them exactly as they do on stock
 * Forge - which is what large modern packs need in order to run at all.
 *
 * <p>This is deliberately a startup decision and not a per-world or per-chunk
 * one: it changes what {@code ExtendedBlockStorage} exposes to coremods, and
 * coremods are applied while classes load, long before any world is opened.
 *
 * <p>Set it with {@code -Dorg.ultramine.chunk.storage=offheap} (the default) or
 * {@code -Dorg.ultramine.chunk.storage=vanilla}. A value that is not one of the
 * accepted spellings fails the launch rather than falling back, because falling
 * back silently would mean running a pack in the mode it cannot run in.
 */
public enum ChunkStorageMode
{
	/** Off-heap slots. The default: least memory, least GC, no patchable arrays. */
	OFF_HEAP,
	/** Vanilla's heap arrays. Slower and heavier, but coremods can patch it. */
	VANILLA;

	public static final String PROPERTY = "org.ultramine.chunk.storage";

	private static final ChunkStorageMode CURRENT = parse(System.getProperty(PROPERTY, "offheap"));

	/** The mode this JVM runs in. Fixed for the life of the process. */
	@Nonnull
	public static ChunkStorageMode current()
	{
		return CURRENT;
	}

	/**
	 * True when chunk sections are stored in vanilla's arrays, so that {@code
	 * ExtendedBlockStorage} publishes them as fields coremods can patch.
	 */
	public static boolean isVanillaShaped()
	{
		return CURRENT == VANILLA;
	}

	@Nonnull
	public static ChunkStorageMode parse(String value)
	{
		String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT).replace("-", "").replace("_", "");
		if(normalized.equals("offheap") || normalized.equals("unsafe") || normalized.equals("default"))
			return OFF_HEAP;
		if(normalized.equals("vanilla") || normalized.equals("heap") || normalized.equals("compat") || normalized.equals("compatibility"))
			return VANILLA;
		throw new IllegalArgumentException("Unknown chunk storage mode '" + value + "'. Set -D" + PROPERTY
				+ " to 'offheap' (default, off-heap slots) or 'vanilla' (heap arrays, for coremods that patch chunk storage).");
	}

	/** The allocator that backs this mode. */
	@Nonnull
	public ChunkAllocService createAlloc()
	{
		return this == VANILLA
				? new org.ultramine.server.chunk.alloc.heap.HeapChunkAlloc()
				: new org.ultramine.server.chunk.alloc.unsafe.UnsafeChunkAlloc();
	}
}
