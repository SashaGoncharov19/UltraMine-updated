package org.ultramine.server.asm.transformers;

import java.util.ListIterator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.ultramine.server.asm.UMTBatchTransformer.IUMClassTransformer;
import org.ultramine.server.asm.UMTBatchTransformer.TransformResult;

/**
 * Routes {@code Field.set} through {@link org.ultramine.server.compat.ReflectionCompat},
 * which falls back to an {@code Unsafe} write when the target is {@code static final}.
 *
 * <p>See that class for why: {@code Field.modifiers} has been filtered out of
 * reflection since Java 12, so the usual "strip FINAL then assign" idiom stops
 * working and mods of this era fail to populate their own tables.
 *
 * <p>Only the {@code set(Object, Object)} overload is redirected — the one the
 * observed failures go through. The primitive setters are left alone rather than
 * bridged on speculation.
 *
 * <p>ultramine: divergence from vanilla FML, which has no such pass.
 */
public class FieldSetCompatTransformer implements IUMClassTransformer
{
	private static final Logger log = LogManager.getLogger();

	private static final String FIELD_TYPE = "java/lang/reflect/Field";
	private static final String SET_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";
	private static final String COMPAT_TYPE = "org/ultramine/server/compat/ReflectionCompat";
	private static final String COMPAT_DESC = "(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V";
	/** The bridge itself calls Field.set; rewriting that would make it call itself. */
	private static final String COMPAT_CLASS_NAME = "org.ultramine.server.compat.ReflectionCompat";

	@Override
	public TransformResult transform(String name, String transformedName, ClassReader classReader, ClassNode classNode)
	{
		if(COMPAT_CLASS_NAME.equals(transformedName) || COMPAT_CLASS_NAME.equals(name))
			return TransformResult.NOT_MODIFIED;

		boolean modified = false;
		for(MethodNode m : classNode.methods)
		{
			for(ListIterator<AbstractInsnNode> it = m.instructions.iterator(); it.hasNext(); )
			{
				AbstractInsnNode insnNode = it.next();
				if(insnNode.getType() != AbstractInsnNode.METHOD_INSN || insnNode.getOpcode() != Opcodes.INVOKEVIRTUAL)
					continue;
				MethodInsnNode mi = (MethodInsnNode)insnNode;
				if(!FIELD_TYPE.equals(mi.owner) || !"set".equals(mi.name) || !SET_DESC.equals(mi.desc))
					continue;

				log.debug("Method {}.{}{}: routing Field.set through ReflectionCompat", name, m.name, m.desc);
				//The receiver becomes the first argument, so the operand stack is
				//identical before and after and the frames stay valid.
				mi.setOpcode(Opcodes.INVOKESTATIC);
				mi.owner = COMPAT_TYPE;
				mi.name = "set";
				mi.desc = COMPAT_DESC;
				mi.itf = false;
				modified = true;
			}
		}
		return modified ? TransformResult.MODIFIED : TransformResult.NOT_MODIFIED;
	}
}
