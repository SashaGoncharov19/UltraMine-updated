package org.ultramine.server.asm.transformers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Pins the repair described in issue #22: a lambda declared inside an interface
 * ships with a {@code CONSTANT_Methodref} pointing at the interface itself,
 * which every JVM from 9 on rejects.
 *
 * <p>The assertions are on the constant pool tag in the emitted bytes, not on
 * the ASM model, because the tag is what the JVM reads and reports on. A test
 * that only checked {@code Handle.isInterface()} would pass on a class the JVM
 * still refuses to load.
 */
public class InterfaceMethodrefRepairTransformerTest
{
	private static final String IFACE = "test/Malformed";
	private static final int CONSTANT_METHODREF = 10;
	private static final int CONSTANT_INTERFACE_METHODREF = 11;

	/**
	 * Builds the shape javac 8 produced: an interface with a static factory
	 * whose invokedynamic bootstrap argument is a handle on the interface's own
	 * private static lambda body, tagged as a plain Methodref.
	 */
	private static byte[] malformedInterface()
	{
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
				IFACE, null, "java/lang/Object", null);

		cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "apply", "(Ljava/lang/String;)Ljava/lang/String;",
				null, null).visitEnd();

		Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC,
				"java/lang/invoke/LambdaMetafactory", "metafactory",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
						+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
						+ "Ljava/lang/invoke/CallSite;",
				false);

		// isInterface = false on a handle whose owner is this very interface.
		// That is exactly the defect; ASM emits CONSTANT_Methodref for it.
		Handle body = new Handle(Opcodes.H_INVOKESTATIC, IFACE, "lambda$make$0",
				"(Ljava/lang/String;)Ljava/lang/String;", false);

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()L" + IFACE + ";", null, null);
		mv.visitCode();
		mv.visitInvokeDynamicInsn("apply", "()L" + IFACE + ";", metafactory,
				Type.getMethodType("(Ljava/lang/String;)Ljava/lang/String;"),
				body,
				Type.getMethodType("(Ljava/lang/String;)Ljava/lang/String;"));
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 0);
		mv.visitEnd();

		MethodVisitor body_mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
				"lambda$make$0", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
		body_mv.visitCode();
		body_mv.visitVarInsn(Opcodes.ALOAD, 0);
		body_mv.visitInsn(Opcodes.ARETURN);
		body_mv.visitMaxs(1, 1);
		body_mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A class, not an interface, referring to its own static method. Legal as-is. */
	private static byte[] plainClass()
	{
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Plain", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "callSelf", "()V", null, null);
		mv.visitCode();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "test/Plain", "callSelf", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * Counts constant pool entries with the given tag, reading the pool the way
	 * the JVM does rather than trusting ASM to report it back.
	 */
	private static int countPoolTag(byte[] cls, int wantedTag)
	{
		ClassReader cr = new ClassReader(cls);
		int count = 0;
		for(int i = 1; i < cr.getItemCount(); i++)
		{
			int off = cr.getItem(i);
			// 0 marks the unused second slot of a long or double entry
			if(off == 0)
				continue;
			// getItem points one past the tag byte
			if(cr.readByte(off - 1) == wantedTag)
				count++;
		}
		return count;
	}

	@Test
	public void fixtureReallyIsMalformed()
	{
		byte[] before = malformedInterface();
		// Two Methodrefs: LambdaMetafactory.metafactory, which is legitimately a
		// class reference, and the lambda body on this interface, which is not.
		assertEquals("fixture must carry the defect being repaired, or the test proves nothing",
				2, countPoolTag(before, CONSTANT_METHODREF));
		assertEquals(0, countPoolTag(before, CONSTANT_INTERFACE_METHODREF));
	}

	@Test
	public void selfReferenceInAnInterfaceBecomesAnInterfaceMethodref()
	{
		byte[] after = transform(malformedInterface());
		assertNotNull(after);
		// The LambdaMetafactory reference stays a Methodref - it really is a
		// class - and only the self-reference is retagged.
		assertEquals("only the self-reference should have been retagged",
				1, countPoolTag(after, CONSTANT_METHODREF));
		assertEquals("the lambda body must now be an InterfaceMethodref",
				1, countPoolTag(after, CONSTANT_INTERFACE_METHODREF));
	}

	/**
	 * The end-to-end proof. Defining the class is not enough to surface the
	 * defect - the JVM reports it when the invokedynamic is resolved - so this
	 * actually calls the factory. Java 8 links the malformed class happily,
	 * which is the entire reason the bug went unnoticed for a decade, so the
	 * check only runs where the JVM can show it.
	 */
	@Test
	public void theRepairedClassLinksOnAJvmThatEnforcesTheRule() throws Exception
	{
		Assume.assumeTrue("only Java 9+ rejects the malformed reference",
				!System.getProperty("java.specification.version").startsWith("1."));

		try
		{
			link(malformedInterface());
			fail("expected the malformed class to be rejected at resolution");
		}
		catch(Throwable expected)
		{
			Throwable root = expected;
			while(root.getCause() != null)
				root = root.getCause();
			assertTrue("expected IncompatibleClassChangeError, got " + root,
					root instanceof IncompatibleClassChangeError);
		}

		assertNotNull("the repaired class must link and run", link(transform(malformedInterface())));
	}

	/** Defines the class and resolves its invokedynamic by calling the factory. */
	private static Object link(byte[] cls) throws Exception
	{
		return new Defining().define(cls).getMethod("make").invoke(null);
	}

	private static final class Defining extends ClassLoader
	{
		Defining()
		{
			super(InterfaceMethodrefRepairTransformerTest.class.getClassLoader());
		}

		Class<?> define(byte[] b)
		{
			return defineClass(IFACE.replace('/', '.'), b, 0, b.length);
		}
	}

	@Test
	public void theRepairedHandleStillNamesTheSameMethod()
	{
		ClassNode node = read(transform(malformedInterface()));
		Handle body = firstLambdaBodyHandle(node);
		assertNotNull("the invokedynamic bootstrap argument should still be there", body);
		assertTrue("handle must now be marked as an interface reference", body.isInterface());
		assertEquals(IFACE, body.getOwner());
		assertEquals("lambda$make$0", body.getName());
		assertEquals("(Ljava/lang/String;)Ljava/lang/String;", body.getDesc());
		assertEquals(Opcodes.H_INVOKESTATIC, body.getTag());
	}

	@Test
	public void aPlainClassIsLeftAlone()
	{
		byte[] before = plainClass();
		byte[] after = new UMTransformerCollection().transform("test.Plain", "test.Plain", before);
		assertSame("a non-interface must not be rewritten at all", before, after);
	}

	private static byte[] transform(byte[] cls)
	{
		return new UMTransformerCollection().transform("test.Malformed", "test.Malformed", cls);
	}

	/** The handle among the bootstrap arguments that points back at this interface. */
	private static Handle firstLambdaBodyHandle(ClassNode node)
	{
		for(MethodNode m : node.methods)
		{
			if(m.instructions == null)
				continue;
			for(AbstractInsnNode insn : m.instructions.toArray())
			{
				if(!(insn instanceof InvokeDynamicInsnNode))
					continue;
				for(Object arg : ((InvokeDynamicInsnNode)insn).bsmArgs)
					if(arg instanceof Handle && node.name.equals(((Handle)arg).getOwner()))
						return (Handle)arg;
			}
		}
		return null;
	}

	private static ClassNode read(byte[] cls)
	{
		ClassNode node = new ClassNode();
		new ClassReader(cls).accept(node, 0);
		return node;
	}
}
