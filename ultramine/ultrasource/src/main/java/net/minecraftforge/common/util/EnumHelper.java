package net.minecraftforge.common.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.*;

import sun.misc.Unsafe;

import cpw.mods.fml.common.FMLLog;
import net.minecraft.block.BlockPressurePlate.Sensitivity;
import net.minecraft.block.material.Material;
import net.minecraft.enchantment.EnumEnchantmentType;
import net.minecraft.entity.Entity.EnumEntitySize;
import net.minecraft.entity.EnumCreatureAttribute;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.item.EntityPainting.EnumArt;
import net.minecraft.entity.player.EntityPlayer.EnumStatus;
import net.minecraft.item.EnumAction;
import net.minecraft.item.EnumRarity;
import net.minecraft.item.Item.ToolMaterial;
import net.minecraft.item.ItemArmor.ArmorMaterial;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.gen.structure.StructureStrongholdPieces.Stronghold.Door;
import net.minecraftforge.classloading.FMLForgePlugin;

public class EnumHelper
{
	private static Object reflectionFactory      = null;
	private static Method newConstructorAccessor = null;
	private static Method newInstance            = null;
	private static Method newFieldAccessor       = null;
	private static Method fieldAccessorSet       = null;
	//Modern JVMs (9+) have no usable sun.reflect accessors; enum constants are
	//constructed through the trusted MethodHandles lookup instead (the "cannot
	//reflectively create enum objects" check exists only in Constructor.newInstance,
	//not in the MethodHandle layer), and fields are written through Unsafe.
	private static MethodHandles.Lookup implLookup = null;
	private static Unsafe unsafe                 = null;
	private static boolean isSetup               = false;

	//Some enums are decompiled with extra arguments, so lets check for that
	@SuppressWarnings("rawtypes")
	private static Class[][] commonTypes =
	{
		{EnumAction.class},
		{ArmorMaterial.class, int.class, int[].class, int.class},
		{EnumArt.class, String.class, int.class, int.class, int.class, int.class},
		{EnumCreatureAttribute.class},
		{EnumCreatureType.class, Class.class, int.class, Material.class, boolean.class, boolean.class},
		{Door.class},
		{EnumEnchantmentType.class},
		{EnumEntitySize.class},
		{Sensitivity.class},
		{MovingObjectType.class},
		{EnumSkyBlock.class, int.class},
		{EnumStatus.class},
		{ToolMaterial.class, int.class, int.class, float.class, float.class, int.class},
		{EnumRarity.class, EnumChatFormatting.class, String.class}
	};

	public static EnumAction addAction(String name)
	{
		return addEnum(EnumAction.class, name);
	}
	public static ArmorMaterial addArmorMaterial(String name, int durability, int[] reductionAmounts, int enchantability)
	{
		return addEnum(ArmorMaterial.class, name, durability, reductionAmounts, enchantability);
	}
	public static EnumArt addArt(String name, String tile, int sizeX, int sizeY, int offsetX, int offsetY)
	{
		return addEnum(EnumArt.class, name, tile, sizeX, sizeY, offsetX, offsetY);
	}
	public static EnumCreatureAttribute addCreatureAttribute(String name)
	{
		return addEnum(EnumCreatureAttribute.class, name);
	}
	@SuppressWarnings("rawtypes")
	public static EnumCreatureType addCreatureType(String name, Class typeClass, int maxNumber, Material material, boolean peaceful, boolean animal)
	{
		return addEnum(EnumCreatureType.class, name, typeClass, maxNumber, material, peaceful, animal);
	}
	public static Door addDoor(String name)
	{
		return addEnum(Door.class, name);
	}
	public static EnumEnchantmentType addEnchantmentType(String name)
	{
		return addEnum(EnumEnchantmentType.class, name);
	}
	public static EnumEntitySize addEntitySize(String name)
	{
		return addEnum(EnumEntitySize.class, name);
	}
	public static Sensitivity addSensitivity(String name)
	{
		return addEnum(Sensitivity.class, name);
	}
	public static MovingObjectType addMovingObjectType(String name)
	{
		return addEnum(MovingObjectType.class, name);
	}
	public static EnumSkyBlock addSkyBlock(String name, int lightValue)
	{
		return addEnum(EnumSkyBlock.class, name, lightValue);
	}
	public static EnumStatus addStatus(String name)
	{
		return addEnum(EnumStatus.class, name);
	}
	public static ToolMaterial addToolMaterial(String name, int harvestLevel, int maxUses, float efficiency, float damage, int enchantability)
	{
		return addEnum(ToolMaterial.class, name, harvestLevel, maxUses, efficiency, damage, enchantability);
	}
	public static EnumRarity addRarity(String name, EnumChatFormatting color, String displayName)
	{
		return addEnum(EnumRarity.class, name, color, displayName);
	}

