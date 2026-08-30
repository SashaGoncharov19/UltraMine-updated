package org.ultramine.server.bootstrap;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Replacement for launchwrapper's {@link Launch} entry point on Java 9+, where
 * the application class loader is no longer a {@link java.net.URLClassLoader}
 * and Launch's constructor fails with a ClassCastException. Reimplements the
 * same tweaker flow, but builds the {@link LaunchClassLoader} from the
 * java.class.path property instead of casting the app class loader.
 *
 * Only used when ServerLaunchWrapper detects a non-URLClassLoader app loader;
 * on Java 8 the stock launchwrapper path runs unchanged.
 */
@SideOnly(Side.SERVER)
public class ModernJavaLaunch
{
	public static void launch(String[] args) throws Exception
	{
		List<URL> classpath = new ArrayList<URL>();
		for(String entry : System.getProperty("java.class.path").split(File.pathSeparator))
		{
			if(!entry.isEmpty())
				classpath.add(new File(entry).toURI().toURL());
		}

		LaunchClassLoader classLoader = new LaunchClassLoader(classpath.toArray(new URL[0]));
		Launch.classLoader = classLoader;
		Launch.blackboard = new HashMap<String, Object>();
		Thread.currentThread().setContextClassLoader(classLoader);

		//Argument handling identical to launchwrapper 1.11 Launch.launch()
		OptionParser parser = new OptionParser();
		parser.allowsUnrecognizedOptions();
		OptionSpec<String> profileOption = parser.accepts("version", "The version we launched with").withRequiredArg();
		OptionSpec<File> gameDirOption = parser.accepts("gameDir", "Alternative game directory").withRequiredArg().ofType(File.class);
		OptionSpec<File> assetsDirOption = parser.accepts("assetsDir", "Assets directory").withRequiredArg().ofType(File.class);
		OptionSpec<String> tweakClassOption = parser.accepts("tweakClass", "Tweak class(es) to load").withRequiredArg();
		OptionSpec<String> nonOption = parser.nonOptions();

		OptionSet options = parser.parse(args);
		File gameDir = options.valueOf(gameDirOption);
		File assetsDir = options.valueOf(assetsDirOption);
		String profileName = options.valueOf(profileOption);
		Launch.minecraftHome = gameDir;
		Launch.assetsDir = assetsDir;

		List<String> tweakClassNames = new ArrayList<String>(options.valuesOf(tweakClassOption));
		List<String> argumentList = new ArrayList<String>();
		Launch.blackboard.put("TweakClasses", tweakClassNames);
		Launch.blackboard.put("ArgumentList", argumentList);

		//The LIVE pending-tweaker list: FML reads it from the blackboard and
		//inserts instantiated tweakers (coremod plugin wrappers, the sorting
		//tweaker) and sorts it in place - see CoreModManager.injectCoreModTweaks
		//and sortTweakList
		List<ITweaker> tweakers = new ArrayList<ITweaker>();
		Launch.blackboard.put("Tweaks", tweakers);

		Set<String> visitedTweakerNames = new HashSet<String>();
		List<ITweaker> allTweakers = new ArrayList<ITweaker>();
		ITweaker primaryTweaker = null;

		//Tweakers may inject more tweaker names/instances while being processed
		//(FML's cascading tweaks) - keep draining until stable. Loop mechanics
		//mirror launchwrapper 1.11 Launch.launch() exactly, quirks included.
		do
		{
			for(Iterator<String> it = tweakClassNames.iterator(); it.hasNext();)
			{
				String tweakName = it.next();
				if(!visitedTweakerNames.add(tweakName))
				{
					it.remove();
					continue;
				}
				classLoader.addClassLoaderExclusion(tweakName.substring(0, tweakName.lastIndexOf('.')));
				ITweaker tweaker = (ITweaker) Class.forName(tweakName, true, classLoader).newInstance();
				tweakers.add(tweaker);
				it.remove();
				if(primaryTweaker == null)
					primaryTweaker = tweaker;
			}

			for(Iterator<ITweaker> it = tweakers.iterator(); it.hasNext();)
			{
				ITweaker tweaker = it.next();
				tweaker.acceptOptions(options.valuesOf(nonOption), gameDir, assetsDir, profileName);
				tweaker.injectIntoClassLoader(classLoader);
				allTweakers.add(tweaker);
				it.remove();
			}
		}
		while(!tweakClassNames.isEmpty());

		if(primaryTweaker == null)
			throw new IllegalArgumentException("No tweak class specified");

		for(ITweaker tweaker : allTweakers)
			argumentList.addAll(Arrays.asList(tweaker.getLaunchArguments()));

		Class<?> target = Class.forName(primaryTweaker.getLaunchTarget(), false, classLoader);
		target.getMethod("main", String[].class).invoke(null, (Object) argumentList.toArray(new String[0]));
	}
}
