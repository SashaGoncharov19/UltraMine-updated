package cpw.mods.fml.common.discovery.asm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Mod discovery reads every class file in every jar on the class path, and the
 * loop that does it abandons the whole jar the moment one entry throws. So an
 * entry it cannot make sense of is not a curiosity - it is a mod that silently
 * never loads, with one warning to say so.
 *
 * <p>The entry that does it is a module descriptor: {@code module-info.class}
 * has no super type, which multi-release jars have carried since Java 9 and
 * which the shipped log4j is one of.
 */
public class ASMModParserTest
{
	/** A real module descriptor, built rather than described. */
	private static byte[] moduleInfo()
	{
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V9, Opcodes.ACC_MODULE, "module-info", null, null, null);
		ModuleVisitor module = writer.visitModule("test.module", 0, null);
		module.visitRequire("java.base", Opcodes.ACC_MANDATED, null);
		module.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static byte[] ordinaryClass(String name, String superName)
	{
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, name, null, superName, null);
		writer.visitEnd();
		return writer.toByteArray();
	}

	@Test
	public void aModuleDescriptorIsNotABaseModRatherThanACrash() throws Exception
	{
		ASMModParser parser = new ASMModParser(new ByteArrayInputStream(moduleInfo()));

		//the point: this used to throw, and the throw cost the entire jar
		assertFalse(parser.isBaseMod(Collections.<String>emptyList()));
	}

	@Test
	public void objectItselfIsNotABaseMod() throws Exception
	{
		ASMModParser parser = new ASMModParser(new ByteArrayInputStream(ordinaryClass("java/lang/Object", null)));

		assertFalse(parser.isBaseMod(Collections.<String>emptyList()));
	}

	/** And the check it exists for still works. */
	@Test
	public void stillRecognisesABaseMod() throws Exception
	{
		assertTrue(new ASMModParser(new ByteArrayInputStream(ordinaryClass("mod_Test", "BaseMod")))
				.isBaseMod(Collections.<String>emptyList()));
		assertTrue(new ASMModParser(new ByteArrayInputStream(ordinaryClass("mod_Test", "net/minecraft/src/BaseMod")))
				.isBaseMod(Collections.<String>emptyList()));
		assertTrue("a type remembered as a BaseMod subclass counts too",
				new ASMModParser(new ByteArrayInputStream(ordinaryClass("mod_Test", "some/mod/Parent")))
						.isBaseMod(Arrays.asList("some.mod.Parent")));
	}

	@Test
	public void anUnrelatedClassIsNotABaseMod() throws Exception
	{
		ASMModParser parser = new ASMModParser(new ByteArrayInputStream(ordinaryClass("some/mod/Thing", "java/lang/Object")));

		assertFalse(parser.isBaseMod(Collections.<String>emptyList()));
	}
}
