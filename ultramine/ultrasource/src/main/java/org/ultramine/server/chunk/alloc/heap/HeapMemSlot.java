package org.ultramine.server.chunk.alloc.heap;

import javax.annotation.Nonnull;

import org.ultramine.server.chunk.alloc.ChunkAllocService;
import org.ultramine.server.chunk.alloc.MemSlot;

/**
 * A chunk section stored the way vanilla stores it: five heap arrays, one byte
 * per block id LSB and a packed nibble each for the id's high bits, the
 * metadata and the two light values.
 *
 * <p>This is the compatibility backend. Its reason to exist is not memory - the
 * off-heap slot is strictly better at that, and holds the identical layout in
 * the identical 12 KiB - but the fact that the arrays are ordinary Java arrays
 * a coremod can reach: ChunkAPI and its clients (EndlessIDs, NEID) and
 * Phosphor's lighting all shadow, extend or replace vanilla's chunk arrays, and
 * they cannot do that to memory addressed through {@code sun.misc.Unsafe}.
 *
 * <p>The arrays handed out by {@link #lsbArray()} and friends are the section
 * itself, not copies: whoever holds one is writing blocks. That is the whole
 * point, and it is what {@code ExtendedBlockStorage} publishes as vanilla's
 * {@code blockLSBArray}/{@code blockMSBArray}/... fields in this mode.
 *
 * <p>Not thread-safe, exactly like the off-heap slot: a section is owned by its
 * chunk and accessed under the chunk's own locking.
 */
public final class HeapMemSlot implements MemSlot
{
	static final int LSB_SIZE = 4096;
	static final int NIBBLE_SIZE = 2048;

	private final HeapChunkAlloc alloc;

	private byte[] lsb = new byte[LSB_SIZE];
	private byte[] msb = new byte[NIBBLE_SIZE];
	private byte[] meta = new byte[NIBBLE_SIZE];
	private byte[] blocklight = new byte[NIBBLE_SIZE];
	private byte[] skylight = new byte[NIBBLE_SIZE];

	private boolean isReleased;

	HeapMemSlot(HeapChunkAlloc alloc)
	{
		this.alloc = alloc;
	}

	//
	// The live arrays. These are the section - handing one out hands out write
	// access to the blocks, which is what makes vanilla-shaped coremods work.
	//

	public byte[] lsbArray()
	{
		return lsb;
	}

	public byte[] msbArray()
	{
		return msb;
	}

	public byte[] metaArray()
	{
		return meta;
	}

	public byte[] blocklightArray()
	{
		return blocklight;
	}

	public byte[] skylightArray()
	{
		return skylight;
	}

	/**
	 * Take the caller's array as the section's storage, without copying. This is
	 * vanilla's {@code setBlockLSBArray} semantics: the caller keeps a reference
	 * and keeps writing through it. Contrast {@link #setLSB(byte[])}, which
	 * copies because it is loading a chunk off disk.
	 */
	public void adoptLSB(@Nonnull byte[] arr)
	{
		checkAdoptable(arr, LSB_SIZE);
		lsb = arr;
	}

	public void adoptMSB(@Nonnull byte[] arr)
	{
		checkAdoptable(arr, NIBBLE_SIZE);
		msb = arr;
	}

	public void adoptBlockMetadata(@Nonnull byte[] arr)
	{
		checkAdoptable(arr, NIBBLE_SIZE);
		meta = arr;
	}

	public void adoptBlocklight(@Nonnull byte[] arr)
	{
		checkAdoptable(arr, NIBBLE_SIZE);
		blocklight = arr;
	}

	public void adoptSkylight(@Nonnull byte[] arr)
	{
		checkAdoptable(arr, NIBBLE_SIZE);
		skylight = arr;
	}

	private static void checkLength(byte[] arr, int length)
	{
		if(arr == null || arr.length != length)
			throw new IllegalArgumentException("expected a " + length + "-byte array");
	}

	/**
	 * A mod may hand over an array bigger than vanilla's - that is one of the
	 * ways the id ceiling gets lifted - and only the leading section-sized window
	 * is ours to interpret. A shorter one is a bug, and failing here beats
	 * writing blocks past the end of it.
	 */
	private static void checkAdoptable(byte[] arr, int length)
	{
		if(arr == null || arr.length < length)
			throw new IllegalArgumentException("expected an array of at least " + length + " bytes");
	}

	//raw set

