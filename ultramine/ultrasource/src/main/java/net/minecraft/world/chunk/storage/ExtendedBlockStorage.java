package net.minecraft.world.chunk.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ultramine.core.service.InjectService;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.NibbleArray;
import org.ultramine.server.chunk.alloc.ChunkAllocService;
import org.ultramine.server.chunk.alloc.ChunkStorageMode;
import org.ultramine.server.chunk.alloc.MemSlot;
import org.ultramine.server.chunk.alloc.heap.HeapMemSlot;

/**
 * One 16x16x16 section of a chunk.
 *
 * <p>Storage sits behind {@link MemSlot} and comes in two shapes, chosen once at
 * startup by {@link ChunkStorageMode}:
 *
 * <ul>
 * <li>{@link ChunkStorageMode#OFF_HEAP} (default) - the section is a 12 KiB
 * off-heap slot. Vanilla's array fields are null and the accessors that return
 * them materialize copies, so writing through one has no effect on the world.
 * <li>{@link ChunkStorageMode#VANILLA} - the section is vanilla's five heap
 * arrays. The fields below are live and are the section itself, the accessors
 * hand them out rather than copying, and the block/metadata/light methods read
 * and write them with vanilla's own instructions - which is what lets coremods
 * that patch chunk storage (ChunkAPI and its clients EndlessIDs and NEID,
 * Phosphor's lighting) apply here as they do on stock Forge.
 * </ul>
 *
 * <p>Both shapes hold the identical packing - a byte of block id plus a nibble
 * of high bits, a nibble each of metadata, block light and sky light - so a
 * world written in one mode is the same world in the other.
 */
public class ExtendedBlockStorage
{
	/** fixed for the life of the JVM, so this folds away at JIT time */
	private static final boolean VANILLA_SHAPED = ChunkStorageMode.isVanillaShaped();

	@InjectService private static ChunkAllocService alloc;
	private int yBase;
	private int blockRefCount;
	private int tickRefCount;
	private volatile MemSlot slot; // volatile read is cheap on x86

	/*
	 * Vanilla's storage. Populated only in ChunkStorageMode.VANILLA, where they
	 * are the same arrays the slot is built from - not copies of it - and where
	 * a coremod may replace any of them wholesale. Their names and descriptors
	 * are vanilla's because that is what @Shadow looks for.
	 */
	private byte[] blockLSBArray;
	private NibbleArray blockMSBArray;
	private NibbleArray blockMetadataArray;
	private NibbleArray blocklightArray;
	private NibbleArray skylightArray;

	/*
	 * Vanilla treats a null MSB or sky-light array as "all zero", and a coremod
	 * may assign null straight through the field. The slot still owns 2 KiB for
	 * each, so it is zeroed once on that transition rather than on every read.
	 */
	private boolean msbDetached;
	private boolean skylightDetached;

	private static final String __OBFID = "CL_00000375";

	public ExtendedBlockStorage(int p_i1997_1_, boolean p_i1997_2_, boolean zerofill)
	{
		this.yBase = p_i1997_1_;
		this.slot = alloc.allocateSlot();
		if(zerofill)
			slot.zerofillAll();
		if(VANILLA_SHAPED)
		{
			/*
			 * Allocated here, vanilla-shaped, and then handed to the slot: the
			 * arrays and the section are one and the same afterwards. Unlike
			 * vanilla, sky light is always present - the off-heap slot always
			 * carries it, and code all over the core reads it without checking,
			 * so a null here would be a crash in the Nether rather than a saving.
			 */
			this.blockLSBArray = new byte[4096];
			this.blockMSBArray = new NibbleArray(this.blockLSBArray.length, 4);
			this.blockMetadataArray = new NibbleArray(this.blockLSBArray.length, 4);
			this.blocklightArray = new NibbleArray(this.blockLSBArray.length, 4);
			this.skylightArray = new NibbleArray(this.blockLSBArray.length, 4);
			pushArraysToSlot();
		}
	}
	
	public ExtendedBlockStorage(int p_i1997_1_, boolean p_i1997_2_)
	{
		this(p_i1997_1_, p_i1997_2_, true);
	}