	private static void setup()
	{
		if (isSetup)
		{
			return;
		}

		try
		{
			Method getReflectionFactory = Class.forName("sun.reflect.ReflectionFactory").getDeclaredMethod("getReflectionFactory");
			reflectionFactory      = getReflectionFactory.invoke(null);
			newConstructorAccessor = Class.forName("sun.reflect.ReflectionFactory").getDeclaredMethod("newConstructorAccessor", Constructor.class);
			newInstance            = Class.forName("sun.reflect.ConstructorAccessor").getDeclaredMethod("newInstance", Object[].class);
			newFieldAccessor       = Class.forName("sun.reflect.ReflectionFactory").getDeclaredMethod("newFieldAccessor", Field.class, boolean.class);
			fieldAccessorSet       = Class.forName("sun.reflect.FieldAccessor").getDeclaredMethod("set", Object.class, Object.class);
		}
		catch (Exception e)
		{
			try
			{
				Field implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
				Unsafe u = getUnsafe();
				implLookup = (MethodHandles.Lookup)u.getObject(u.staticFieldBase(implLookupField), u.staticFieldOffset(implLookupField));
			}
			catch (Exception e2)
			{
				e.printStackTrace();
				e2.printStackTrace();
			}
		}

		isSetup = true;
	}

	/*
	 * Everything below this is found at the site below, and updated to be able to compile in Eclipse/Java 1.6+
	 * Also modified for use in decompiled code.
	 * Found at: http://niceideas.ch/roller2/badtrash/entry/java_create_enum_instances_dynamically
	 */
	private static Object getConstructorAccessor(Class<?> enumClass, Class<?>[] additionalParameterTypes) throws Exception
	{
		Class<?>[] parameterTypes = new Class[additionalParameterTypes.length + 2];
		parameterTypes[0] = String.class;
		parameterTypes[1] = int.class;
		System.arraycopy(additionalParameterTypes, 0, parameterTypes, 2, additionalParameterTypes.length);
		return newConstructorAccessor.invoke(reflectionFactory, enumClass.getDeclaredConstructor(parameterTypes));
	}

	private static < T extends Enum<? >> T makeEnum(Class<T> enumClass, String value, int ordinal, Class<?>[] additionalTypes, Object[] additionalValues) throws Exception
	{
		Object[] parms = new Object[additionalValues.length + 2];
		parms[0] = value;
		parms[1] = Integer.valueOf(ordinal);
		System.arraycopy(additionalValues, 0, parms, 2, additionalValues.length);
		if (implLookup != null)
		{
			Class<?>[] parameterTypes = new Class[additionalTypes.length + 2];
			parameterTypes[0] = String.class;
			parameterTypes[1] = int.class;
			System.arraycopy(additionalTypes, 0, parameterTypes, 2, additionalTypes.length);
			try
			{
				MethodHandle ctor = implLookup.findConstructor(enumClass, MethodType.methodType(void.class, parameterTypes));
				return enumClass.cast(invokeWithExactArgs(ctor, parms));
			}
			catch (Exception e)
			{
				throw e;
			}
			catch (Throwable t)
			{
				throw new RuntimeException(t);
			}
		}
		return enumClass.cast(newInstance.invoke(getConstructorAccessor(enumClass, additionalTypes), new Object[] {parms}));
	}

	/*
	 * ultramine: an enum whose constructor ends in a varargs parameter yields a
	 * varargs-collector handle from findConstructor, and invokeWithArguments then
	 * reads the argument list as the *spread* form - so the parameter array built
	 * above, already in exact positional shape, is taken as one more element to
	 * collect and the cast fails:
	 *
	 *   ClassCastException: Cannot cast [Ljava.lang.Class; to java.lang.Class
	 *     at MethodHandleImpl$AsVarargsCollector.invokeWithArguments
	 *     at EnumHelper.makeEnum
	 *
	 * asFixedArity says what is actually meant: these arguments are the declared
	 * parameters, one for one. It is a no-op on a handle that is not a collector.
	 *
	 * Only Java 9+ reaches this at all - setup() prefers sun.reflect.
	 * ReflectionFactory, whose ConstructorAccessor takes exact positional
	 * arguments and never had the problem - which is why a pack could add
	 * varargs enum values happily on Java 8 and die on a modern JVM.
	 */
	static Object invokeWithExactArgs(MethodHandle ctor, Object[] parms) throws Throwable
	{
		return ctor.asFixedArity().invokeWithArguments(parms);
	}

