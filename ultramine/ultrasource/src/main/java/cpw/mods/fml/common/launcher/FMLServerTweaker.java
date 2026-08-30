package cpw.mods.fml.common.launcher;

import cpw.mods.fml.relauncher.FMLLaunchHandler;
import net.minecraft.launchwrapper.LaunchClassLoader;

public class FMLServerTweaker extends FMLTweaker {
	@Override
	public String getLaunchTarget()
	{
		return "net.minecraft.server.MinecraftServer";
	}

	@Override
	public void injectIntoClassLoader(LaunchClassLoader classLoader)
	{
		// launchwrapper iterates its transformer list with a plain ArrayList
		// iterator, but coremods (e.g. CoreTweaks' transformer wrappers) may
		// register or replace transformers from inside a transform call, which
		// crashes class loading with a ConcurrentModificationException. Swap
		// the list for a CopyOnWriteArrayList before anything registers.
		try
		{
			java.lang.reflect.Field f = LaunchClassLoader.class.getDeclaredField("transformers");
			f.setAccessible(true);
			f.set(classLoader, new java.util.concurrent.CopyOnWriteArrayList<Object>((java.util.List<?>)f.get(classLoader)));
		}
		catch (Exception e)
		{
			System.out.println("Could not harden the launchwrapper transformer list: " + e);
		}

		// The mojang packages are excluded so the log4j2 queue is correctly visible from
		// the obfuscated and deobfuscated parts of the code. Without, the UI won't show anything
		classLoader.addClassLoaderExclusion("com.mojang.");
		classLoader.addTransformerExclusion("cpw.mods.fml.repackage.");
		classLoader.addTransformerExclusion("cpw.mods.fml.relauncher.");
		classLoader.addTransformerExclusion("cpw.mods.fml.common.asm.transformers.");
		classLoader.addClassLoaderExclusion("LZMA.");
		FMLLaunchHandler.configureForServerLaunch(classLoader, this);
		FMLLaunchHandler.appendCoreMods();
	}
}