	public ExtendedBlockStorage(MemSlot slot, int yBase, int blockRefCount, int tickRefCount)
	{
		this.slot = slot;
		this.yBase = yBase;
		this.blockRefCount = blockRefCount;
		this.tickRefCount = tickRefCount;
		if(VANILLA_SHAPED)
			adoptArraysFromSlot();
	}

	//
	// Keeping the two views of one section in step. In VANILLA mode the fields
	// are the truth: a coremod may assign one directly, so the slot is brought
	// back in line whenever raw storage is handed out.
	//

	private HeapMemSlot heapSlot()
	{
		MemSlot local = this.slot;
		if(!(local instanceof HeapMemSlot))
			throw new IllegalStateException("chunk storage mode is " + ChunkStorageMode.current()
					+ " but this section is backed by " + (local == null ? "nothing" : local.getClass().getName()));
		return (HeapMemSlot)local;
	}

	/** Take the slot's arrays as this section's vanilla fields. */
	private void adoptArraysFromSlot()
	{
		HeapMemSlot heap = heapSlot();
		this.blockLSBArray = heap.lsbArray();
		this.blockMSBArray = new NibbleArray(heap.msbArray(), 4);
		this.blockMetadataArray = new NibbleArray(heap.metaArray(), 4);
		this.blocklightArray = new NibbleArray(heap.blocklightArray(), 4);
		this.skylightArray = new NibbleArray(heap.skylightArray(), 4);
	}

	/** Make the slot use whatever arrays the fields currently point at. */
	private void pushArraysToSlot()
	{
		HeapMemSlot heap = heapSlot();
		if(this.blockLSBArray != null && this.blockLSBArray != heap.lsbArray())
			heap.adoptLSB(this.blockLSBArray);
		if(this.blockMetadataArray != null && this.blockMetadataArray.data != heap.metaArray())
			heap.adoptBlockMetadata(this.blockMetadataArray.data);
		if(this.blocklightArray != null && this.blocklightArray.data != heap.blocklightArray())
			heap.adoptBlocklight(this.blocklightArray.data);

		if(this.blockMSBArray == null)
		{
			if(!this.msbDetached)
			{
				heap.zerofillMSB();
				this.msbDetached = true;
			}
		}
		else
		{
			this.msbDetached = false;
			if(this.blockMSBArray.data != heap.msbArray())
				heap.adoptMSB(this.blockMSBArray.data);
		}

		if(this.skylightArray == null)
		{
			if(!this.skylightDetached)
			{
				heap.zerofillSkylight();
				this.skylightDetached = true;
			}
		}
		else
		{
			this.skylightDetached = false;
			if(this.skylightArray.data != heap.skylightArray())
				heap.adoptSkylight(this.skylightArray.data);
		}
	}

	public Block getBlockByExtId(int p_150819_1_, int p_150819_2_, int p_150819_3_)
	{
		if(VANILLA_SHAPED)
		{
			int l = this.blockLSBArray[p_150819_2_ << 8 | p_150819_3_ << 4 | p_150819_1_] & 255;

			if (this.blockMSBArray != null)
			{
				l |= this.blockMSBArray.get(p_150819_1_, p_150819_2_, p_150819_3_) << 8;
			}

			return Block.getBlockById(l);
		}
		return Block.getBlockById(slot.getBlockId(p_150819_1_, p_150819_2_, p_150819_3_));
	}

	public void func_150818_a(int p_150818_1_, int p_150818_2_, int p_150818_3_, Block p_150818_4_)
	{
		Block block1 = VANILLA_SHAPED
				? this.getBlockByExtId(p_150818_1_, p_150818_2_, p_150818_3_)
				: Block.getBlockById(slot.getBlockId(p_150818_1_, p_150818_2_, p_150818_3_));

		if (block1 != Blocks.air)
		{
			--this.blockRefCount;

			if (block1.getTickRandomly())
			{
				--this.tickRefCount;
			}
		}

		if (p_150818_4_ != Blocks.air)
		{
			++this.blockRefCount;

			if (p_150818_4_.getTickRandomly())
			{
				++this.tickRefCount;
			}
		}

		int i1 = Block.getIdFromBlock(p_150818_4_);

		if(VANILLA_SHAPED)
		{
			this.blockLSBArray[p_150818_2_ << 8 | p_150818_3_ << 4 | p_150818_1_] = (byte)(i1 & 255);

			if (i1 > 255)
			{
				if (this.blockMSBArray == null)
				{
					this.blockMSBArray = new NibbleArray(this.blockLSBArray.length, 4);
					pushArraysToSlot();
				}

				this.blockMSBArray.set(p_150818_1_, p_150818_2_, p_150818_3_, (i1 & 3840) >> 8);
			}
			else if (this.blockMSBArray != null)
			{
				this.blockMSBArray.set(p_150818_1_, p_150818_2_, p_150818_3_, 0);
			}
		}
		else
		{
			slot.setBlockId(p_150818_1_, p_150818_2_, p_150818_3_, i1);
		}
	}

