package org.ultramine.server.asm.transformers;

import java.util.ListIterator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.ultramine.server.asm.UMTBatchTransformer.IUMClassTransformer;
import org.ultramine.server.asm.UMTBatchTransformer.TransformResult;

/**
 * Repairs interface method references that JDK 8 emitted as
 * {@code CONSTANT_Methodref} where the JVM requires
 * {@code CONSTANT_InterfaceMethodref}.
 *
 * <p>A lambda written inside an interface compiles to a {@code private static}
 * synthetic method on that interface, and the {@code invokedynamic} bootstrap
 * arguments carry a method handle pointing back at it. Toolchains of the
 * 1.7.10 era tagged that handle as a plain {@code Methodref}. Java 8's verifier
 * accepted it; every JVM from 9 on rejects the class outright:
 *
 * <pre>
 * IncompatibleClassChangeError: Inconsistent constant pool data in classfile
 *   for class net/glease/tc4tweak/api/InfusionExtAPI$RecipeNBTBehavior.
 *   Method 'lambda$mergeNBT$1(...)' at index 40 is CONSTANT_MethodRef and
 *   should be CONSTANT_InterfaceMethodRef
 * </pre>
 *
 * <p>That is not one mod's bug. Any mod of that vintage with a lambda inside an
 * interface ships the same malformation, and a large pack carries hundreds of
 * such mods — so this is repaired for every class rather than special-cased.
 *
 * <p><b>Only self-references are repaired</b>, which is what makes this cheap
 * and safe. When the owner is the class being loaded, its own access flags say
 * whether it is an interface, so nothing has to be resolved through the class
 * loader — no I/O, and no risk of forcing a class to load early. That also
 * happens to cover the whole known failure mode: the defect is produced by the
 * compiler referring to a method it is itself declaring.
 *
 * <p>The rewrite is provably a correction rather than a guess. JVMS 4.4.2
 * requires the {@code class_index} of a {@code CONSTANT_Methodref} to name a
 * class, never an interface, so inside an interface a self-referencing
 * {@code Methodref} is malformed in every case — there is no valid program in
 * which flipping it changes behaviour.
 *
 * <p>ultramine: divergence from vanilla FML, which has no such pass.
 */
public class InterfaceMethodrefRepairTransformer implements IUMClassTransformer
{
	private static final Logger log = LogManager.getLogger();

	@Override
	public TransformResult transform(String name, String transformedName, ClassReader classReader, ClassNode classNode)
	{
		//Every non-interface class costs exactly this bitmask test.
		if((classNode.access & Opcodes.ACC_INTERFACE) == 0)
			return TransformResult.NOT_MODIFIED;

		int repaired = 0;
		for(MethodNode m : classNode.methods)
		{
			if(m.instructions == null)
				continue;
			for(ListIterator<AbstractInsnNode> it = m.instructions.iterator(); it.hasNext(); )
			{
				AbstractInsnNode insn = it.next();
				if(insn instanceof InvokeDynamicInsnNode)
				{
					InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode)insn;
					//The lambda body handle lives in the bootstrap arguments; the
					//bootstrap method itself is LambdaMetafactory and never matches.
					if(indy.bsmArgs != null)
					{
						for(int i = 0; i < indy.bsmArgs.length; i++)
						{
							Object arg = indy.bsmArgs[i];
							if(arg instanceof Handle && needsRepair((Handle)arg, classNode))
							{
								Handle h = (Handle)arg;
								indy.bsmArgs[i] = new Handle(h.getTag(), h.getOwner(), h.getName(), h.getDesc(), true);
								repaired++;
							}
						}
					}
					if(indy.bsm != null && needsRepair(indy.bsm, classNode))
					{
						Handle h = indy.bsm;
						indy.bsm = new Handle(h.getTag(), h.getOwner(), h.getName(), h.getDesc(), true);
						repaired++;
					}
				}
				else if(insn instanceof MethodInsnNode)
				{
					MethodInsnNode min = (MethodInsnNode)insn;
					if(!min.itf && classNode.name.equals(min.owner))
					{
						min.itf = true;
						repaired++;
					}
				}
			}
		}

		if(repaired == 0)
			return TransformResult.NOT_MODIFIED;

		//Per class rather than per reference, and at debug: a pack the size of
		//GT New Horizons trips this in hundreds of classes, and it is a repair
		//rather than a problem the operator can act on.
		log.debug("Repaired {} interface method reference(s) in {}", repaired, transformedName);
		//Only constant pool tags change; the stack shape is untouched, so frames
		//must not be recomputed.
		return TransformResult.MODIFIED;
	}

	private static boolean needsRepair(Handle handle, ClassNode classNode)
	{
		return !handle.isInterface() && classNode.name.equals(handle.getOwner());
	}
}
