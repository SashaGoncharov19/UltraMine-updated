package org.ultramine.server.util;

import org.apache.commons.io.Charsets;
import org.apache.commons.io.IOUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.BeanAccess;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.introspector.PropertyUtils;
import org.yaml.snakeyaml.representer.Representer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public class YamlConfigProvider
{
	private static final Yaml YAML;
	
	static
	{
		PropertyUtils prorutils = new PropertyUtils()
		{
			protected Set<Property> createPropertySet(Class<? extends Object> type, BeanAccess bAccess)
			{
				Set<Property> properties = new LinkedHashSet<Property>();
				Collection<Property> props = getPropertiesMap(type, bAccess).values();
				for(Property property : props)
					if(property.isReadable() && (false || property.isWritable()))
						properties.add(property);
				return properties;
			}
		};
		prorutils.setSkipMissingProperties(true);

		// ultramine: SnakeYAML 2.x dropped the no-arg Constructor()/Representer() and
		// requires LoaderOptions/DumperOptions instead. Every file this provider reads
		// (server.yml, worlds.yml, itemblocker.yml, warps.yml) is written by an operator
		// or by this server itself under settings/storage - never parsed from a network
		// or player-supplied source - so the stock LoaderOptions() is kept rather than
		// loosened or tightened:
		//  - its TagInspector (UnTrustedTagInspector) refuses arbitrary Java-object tags,
		//    which is the CVE-2022-1471 hardening 2.x ships by default; this provider only
		//    ever needs JavaBean-style construction of the requested root class, so nothing
		//    here depended on the looser 1.x behaviour.
		//  - maxAliasesForCollections (50), nestingDepthLimit (50) and codePointLimit
		//    (3 MiB) are left at their defaults. The largest config this core ships,
		//    defaultworlds.yml, uses 3 anchors referenced a handful of times each and is a
		//    few KB, so none of our shipped or generated configs are known to approach
		//    these limits; raise them explicitly here if a real config ever does.
		LoaderOptions loaderOptions = new LoaderOptions();
		Constructor constructor = new Constructor(loaderOptions);
		constructor.setPropertyUtils(prorutils);

		DumperOptions opts = new DumperOptions();
		opts.setIndent(4);

		YAML = new Yaml(constructor, new Representer(opts), opts);
	}

	public static <T> T getOrCreateConfig(File configFile, Class<T> clazz)
	{
		T ret;

		if(!configFile.exists())
		{
			try
			{
				ret = clazz.newInstance();
			}
			catch (Exception e)
			{
				throw new RuntimeException("impossible exception", e);
			}

			saveConfig(configFile, ret);
		}
		else
		{
			return readConfig(configFile, clazz);
		}

		return ret;
	}
	
	public static <T> T readConfig(File configFile, Class<T> clazz)
	{
		Reader reader = null;
		try
		{
			reader = new InputStreamReader(new FileInputStream(configFile), Charsets.UTF_8);
			return YAML.loadAs(reader, clazz);
		}
		catch (IOException e)
		{
			throw new RuntimeException("Failed to read config: " + configFile.getPath(), e);
		}
		finally
		{
			IOUtils.closeQuietly(reader);
		}
	}
	
	public static <T> T readConfig(String config, Class<T> clazz)
	{
		return YAML.loadAs(config, clazz);
	}
	
	public static void saveConfig(File configFile, Object o)
	{
		AsyncIOUtils.writeString(configFile, YAML.dumpAsMap(o));
	}
}