	public int getExtBlockMetadata(int p_76665_1_, int p_76665_2_, int p_76665_3_)
	{
		if(VANILLA_SHAPED)
			return this.blockMetadataArray.get(p_76665_1_, p_76665_2_, p_76665_3_);
		return slot.getMeta(p_76665_1_, p_76665_2_, p_76665_3_);
	}

	public void setExtBlockMetadata(int p_76654_1_, int p_76654_2_, int p_76654_3_, int p_76654_4_)
	{
		if(VANILLA_SHAPED)
			this.blockMetadataArray.set(p_76654_1_, p_76654_2_, p_76654_3_, p_76654_4_);
		else
			slot.setMeta(p_76654_1_, p_76654_2_, p_76654_3_, p_76654_4_);
	}

	public boolean isEmpty()
	{
		return this.blockRefCount == 0;
	}

	public boolean getNeedsRandomTick()
	{
		return this.tickRefCount > 0;
	}

	public int getYLocation()
	{
		return this.yBase;
	}

	public void setExtSkylightValue(int p_76657_1_, int p_76657_2_, int p_76657_3_, int p_76657_4_)
	{
		if(VANILLA_SHAPED)
			this.skylightArray.set(p_76657_1_, p_76657_2_, p_76657_3_, p_76657_4_);
		else
			slot.setSkylight(p_76657_1_, p_76657_2_, p_76657_3_, p_76657_4_);
	}

	public int getExtSkylightValue(int p_76670_1_, int p_76670_2_, int p_76670_3_)
	{
		if(VANILLA_SHAPED)
			return this.skylightArray.get(p_76670_1_, p_76670_2_, p_76670_3_);
		return slot.getSkylight(p_76670_1_, p_76670_2_, p_76670_3_);
	}

	public void setExtBlocklightValue(int p_76677_1_, int p_76677_2_, int p_76677_3_, int p_76677_4_)
	{
		if(VANILLA_SHAPED)
			this.blocklightArray.set(p_76677_1_, p_76677_2_, p_76677_3_, p_76677_4_);
		else
			slot.setBlocklight(p_76677_1_, p_76677_2_, p_76677_3_, p_76677_4_);
	}

	public int getExtBlocklightValue(int p_76674_1_, int p_76674_2_, int p_76674_3_)
	{
		if(VANILLA_SHAPED)
			return this.blocklightArray.get(p_76674_1_, p_76674_2_, p_76674_3_);
		return slot.getBlocklight(p_76674_1_, p_76674_2_, p_76674_3_);
	}

	public void removeInvalidBlocks()
	{
		this.blockRefCount = 0;
		this.tickRefCount = 0;

		for (int i = 0; i < 16; ++i)
		{
			for (int j = 0; j < 16; ++j)
			{
				for (int k = 0; k < 16; ++k)
				{
					// ultramine: replaced loop order from (x, y, z) to (y, z, x)
					Block block = this.getBlockByExtId(k, i, j);

					if (block != Blocks.air)
					{
						++this.blockRefCount;

						if (block.getTickRandomly())
						{
							++this.tickRefCount;
						}
					}
				}
			}
		}
	}

	/*
	 * Vanilla's array accessors. In VANILLA mode they are exactly what they say:
	 * the section's own storage, handed out and taken back. In OFF_HEAP mode the
	 * section is not made of arrays at all, so a getter can only materialize a
	 * copy - a caller that writes through one is writing to a throwaway, which is
	 * what the warning is about.
	 */

	public byte[] getBlockLSBArray()
	{
		if(VANILLA_SHAPED)
			return this.blockLSBArray;
		logDeprecation();
		return slot.copyLSB();
	}

