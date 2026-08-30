package org.ultramine.server.bootstrap;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
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

		/*
		 * LaunchClassLoader hands its excluded packages - launchwrapper's own,
		 * log4j, and every tweaker's package - to a delegation parent, which it
		 * fixes at construction to whatever loaded it: the application class
		 * loader. FML needs to put coremod jars there, because that is where a
		 * cascading tweaker gets loaded from, and it does so by reflecting
		 * URLClassLoader.addURL onto it. On Java 9+ the application loader is not
		 * a URLClassLoader and cannot be extended at all, so every coremod that
		 * ships a cascading tweaker would silently fail to load.
		 *
		 * So an appendable loader is installed in that position instead. It
		 * starts empty and delegates to the application loader, which makes it
		 * transparent - every class still resolves exactly where it did - until
		 * FML adds a coremod jar to it, which is the whole point.
		 */
		URLClassLoader appendableParent = new URLClassLoader(new URL[0], ModernJavaLaunch.class.getClassLoader());
		if(installDelegationParent(classLoader, appendableParent))
			Launch.blackboard.put("ultramine.parentClassLoader", appendableParent);

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

	/**
	 * Point launchwrapper's excluded-package delegation at our own loader.
	 *
	 * <p>The field holding it is private with no setter, and launchwrapper ships
	 * as a binary dependency, so there is nothing to reach it but reflection. It
	 * is found by type rather than by name - it is the one non-static
	 * {@code ClassLoader} field on the class - so a renamed field does not break
	 * this, and a launchwrapper that genuinely does not work this way is
	 * recognised rather than guessed at.
	 *
	 * <p>Failure is loud but not fatal: without it the server still runs
	 * everything that does not need a cascading tweaker, which is exactly what it
	 * did before, and a message beats twenty reflection stack traces later.
	 */
	private static boolean installDelegationParent(LaunchClassLoader classLoader, ClassLoader parent)
	{
		Field found = null;
		for(Field field : LaunchClassLoader.class.getDeclaredFields())
		{
			if(field.getType() == ClassLoader.class && !Modifier.isStatic(field.getModifiers()))
			{
				if(found != null)
				{
					found = null;
					break;
				}
				found = field;
			}
		}

		if(found != null)
		{
			try
			{
				found.setAccessible(true);
				found.set(classLoader, parent);
				return true;
			}
			catch(Exception e)
			{
				System.err.println("Could not redirect launchwrapper's class loader delegation: " + e);
			}
		}

		System.err.println("This JVM has no extendable application class loader (Java 9+) and launchwrapper's "
				+ "delegation parent could not be replaced. Mods that ship a cascading tweaker will not load. "
				+ "Run the server on Java 8 if the pack needs them.");
		return false;
	}
}