	private static Unsafe getUnsafe() throws Exception
	{
		if (unsafe == null)
		{
			Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
			theUnsafe.setAccessible(true);
			unsafe = (Unsafe)theUnsafe.get(null);
		}
		return unsafe;
	}

	//Unsafe writes need no FINAL stripping via Field.modifiers (reflection-filtered
	//since Java 12) and no accessibility, so they work on Java 8 and 25 alike -
	//including on java.base fields like the Class enum caches blanked below.
	public static void setFailsafeFieldValue(Field field, Object target, Object value) throws Exception
	{
		try
		{
			field.setAccessible(true);
		}
		catch (RuntimeException e)
		{
			//InaccessibleObjectException for java.base fields on modern JVMs -
			//the Unsafe write below does not need accessibility.
		}
		Unsafe u = getUnsafe();
		if (Modifier.isStatic(field.getModifiers()))
		{
			u.putObject(u.staticFieldBase(field), u.staticFieldOffset(field), value);
		}
		else
		{
			u.putObject(target, u.objectFieldOffset(field), value);
		}
	}

	private static void blankField(Class<?> enumClass, String fieldName) throws Exception
	{
		for (Field field : Class.class.getDeclaredFields())
		{
			if (field.getName().contains(fieldName))
			{
				setFailsafeFieldValue(field, enumClass, null);
				break;
			}
		}
	}

	private static void cleanEnumCache(Class<?> enumClass) throws Exception
	{
		blankField(enumClass, "enumConstantDirectory");
		blankField(enumClass, "enumConstants");
	}

	public static <T extends Enum<? >> T addEnum(Class<T> enumType, String enumName, Object... paramValues)
	{
		setup();
		return addEnum(commonTypes, enumType, enumName, paramValues);
	}

	@SuppressWarnings("rawtypes")
	public static <T extends Enum<? >> T addEnum(Class[][] map, Class<T> enumType, String enumName, Object... paramValues)
	{
		for (Class[] lookup : map)
		{
			if (lookup[0] == enumType)
			{
				Class<?>[] paramTypes = new Class<?>[lookup.length - 1];
				if (paramTypes.length > 0)
				{
					System.arraycopy(lookup, 1, paramTypes, 0, paramTypes.length);
				}
				return addEnum(enumType, enumName, paramTypes, paramValues);
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public static <T extends Enum<? >> T addEnum(Class<T> enumType, String enumName, Class<?>[] paramTypes, Object[] paramValues)
	{
		if (!isSetup)
		{
			setup();
		}

		Field valuesField = null;
		Field[] fields = enumType.getDeclaredFields();

		for (Field field : fields)
		{
			String name = field.getName();
			if (name.equals("$VALUES") || name.equals("ENUM$VALUES")) //Added 'ENUM$VALUES' because Eclipse's internal compiler doesn't follow standards
			{
				valuesField = field;
				break;
			}
		}

		int flags = (FMLForgePlugin.RUNTIME_DEOBF ? Modifier.PUBLIC : Modifier.PRIVATE) | Modifier.STATIC | Modifier.FINAL | 0x1000 /*SYNTHETIC*/;
		if (valuesField == null)
		{
			String valueType = String.format("[L%s;", enumType.getName().replace('.', '/'));

			for (Field field : fields)
			{
				if ((field.getModifiers() & flags) == flags &&
					 field.getType().getName().replace('.', '/').equals(valueType)) //Apparently some JVMs return .'s and some don't..
				{
					valuesField = field;
					break;
				}
			}
		}

		if (valuesField == null)
		{
			FMLLog.severe("Could not find $VALUES field for enum: %s", enumType.getName());
			FMLLog.severe("Runtime Deobf: %s", FMLForgePlugin.RUNTIME_DEOBF);
			FMLLog.severe("Flags: %s", String.format("%16s", Integer.toBinaryString(flags)).replace(' ', '0'));
			FMLLog.severe("Fields:");
			for (Field field : fields)
			{
				String mods = String.format("%16s", Integer.toBinaryString(field.getModifiers())).replace(' ', '0');
				FMLLog.severe("       %s %s: %s", mods, field.getName(), field.getType().getName());
			}
			return null;
		}

		valuesField.setAccessible(true);

		try
		{
			T[] previousValues = (T[])valuesField.get(enumType);
			List<T> values = new ArrayList<T>(Arrays.asList(previousValues));
			T newValue = (T)makeEnum(enumType, enumName, values.size(), paramTypes, paramValues);
			values.add(newValue);
			setFailsafeFieldValue(valuesField, null, values.toArray((T[]) Array.newInstance(enumType, 0)));
			cleanEnumCache(enumType);

			return newValue;
		}
		catch (Exception e)
		{
			e.printStackTrace();
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	static
	{
		if (!isSetup)
		{
			setup();
		}
	}
}