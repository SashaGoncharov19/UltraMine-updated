package org.ultramine.server.bootstrap;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

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
 * same tweaker flow, but builds the {@link LaunchClassLoader} from the class
 * path instead of casting the app class loader.
 *
 * <p>The class path is read the way launchwrapper reads it on Java 8 - where it
 * asks the application class loader for its URLs - which means the entries the
 * jar manifests chain in through {@code Class-Path} as well as those on the
 * command line. Everything else about the arrangement is left exactly as stock:
 * the LaunchClassLoader still delegates its excluded packages to the
 * application class loader, because that is the loader FML extends and the one
 * Mixin checks its tweaker against.
 *
 * <p>Only used when ServerLaunchWrapper detects a non-URLClassLoader app
 * loader; on Java 8 the stock launchwrapper path runs unchanged.
 */
@SideOnly(Side.SERVER)
public class ModernJavaLaunch
{
	public static void launch(String[] args) throws Exception
	{
		LaunchClassLoader classLoader = new LaunchClassLoader(classPathUrls());
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

	/**
	 * The application class path, the way the application loader sees it: the
	 * {@code java.class.path} entries plus whatever their manifests chain in
	 * through {@code Class-Path}, which is how the server jar reaches
	 * {@code libraries/}.
	 */
	private static URL[] classPathUrls()
	{
		List<URL> urls = new ArrayList<URL>();
		Set<String> visited = new HashSet<String>();
		for(String entry : System.getProperty("java.class.path", "").split(File.pathSeparator))
		{
			if(!entry.isEmpty())
				addClassPathEntry(new File(entry), urls, visited);
		}
		return urls.toArray(new URL[0]);
	}

	private static void addClassPathEntry(File file, List<URL> urls, Set<String> visited)
	{
		String key;
		try
		{
			key = file.getCanonicalPath();
		}
		catch(IOException e)
		{
			key = file.getAbsolutePath();
		}
		if(!visited.add(key))
			return;

		try
		{
			urls.add(file.toURI().toURL());
		}
		catch(MalformedURLException e)
		{
			return;
		}

		if(!file.isFile())
			return;

		//Class-Path entries are relative to the jar that declares them
		JarFile jar = null;
		try
		{
			jar = new JarFile(file);
			Manifest manifest = jar.getManifest();
			if(manifest == null)
				return;
			String classPath = manifest.getMainAttributes().getValue("Class-Path");
			if(classPath == null)
				return;
			File dir = file.getAbsoluteFile().getParentFile();
			for(String entry : classPath.split(" "))
			{
				if(!entry.isEmpty())
					addClassPathEntry(new File(dir, entry), urls, visited);
			}
		}
		catch(IOException e)
		{
			//not a readable jar; its own URL is already recorded
		}
		finally
		{
			if(jar != null)
			{
				try
				{
					jar.close();
				}
				catch(IOException ignored) {}
			}
		}
	}

}
