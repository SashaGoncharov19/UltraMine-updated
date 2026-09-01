package net.minecraftforge.common.util;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.junit.Test;

/**
 * Pins the varargs handling in {@link EnumHelper#invokeWithExactArgs}.
 *
 * <p>Deliberately tested through that method rather than through
 * {@code addEnum}. {@code setup()} prefers {@code sun.reflect.ReflectionFactory},
 * which still resolves on Java 8, so an {@code addEnum} test running on the
 * project's Java 8 toolchain would never reach the MethodHandle path and would
 * pass without exercising the bug at all.
 */
public class EnumHelperVarargsTest
{
	/** The shape of an enum constructor whose last parameter is varargs. */
	static class VarargsCtor
	{
		VarargsCtor(String name, int ordinal, Class<?>... types)
		{
			assertNotNull(types);
		}
	}

	static class PlainCtor
	{
		PlainCtor(String name, int ordinal)
		{
		}
	}

	private static MethodHandle handle(Class<?> owner, Class<?>... params) throws Exception
	{
		return MethodHandles.lookup().findConstructor(owner, MethodType.methodType(void.class, params));
	}

	/** Without this, the test below would prove nothing. */
	@Test
	public void aVarargsConstructorHandleReallyIsACollector() throws Exception
	{
		MethodHandle ctor = handle(VarargsCtor.class, String.class, int.class, Class[].class);
		assertTrue(ctor.isVarargsCollector());

		try
		{
			ctor.invokeWithArguments(exactArgs());
			fail("expected the collector to mis-read the already-built argument array");
		}
		catch(ClassCastException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("java.lang.Class"));
		}
		catch(Throwable t)
		{
			fail("expected ClassCastException, got " + t);
		}
	}

	@Test
	public void exactArgsReachAVarargsConstructor() throws Throwable
	{
		MethodHandle ctor = handle(VarargsCtor.class, String.class, int.class, Class[].class);
		assertNotNull(EnumHelper.invokeWithExactArgs(ctor, exactArgs()));
	}

	/** asFixedArity is a no-op on a handle that is not a collector; prove it. */
	@Test
	public void aPlainConstructorStillWorks() throws Throwable
	{
		MethodHandle ctor = handle(PlainCtor.class, String.class, int.class);
		assertNotNull(EnumHelper.invokeWithExactArgs(ctor, new Object[]{"NAME", Integer.valueOf(0)}));
	}

	/** What makeEnum builds: name, ordinal, then the declared parameters. */
	private static Object[] exactArgs()
	{
		return new Object[]{"NAME", Integer.valueOf(0), new Class<?>[]{String.class}};
	}
}
