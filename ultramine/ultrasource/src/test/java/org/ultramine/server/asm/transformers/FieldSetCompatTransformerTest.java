package org.ultramine.server.asm.transformers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Pins the redirect described in issue #22: mod code assigning a static final
 * field through reflection fails from Java 12 on, because {@code Field.modifiers}
 * is no longer reachable to strip {@code FINAL} with.
 */
public class FieldSetCompatTransformerTest
{
	private static final String COMPAT = "org/ultramine/server/compat/ReflectionCompat";

	/** A class whose one method calls {@code field.set(target, value)}. */
	private static byte[] callerCallingFieldSet(String internalName)
	{
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "poke",
				"(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V", null,
				new String[]{"java/lang/IllegalAccessException"});
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "set",
				"(Ljava/lang/Object;Ljava/lang/Object;)V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(3, 3);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static MethodInsnNode theOnlyCall(byte[] cls)
	{
		ClassNode node = new ClassNode();
		new ClassReader(cls).accept(node, 0);
		for(MethodNode m : node.methods)
			for(AbstractInsnNode insn : m.instructions.toArray())
				if(insn instanceof MethodInsnNode)
					return (MethodInsnNode)insn;
		return null;
	}

	@Test
	public void modCodeIsRoutedThroughTheBridge()
	{
		byte[] after = new UMTransformerCollection()
				.transform("some.Mod", "some.Mod", callerCallingFieldSet("some/Mod"));
		MethodInsnNode call = theOnlyCall(after);

		assertEquals("must become a static call on the bridge", Opcodes.INVOKESTATIC, call.getOpcode());
		assertEquals(COMPAT, call.owner);
		assertEquals("set", call.name);
		// The receiver becomes the first argument, so the operand stack is
		// unchanged and the original frames stay valid.
		assertEquals("(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V", call.desc);
	}

	/** Rewriting the bridge would make it call itself instead of Field.set. */
	@Test
	public void theBridgeItselfIsLeftAlone()
	{
		String name = "org.ultramine.server.compat.ReflectionCompat";
		byte[] before = callerCallingFieldSet(COMPAT);
		byte[] after = new UMTransformerCollection().transform(name, name, before);
		assertSame("the bridge must not be rewritten", before, after);
	}

	/**
	 * The behaviour the redirect exists to restore, on the same shape as the
	 * failure that prompted it: a {@code static final List} the mod replaces
	 * wholesale.
	 */
	@Test
	public void theBridgeWritesAStaticFinalFieldReflectionCannot() throws Exception
	{
		Field field = Target.class.getDeclaredField("LOOT");
		List<String> replacement = new ArrayList<String>();
		replacement.add("changed");

		org.ultramine.server.compat.ReflectionCompat.set(field, null, replacement);
		assertEquals(replacement, Target.readBack());
	}

	/** A field reflection can write must go through untouched, not via Unsafe. */
	@Test
	public void aWritableFieldGoesThroughUnchanged() throws Exception
	{
		Field field = Target.class.getDeclaredField("mutable");
		field.setAccessible(true);
		org.ultramine.server.compat.ReflectionCompat.set(field, null, "ok");
		assertEquals("ok", Target.mutable);
	}

	public static class Target
	{
		/**
		 * Deliberately not a compile-time constant. A {@code static final String}
		 * initialised from a literal is inlined at every use site, so the test
		 * would pass while the field never changed.
		 */
		private static final List<String> LOOT = new ArrayList<String>();
		static String mutable = "";

		static List<String> readBack()
		{
			return LOOT;
		}
	}
}
