package org.ultramine.server.util;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import net.minecraft.entity.DataWatcher.WatchableObject;

/**
 * Array-backed storage for {@link net.minecraft.entity.DataWatcher}'s watched objects.
 *
 * UltraMine replaces vanilla's HashMap with direct id-indexed array access (no boxing,
 * no hashing on the per-entity tick path). The field itself must still be a Map: the
 * mod ecosystem reaches into it by its vanilla shape - CoreTweaks' DataWatcher
 * diagnostics @Shadow it as {@code Map<Integer, WatchableObject>} and hard-fail the
 * whole class otherwise. This is both: a Map to the outside, an array to the core.
 *
 * The array grows on demand, so mods that lift vanilla's 32-id ceiling (EndlessIDs)
 * work as well.
 */
public class WatchableObjectMap extends AbstractMap<Integer, WatchableObject>
{
	//volatile: DataWatcher reads watched objects without holding its lock (packet
	//threads do, via getAllWatched/func_151509_a), and unlike the fixed final
	//array this one is replaced when it grows - the volatile write publishes the
	//copied contents along with the new reference. A volatile read is free on x86.
	private volatile WatchableObject[] slots = new WatchableObject[32];
	private int size;

	/** Array access for the core's own hot paths - iterate under DataWatcher's lock. */
	public WatchableObject[] array()
	{
		return slots;
	}

	public WatchableObject getById(int id)
	{
		WatchableObject[] arr = slots;
		return id >= 0 && id < arr.length ? arr[id] : null;
	}

	public void putById(int id, WatchableObject value)
	{
		if(id < 0)
			throw new IndexOutOfBoundsException("Data value id " + id + " is negative");
		if(id >= slots.length)
		{
			int newLength = slots.length;
			while(newLength <= id)
				newLength <<= 1;
			WatchableObject[] grown = new WatchableObject[newLength];
			System.arraycopy(slots, 0, grown, 0, slots.length);
			slots = grown;
		}
		if(slots[id] == null && value != null)
			size++;
		else if(slots[id] != null && value == null)
			size--;
		slots[id] = value;
	}

	@Override
	public int size()
	{
		return size;
	}

	@Override
	public boolean containsKey(Object key)
	{
		return key instanceof Integer && getById(((Integer)key).intValue()) != null;
	}

	@Override
	public WatchableObject get(Object key)
	{
		return key instanceof Integer ? getById(((Integer)key).intValue()) : null;
	}

	@Override
	public WatchableObject put(Integer key, WatchableObject value)
	{
		WatchableObject previous = getById(key.intValue());
		putById(key.intValue(), value);
		return previous;
	}

	@Override
	public WatchableObject remove(Object key)
	{
		if(!(key instanceof Integer))
			return null;
		int id = ((Integer)key).intValue();
		WatchableObject previous = getById(id);
		if(previous != null)
			putById(id, null);
		return previous;
	}

	@Override
	public Set<Map.Entry<Integer, WatchableObject>> entrySet()
	{
		return new AbstractSet<Map.Entry<Integer, WatchableObject>>()
		{
			@Override
			public int size()
			{
				return size;
			}

			@Override
			public Iterator<Map.Entry<Integer, WatchableObject>> iterator()
			{
				return new Iterator<Map.Entry<Integer, WatchableObject>>()
				{
					private int next = advance(0);

					private int advance(int from)
					{
						WatchableObject[] arr = slots;
						while(from < arr.length && arr[from] == null)
							from++;
						return from;
					}

					@Override
					public boolean hasNext()
					{
						return next < slots.length;
					}

					@Override
					public Map.Entry<Integer, WatchableObject> next()
					{
						if(!hasNext())
							throw new NoSuchElementException();
						int id = next;
						next = advance(id + 1);
						return new AbstractMap.SimpleEntry<Integer, WatchableObject>(Integer.valueOf(id), slots[id]);
					}

					@Override
					public void remove()
					{
						throw new UnsupportedOperationException();
					}
				};
			}
		};
	}
}
