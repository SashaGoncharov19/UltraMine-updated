package org.ultramine.server.asm.transformers;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * log4j-core 2.0-beta9 kept its helper classes in
 * {@code org.apache.logging.log4j.core.helpers}; 2.x moved them to
 * {@code org.apache.logging.log4j.core.util} (and Strings to
 * {@code org.apache.logging.log4j.util}). Mods compiled against the Forge-era
 * beta9 - CoFHCore among them - therefore die with NoClassDefFoundError on the
 * modern log4j this core ships.
 *
 * Rewriting the type references is enough for the classes that only moved; the
 * two that were dropped outright are pointed at our own stand-ins.
 */
public class Log4jPackageRemapTransformer implements IClassTransformer
{
	private static final String OLD_PKG = "org/apache/logging/log4j/core/helpers/";
	private static final byte[] OLD_PKG_BYTES = OLD_PKG.getBytes(java.nio.charset.Charset.forName("UTF-8"));
	private static final String NEW_PKG = "org/apache/logging/log4j/core/util/";
	/** classes that did not simply move: old simple name -> full new internal name */
	private static final Map<String, String> SPECIAL = new HashMap<String, String>();

	static
	{
		SPECIAL.put("Strings", "org/apache/logging/log4j/util/Strings");
		SPECIAL.put("Charsets", "org/ultramine/server/compat/log4j/Charsets");
		SPECIAL.put("UUIDUtil", "org/ultramine/server/compat/log4j/UUIDUtil");
	}

	private static final Remapper REMAPPER = new Remapper()
	{
		@Override
		public String map(String internalName)
		{
			if(!internalName.startsWith(OLD_PKG))
				return internalName;
			String rest = internalName.substring(OLD_PKG.length());
			int inner = rest.indexOf('$');
			String simple = inner < 0 ? rest : rest.substring(0, inner);
			String special = SPECIAL.get(simple);
			if(special != null)
				return inner < 0 ? special : special + rest.substring(inner);
			return NEW_PKG + rest;
		}
	};

	@Override
	public byte[] transform(String name, String transformedName, byte[] basicClass)
	{
		if(basicClass == null || !containsOldPackage(basicClass))
			return basicClass;

		ClassReader reader = new ClassReader(basicClass);
		ClassWriter writer = new ClassWriter(0);
		reader.accept(new ClassRemapper(writer, REMAPPER), 0);
		return writer.toByteArray();
	}

	/**
	 * Constant-pool scan: cheap enough to run on every class, and it keeps the
	 * remapper (and its full parse/rewrite) off the 99.9% that never mention
	 * the old package.
	 */
	private static boolean containsOldPackage(byte[] bytes)
	{
		byte first = OLD_PKG_BYTES[0];
		int limit = bytes.length - OLD_PKG_BYTES.length;
		outer:
		for(int i = 0; i <= limit; i++)
		{
			if(bytes[i] != first)
				continue;
			for(int j = 1; j < OLD_PKG_BYTES.length; j++)
				if(bytes[i + j] != OLD_PKG_BYTES[j])
					continue outer;
			return true;
		}
		return false;
	}
}
