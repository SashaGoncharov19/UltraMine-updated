package org.ultramine.server.asm.transformers;

import java.util.HashMap;
import java.util.ListIterator;
import java.util.Map;

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
 * Retargets log4j-core 2.0-beta9 static factory calls (what all 1.7.10 mods
 * are compiled against) to {@link org.ultramine.server.compat.Log4jBeta9Compat},
 * which implements them on the 2.17.2 API. Without this, such calls throw
 * NoSuchMethodError inside mod static initializers (e.g. GregTech's GTMod).
 */
public class Log4jBeta9CompatTransformer implements IUMClassTransformer
{
	private static final Logger log = LogManager.getLogger();

	private static final String COMPAT_TYPE = "org/ultramine/server/compat/Log4jBeta9Compat";
	private static final String LOG4J_CORE_PREFIX = "org/apache/logging/log4j/core/";
	/** "owner|name|desc" of a removed beta9 factory -> bridge method name (descriptor unchanged) */
	private static final Map<String, String> BRIDGE = new HashMap<>();

	private static void add(String owner, String name, String desc, String bridgeName)
	{
		BRIDGE.put(owner + '|' + name + '|' + desc, bridgeName);
	}

	static
	{
		add("org/apache/logging/log4j/core/layout/PatternLayout", "createLayout",
				"(Ljava/lang/String;Lorg/apache/logging/log4j/core/config/Configuration;Lorg/apache/logging/log4j/core/pattern/RegexReplacement;Ljava/lang/String;Ljava/lang/String;)Lorg/apache/logging/log4j/core/layout/PatternLayout;",
				"patternLayoutCreateLayout");
		//keyed on the post-remap owner (Log4jPackageRemapTransformer runs first)
		add("org/apache/logging/log4j/core/util/Loader", "loadClass",
				"(Ljava/lang/String;)Ljava/lang/Class;",
				"loaderLoadClass");
		add("org/apache/logging/log4j/core/appender/rolling/OnStartupTriggeringPolicy", "createPolicy",
				"()Lorg/apache/logging/log4j/core/appender/rolling/OnStartupTriggeringPolicy;",
				"onStartupCreatePolicy");
		add("org/apache/logging/log4j/core/appender/rolling/DefaultRolloverStrategy", "createStrategy",
				"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lorg/apache/logging/log4j/core/config/Configuration;)Lorg/apache/logging/log4j/core/appender/rolling/DefaultRolloverStrategy;",
				"defaultRolloverCreateStrategy");
		add("org/apache/logging/log4j/core/appender/RollingRandomAccessFileAppender", "createAppender",
				"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lorg/apache/logging/log4j/core/appender/rolling/TriggeringPolicy;Lorg/apache/logging/log4j/core/appender/rolling/RolloverStrategy;Lorg/apache/logging/log4j/core/Layout;Lorg/apache/logging/log4j/core/Filter;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lorg/apache/logging/log4j/core/config/Configuration;)Lorg/apache/logging/log4j/core/appender/RollingRandomAccessFileAppender;",
				"rollingRandomAccessFileCreateAppender");
		add("org/apache/logging/log4j/core/appender/FileAppender", "createAppender",
				"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lorg/apache/logging/log4j/core/Layout;Lorg/apache/logging/log4j/core/Filter;Ljava/lang/String;Ljava/lang/String;Lorg/apache/logging/log4j/core/config/Configuration;)Lorg/apache/logging/log4j/core/appender/FileAppender;",
				"fileAppenderCreateAppender");
		add("org/apache/logging/log4j/core/appender/RollingFileAppender", "createAppender",
				"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lorg/apache/logging/log4j/core/appender/rolling/TriggeringPolicy;Lorg/apache/logging/log4j/core/appender/rolling/RolloverStrategy;Lorg/apache/logging/log4j/core/Layout;Lorg/apache/logging/log4j/core/Filter;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lorg/apache/logging/log4j/core/config/Configuration;)Lorg/apache/logging/log4j/core/appender/RollingFileAppender;",
				"rollingFileCreateAppender");
	}

	@Override
	public TransformResult transform(String name, String transformedName, ClassReader classReader, ClassNode classNode)
	{
		boolean modified = false;
		for(MethodNode m : classNode.methods)
		{
			for(ListIterator<AbstractInsnNode> it = m.instructions.iterator(); it.hasNext(); )
			{
				AbstractInsnNode insnNode = it.next();
				if(insnNode.getType() == AbstractInsnNode.METHOD_INSN && insnNode.getOpcode() == Opcodes.INVOKESTATIC)
				{
					MethodInsnNode mi = (MethodInsnNode)insnNode;
					if(mi.owner.startsWith(LOG4J_CORE_PREFIX))
					{
						String bridgeName = BRIDGE.get(mi.owner + '|' + mi.name + '|' + mi.desc);
						if(bridgeName != null)
						{
							log.debug("Method {}.{}{}: retargeting beta9 log4j call {}.{} to Log4jBeta9Compat.{}", name, m.name, m.desc, mi.owner, mi.name, bridgeName);
							mi.owner = COMPAT_TYPE;
							mi.name = bridgeName;
							mi.itf = false;
							modified = true;
						}
					}
				}
			}
		}
		return modified ? TransformResult.MODIFIED : TransformResult.NOT_MODIFIED;
	}
}