	@SideOnly(Side.CLIENT)
	public void clearMSBArray()
	{
		if(VANILLA_SHAPED)
		{
			this.blockMSBArray = null;
			pushArraysToSlot();
		}
		else
			slot.zerofillMSB();
	}

	public NibbleArray getBlockMSBArray()
	{
		if(VANILLA_SHAPED)
			return this.blockMSBArray;
		logDeprecation();
		return new NibbleArray(slot.copyMSB(), 4);
	}

	public NibbleArray getMetadataArray()
	{
		if(VANILLA_SHAPED)
			return this.blockMetadataArray;
		logDeprecation();
		return new NibbleArray(slot.copyBlockMetadata(), 4);
	}

	public NibbleArray getBlocklightArray()
	{
		if(VANILLA_SHAPED)
			return this.blocklightArray;
		logDeprecation();
		return new NibbleArray(slot.copyBlocklight(), 4);
	}

	public NibbleArray getSkylightArray()
	{
		if(VANILLA_SHAPED)
			return this.skylightArray;
		logDeprecation();
		return new NibbleArray(slot.copySkylight(), 4);
	}

	public void setBlockLSBArray(byte[] p_76664_1_)
	{
		if(VANILLA_SHAPED)
		{
			this.blockLSBArray = p_76664_1_;
			pushArraysToSlot();
			return;
		}
		logDeprecation();
		slot.setLSB(p_76664_1_);
	}

	public void setBlockMSBArray(NibbleArray p_76673_1_)
	{
		if(VANILLA_SHAPED)
		{
			this.blockMSBArray = p_76673_1_;
			pushArraysToSlot();
			return;
		}
		logDeprecation();
		slot.setMSB(p_76673_1_.data);
	}

	public void setBlockMetadataArray(NibbleArray p_76668_1_)
	{
		if(VANILLA_SHAPED)
		{
			this.blockMetadataArray = p_76668_1_;
			pushArraysToSlot();
			return;
		}
		logDeprecation();
		slot.setBlockMetadata(p_76668_1_.data);
	}

	public void setBlocklightArray(NibbleArray p_76659_1_)
	{
		if(VANILLA_SHAPED)
		{
			this.blocklightArray = p_76659_1_;
			pushArraysToSlot();
			return;
		}
		logDeprecation();
		slot.setBlocklight(p_76659_1_.data);
	}

	public void setSkylightArray(NibbleArray p_76666_1_)
	{
		if(VANILLA_SHAPED)
		{
			this.skylightArray = p_76666_1_;
			pushArraysToSlot();
			return;
		}
		logDeprecation();
		slot.setSkylight(p_76666_1_.data);
	}

	@SideOnly(Side.CLIENT)
	public NibbleArray createBlockMSBArray()
	{
		if(VANILLA_SHAPED)
		{
			this.blockMSBArray = new NibbleArray(this.blockLSBArray.length, 4);
			pushArraysToSlot();
			return this.blockMSBArray;
		}
		logDeprecation();
		slot.zerofillMSB();
		return getBlockMSBArray();
	}

	private static final Logger log = LogManager.getLogger();
	
	private static void logDeprecation()
	{
		log.warn("Called deprecated method in ExtendedBlockStorage. It may have no effect intended by the modder or lead to performance issues", new Throwable());
	}
	
	/**
	 * Raw storage, used by the bulk paths - chunk save, chunk packet, snapshots.
	 * In VANILLA mode the fields are authoritative and a coremod may have
	 * assigned one directly, so the slot is realigned with them first; the check
	 * is five reference comparisons.
	 */
	public MemSlot getSlot()
	{
		MemSlot local = this.slot;
		if(VANILLA_SHAPED && local != null)
			pushArraysToSlot();
		return local;
	}

	public ExtendedBlockStorage copy()
	{
		slot.getClass(); //NPE
		if(VANILLA_SHAPED)
			pushArraysToSlot();
		return new ExtendedBlockStorage(slot.copy(), yBase, blockRefCount, tickRefCount);
	}
	
	public void release()
	{
		MemSlot slotLocal = this.slot;
		this.slot = null;
		slotLocal.release();
	}

	public void incBlockRefCount()
	{
		blockRefCount++;
	}
}
