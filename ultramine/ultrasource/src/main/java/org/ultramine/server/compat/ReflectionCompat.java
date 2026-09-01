package org.ultramine.server.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import net.minecraftforge.common.util.EnumHelper;

/**
 * Makes {@link Field#set} on a {@code static final} field behave the way it did
 * on Java 8, where mods of this era were written and tested.
 *
 * <p>The idiom is everywhere in 1.7.10 code: strip {@code FINAL} by reflecting on
 * {@code Field.modifiers}, then assign. {@code Field.modifiers} has been filtered
 * out of reflection since Java 12, so the stripping silently does nothing and the
 * assignment throws:
 *
 * <pre>
 * RuntimeException: java.lang.IllegalAccessException: Can not set static final
 *   java.util.List field thaumcraft.common.entities.ai.interact.AIFish.LOOTCRAP
 *   to java.util.ArrayList
 * </pre>
 *
 * <p>Whatever the mod was populating stays empty, usually without stopping the
 * server — so this is worse than a crash: the pack runs with a feature quietly
 * broken.
 *
 * <p>The fallback is the same {@code Unsafe} write this core already uses for its
 * own static finals ({@code ObjectHolderRef}, {@code ItemStackHolderRef},
 * {@code EnumHelper}), which needs neither {@code FINAL} stripping nor
 * accessibility and so works on Java 8 and 25 alike.
 *
 * <p>ultramine: divergence from vanilla FML, which has no such bridge.
 */
public final class ReflectionCompat
{
	private ReflectionCompat()
	{
	}

	/**
	 * Drop-in replacement for {@code field.set(target, value)}.
	 *
	 * <p>The direct assignment is attempted first, so anything that works
	 * unaided keeps working and keeps its own error behaviour. Only a
	 * {@code final} field falls back, because that is the case Java 9+ changed;
	 * an {@link IllegalAccessException} for any other reason is a real error and
	 * is rethrown untouched rather than quietly forced through.
	 */
	public static void set(Field field, Object target, Object value) throws IllegalAccessException
	{
		try
		{
			field.set(target, value);
			return;
		}
		catch(IllegalAccessException direct)
		{
			if(!Modifier.isFinal(field.getModifiers()))
				throw direct;

			try
			{
				EnumHelper.setFailsafeFieldValue(field, target, value);
			}
			catch(Exception fallback)
			{
				//The original exception is the one that describes the program's
				//mistake; the fallback failing is a detail of how we tried to
				//paper over it.
				direct.addSuppressed(fallback);
				throw direct;
			}
		}
	}
}
