/*
 * Forge Mod Loader
 * Copyright (c) 2012-2013 cpw.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Contributors:
 *     cpw - implementation
 */

package cpw.mods.fml.common;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.Level;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;

import com.google.common.collect.ImmutableList;

import cpw.mods.fml.common.asm.transformers.ModAPITransformer;
import cpw.mods.fml.common.discovery.ASMDataTable;

/**
 * A simple delegating class loader used to load mods into the system
 *
 *
 * @author cpw
 *
 */
public class ModClassLoader extends URLClassLoader
{
	private static final List<String> STANDARD_LIBRARIES = ImmutableList.of("jinput.jar", "lwjgl.jar", "lwjgl_util.jar", "rt.jar");
	private LaunchClassLoader mainClassLoader;

	public ModClassLoader(ClassLoader parent) {
		super(new URL[0], null);
		/*
		 * ultramine: FML only works when launchwrapper's LaunchClassLoader is the
		 * one that loaded it. When something initializes FML earlier - typically a
		 * tweaker doing real work in acceptOptions, before launchwrapper hands
		 * control over - this cast is the first place it surfaces, as a bare
		 * ClassCastException naming FML's own classes. That reads like a fault in
		 * this core, and it is not: name the loader that turned up and point at the
		 * tweaker, so the log blames what actually went wrong.
		 */
		if (!(parent instanceof LaunchClassLoader))
		{
			throw new IllegalStateException("FML was initialized by " + parent.getClass().getName()
					+ " rather than launchwrapper's LaunchClassLoader, so it cannot load mods. Something touched FML "
					+ "before launchwrapper handed control over - almost always a tweaker that does its work in "
					+ "acceptOptions instead of injectIntoClassLoader. The last \"Calling tweak class\" line above "
					+ "names it; that mod is the one to remove.");
		}
		this.mainClassLoader = (LaunchClassLoader)parent;
	}

	public void addFile(File modFile) throws MalformedURLException
	{
		URL url = modFile.toURI().toURL();
		mainClassLoader.addURL(url);
	}

	@Override
	public Class<?> loadClass(String name) throws ClassNotFoundException
	{
		return mainClassLoader.loadClass(name);
	}

	public File[] getParentSources() {
		List<URL> urls=mainClassLoader.getSources();
		File[] sources=new File[urls.size()];
		try
		{
			for (int i = 0; i<urls.size(); i++)
			{
				sources[i]=new File(urls.get(i).toURI());
			}
			return sources;
		}
		catch (URISyntaxException e)
		{
			FMLLog.log(Level.ERROR, e, "Unable to process our input to locate the minecraft code");
			throw new LoaderException(e);
		}
	}

	public List<String> getDefaultLibraries()
	{
		return STANDARD_LIBRARIES;
	}

	public void clearNegativeCacheFor(Set<String> classList)
	{
		mainClassLoader.clearNegativeEntries(classList);
	}

	public ModAPITransformer addModAPITransformer(ASMDataTable dataTable)
	{
		mainClassLoader.registerTransformer("cpw.mods.fml.common.asm.transformers.ModAPITransformer");
		List<IClassTransformer> transformers = mainClassLoader.getTransformers();
		ModAPITransformer modAPI = (ModAPITransformer) transformers.get(transformers.size()-1);
		modAPI.initTable(dataTable);
		return modAPI;
	}
}