	@Override
	public void setLSB(byte[] arr)
	{
		checkLength(arr, LSB_SIZE);
		System.arraycopy(arr, 0, lsb, 0, LSB_SIZE);
	}

	@Override
	public void setLSB(byte[] arr, int start)
	{
		checkReadable(arr, start, LSB_SIZE);
		System.arraycopy(arr, start, lsb, 0, LSB_SIZE);
	}

	@Override
	public void setMSB(byte[] arr)
	{
		checkLength(arr, NIBBLE_SIZE);
		System.arraycopy(arr, 0, msb, 0, NIBBLE_SIZE);
	}

	@Override
	public void setMSB(byte[] arr, int start)
	{
		checkReadable(arr, start, NIBBLE_SIZE);
		System.arraycopy(arr, start, msb, 0, NIBBLE_SIZE);
	}

	@Override
	public void setBlockMetadata(byte[] arr)
	{
		checkLength(arr, NIBBLE_SIZE);
		System.arraycopy(arr, 0, meta, 0, NIBBLE_SIZE);
	}

	@Override
	public void setBlockMetadata(byte[] arr, int start)
	{
		checkReadable(arr, start, NIBBLE_SIZE);
		System.arraycopy(arr, start, meta, 0, NIBBLE_SIZE);
	}

	@Override
	public void setBlocklight(byte[] arr)
	{
		checkLength(arr, NIBBLE_SIZE);
		System.arraycopy(arr, 0, blocklight, 0, NIBBLE_SIZE);
	}

	@Override
	public void setBlocklight(byte[] arr, int start)
	{
		checkReadable(arr, start, NIBBLE_SIZE);
		System.arraycopy(arr, start, blocklight, 0, NIBBLE_SIZE);
	}

	@Override
	public void setSkylight(byte[] arr)
	{
		checkLength(arr, NIBBLE_SIZE);
		System.arraycopy(arr, 0, skylight, 0, NIBBLE_SIZE);
	}

	@Override
	public void setSkylight(byte[] arr, int start)
	{
		checkReadable(arr, start, NIBBLE_SIZE);
		System.arraycopy(arr, start, skylight, 0, NIBBLE_SIZE);
	}

	//raw copy

	@Override
	public void copyLSB(byte[] arr)
	{
		checkLength(arr, LSB_SIZE);
		System.arraycopy(lsb, 0, arr, 0, LSB_SIZE);
	}

	@Override
	public void copyLSB(byte[] arr, int start)
	{
		checkWritable(arr, start, LSB_SIZE);
		System.arraycopy(lsb, 0, arr, start, LSB_SIZE);
	}

	@Override
	public void copyMSB(byte[] arr)
	{
		checkLength(arr, NIBBLE_SIZE);
		System.arraycopy(msb, 0, arr, 0, NIBBLE_SIZE);
	}

	@Override
	public void copyMSB(byte[] arr, int start)
	{
		checkWritable(arr, start, NIBBLE_SIZE);
		System.arraycopy(msb, 0, arr, start, NIBBLE_SIZE);
	}

	@Override
	public void copyBlockMetadata(byte[] arr)
	{
		checkLength(arr, NIBBLE_SIZE);
		System.arraycopy(meta, 0, arr, 0, NIBBLE_SIZE);
	}

	@Override
	public void copyBlockMetadata(byte[] arr, int start)
	{
		checkWritable(arr, start, NIBBLE_SIZE);
		System.arraycopy(meta, 0, arr, start, NIBBLE_SIZE);
	}

	@Override
	public void copyBlocklight(byte[] arr)
	{
		checkLength(arr, NIBBLE_SIZE);
		System.arraycopy(blocklight, 0, arr, 0, NIBBLE_SIZE);
	}

	@Override
	public void copyBlocklight(byte[] arr, int start)
	{
		checkWritable(arr, start, NIBBLE_SIZE);
		System.arraycopy(blocklight, 0, arr, start, NIBBLE_SIZE);
	}

	@Override
	public void copySkylight(byte[] arr)
	{
		checkLength(arr, NIBBLE_SIZE);
		System.arraycopy(skylight, 0, arr, 0, NIBBLE_SIZE);
	}

	@Override
	public void copySkylight(byte[] arr, int start)
	{
		checkWritable(arr, start, NIBBLE_SIZE);
		System.arraycopy(skylight, 0, arr, start, NIBBLE_SIZE);
	}

