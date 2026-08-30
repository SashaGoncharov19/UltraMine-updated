package cpw.mods.fml.common.registry;

import java.lang.reflect.Field;

import net.minecraft.item.ItemStack;

import org.apache.logging.log4j.Level;

import sun.misc.Unsafe;

import com.google.common.base.Throwables;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.registry.GameRegistry.ItemStackHolder;


/**
 * Internal class used in tracking {@link ItemStackHolder} references
 *
 * @author cpw
 *
 */
class ItemStackHolderRef {
	private Field field;
	private String itemName;
	private int meta;
	private String serializednbt;


	ItemStackHolderRef(Field field, String itemName, int meta, String serializednbt)
	{
		this.field = field;
		this.itemName = itemName;
		this.meta = meta;
		this.serializednbt = serializednbt;
		makeWritable(field);
	}

	//The historical implementation stripped FINAL via Field.modifiers and wrote
	//through sun.reflect.ReflectionFactory field accessors - both are gone on
	//modern JVMs (Field.modifiers is reflection-filtered since 12, the factory
	//methods since 9). Unsafe static-field writes work on Java 8 and 25 alike.
	private static Unsafe unsafe;
	private static void makeWritable(Field f)
	{
		try
		{
			if (unsafe == null)
			{
				Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
				theUnsafe.setAccessible(true);
				unsafe = (Unsafe) theUnsafe.get(null);
			}
		} catch (Exception e)
		{
			throw Throwables.propagate(e);
		}
	}

	public void apply()
	{
		ItemStack is;
		try
		{
			is = GameRegistry.makeItemStack(itemName, meta, 1, serializednbt);
		} catch (RuntimeException e)
		{
			FMLLog.getLogger().log(Level.ERROR, "Caught exception processing itemstack {},{},{} in annotation at {}.{}", itemName, meta, serializednbt,field.getClass().getName(),field.getName());
			throw e;
		}
		try
		{
			Object base = unsafe.staticFieldBase(field);
			long offset = unsafe.staticFieldOffset(field);
			unsafe.putObject(base, offset, is);
		}
		catch (Exception e)
		{
			FMLLog.getLogger().log(Level.WARN, "Unable to set {} with value {},{},{}", this.field, this.itemName, this.meta, this.serializednbt);
		}
	}
}