	private static void checkReadable(byte[] arr, int start, int length)
	{
		if(arr == null || start < 0 || arr.length - start < length)
			throw new IllegalArgumentException("cannot read " + length + " bytes at " + start);
	}

	private static void checkWritable(byte[] arr, int start, int length)
	{
		if(arr == null || start < 0 || arr.length - start < length)
			throw new IllegalArgumentException("cannot write " + length + " bytes at " + start);
	}

	//clear

	@Override
	public void zerofillMSB()
	{
		java.util.Arrays.fill(msb, (byte)0);
	}

	@Override
	public void zerofillSkylight()
	{
		java.util.Arrays.fill(skylight, (byte)0);
	}

	@Override
	public void zerofillAll()
	{
		java.util.Arrays.fill(lsb, (byte)0);
		java.util.Arrays.fill(msb, (byte)0);
		java.util.Arrays.fill(meta, (byte)0);
		java.util.Arrays.fill(blocklight, (byte)0);
		java.util.Arrays.fill(skylight, (byte)0);
	}

	//per-coordinate access; identical packing to the off-heap slot

	private static int get4bits(byte[] arr, int x, int y, int z)
	{
		int ind = y << 8 | z << 4 | x;
		byte data = arr[ind >> 1];
		return (ind & 1) == 0 ? data & 15 : data >> 4 & 15;
	}

	private static void set4bits(byte[] arr, int x, int y, int z, int data)
	{
		int ind = y << 8 | z << 4 | x;
		int off = ind >> 1;
		if((ind & 1) == 0)
			arr[off] = (byte)(arr[off] & 240 | data & 15);
		else
			arr[off] = (byte)(arr[off] & 15 | (data & 15) << 4);
	}

	@Override
	public int getBlockId(int x, int y, int z)
	{
		return (lsb[y << 8 | z << 4 | x] & 255) | (get4bits(msb, x, y, z) << 8);
	}

	@Override
	public void setBlockId(int x, int y, int z, int id)
	{
		lsb[y << 8 | z << 4 | x] = (byte)(id & 0xFF);
		set4bits(msb, x, y, z, (id & 3840) >> 8);
	}

	@Override
	public int getMeta(int x, int y, int z)
	{
		return get4bits(meta, x, y, z);
	}

	@Override
	public void setMeta(int x, int y, int z, int m)
	{
		set4bits(meta, x, y, z, m);
	}

	@Override
	public int getBlocklight(int x, int y, int z)
	{
		return get4bits(blocklight, x, y, z);
	}

	@Override
	public void setBlocklight(int x, int y, int z, int val)
	{
		set4bits(blocklight, x, y, z, val);
	}

	@Override
	public int getSkylight(int x, int y, int z)
	{
		return get4bits(skylight, x, y, z);
	}

	@Override
	public void setSkylight(int x, int y, int z, int val)
	{
		set4bits(skylight, x, y, z, val);
	}

	//

	@Nonnull
	@Override
	public ChunkAllocService getAlloc()
	{
		return alloc;
	}

	@Override
	public void copyFrom(@Nonnull MemSlot src)
	{
		if(getClass() != src.getClass())
			throw new IllegalStateException("cannot copy between chunk storage backends");
		if(isReleased)
			throw new IllegalStateException("Destination slot already released");
		HeapMemSlot other = (HeapMemSlot)src;
		if(other.isReleased)
			throw new IllegalStateException("Source slot already released");

		//the arrays may have been replaced by a mod, so copy into ours by value
		System.arraycopy(other.lsb, 0, lsb, 0, LSB_SIZE);
		System.arraycopy(other.msb, 0, msb, 0, NIBBLE_SIZE);
		System.arraycopy(other.meta, 0, meta, 0, NIBBLE_SIZE);
		System.arraycopy(other.blocklight, 0, blocklight, 0, NIBBLE_SIZE);
		System.arraycopy(other.skylight, 0, skylight, 0, NIBBLE_SIZE);
	}

	/**
	 * There is nothing to hand back to the OS here - the arrays are collected
	 * once the section is unreachable. Releasing is still tracked so that a
	 * double release fails the same way it does off-heap, where it would
	 * otherwise free memory another thread is still reading.
	 */
	@Override
	public void release()
	{
		if(isReleased)
			throw new IllegalStateException("Slot already released");
		isReleased = true;
		alloc.releaseSlot();
	}
}
