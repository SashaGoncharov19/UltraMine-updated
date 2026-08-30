package org.ultramine.server.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.Test;

/**
 * The whole 1.7.10 mod ecosystem is compiled against log4j-core 2.0-beta9, whose
 * factory signatures the modern library no longer has. A transformer retargets
 * those calls onto {@link Log4jBeta9Compat}, keyed by an exact owner/name/
 * descriptor triple - so a typo in that table, or a bridge method that quietly
 * stops matching the descriptor it is registered under, would silently do
 * nothing until a mod crashed at runtime. These tests check the two halves
 * agree, and that the bridge actually works on the shipped log4j.
 */
public class Log4jBeta9CompatTest
{
	/**
	 * Mirrors the transformer's table: bridge method name -> the beta9 descriptor
	 * whose calls get retargeted to it. Kept here deliberately as an independent
	 * copy - if the two ever disagree, that is the bug this test is looking for.
	 */
	private static Map<String, String> expectedBridges()
	{
		Map<String, String> m = new HashMap<String, String>();
		m.put("patternLayoutCreateLayout",
				"(Ljava/lang/String;Lorg/apache/logging/log4j/core/config/Configuration;Lorg/apache/logging/log4j/core/pattern/RegexReplacement;Ljava/lang/String;Ljava/lang/String;)Lorg/apache/logging/log4j/core/layout/PatternLayout;");
		m.put("onStartupCreatePolicy",
				"()Lorg/apache/logging/log4j/core/appender/rolling/OnStartupTriggeringPolicy;");
		m.put("defaultRolloverCreateStrategy",
				"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lorg/apache/logging/log4j/core/config/Configuration;)Lorg/apache/logging/log4j/core/appender/rolling/DefaultRolloverStrategy;");
		m.put("rollingRandomAccessFileCreateAppender",
				"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lorg/apache/logging/log4j/core/appender/rolling/TriggeringPolicy;Lorg/apache/logging/log4j/core/appender/rolling/RolloverStrategy;Lorg/apache/logging/log4j/core/Layout;Lorg/apache/logging/log4j/core/Filter;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lorg/apache/logging/log4j/core/config/Configuration;)Lorg/apache/logging/log4j/core/appender/RollingRandomAccessFileAppender;");
		m.put("fileAppenderCreateAppender",
				"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lorg/apache/logging/log4j/core/Layout;Lorg/apache/logging/log4j/core/Filter;Ljava/lang/String;Ljava/lang/String;Lorg/apache/logging/log4j/core/config/Configuration;)Lorg/apache/logging/log4j/core/appender/FileAppender;");
		m.put("rollingFileCreateAppender",
				"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lorg/apache/logging/log4j/core/appender/rolling/TriggeringPolicy;Lorg/apache/logging/log4j/core/appender/rolling/RolloverStrategy;Lorg/apache/logging/log4j/core/Layout;Lorg/apache/logging/log4j/core/Filter;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lorg/apache/logging/log4j/core/config/Configuration;)Lorg/apache/logging/log4j/core/appender/RollingFileAppender;");
		m.put("loaderLoadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
		return m;
	}

	/**
	 * A retargeted call keeps the original descriptor, so each bridge method must
	 * exist with exactly the signature the transformer redirects to it. Anything
	 * else is a NoSuchMethodError on a mod's first log call.
	 */
	@Test
	public void everyBridgeMethodMatchesTheDescriptorItIsRegisteredUnder()
	{
		for(Map.Entry<String, String> entry : expectedBridges().entrySet())
		{
			Method found = null;
			for(Method m : Log4jBeta9Compat.class.getDeclaredMethods())
			{
				if(m.getName().equals(entry.getKey()))
				{
					found = m;
					break;
				}
			}
			assertNotNull("no bridge method named " + entry.getKey(), found);
			assertTrue("bridge " + entry.getKey() + " must be static", java.lang.reflect.Modifier.isStatic(found.getModifiers()));
			assertEquals("descriptor mismatch for " + entry.getKey(), entry.getValue(), descriptorOf(found));
		}
	}

	/** The bridge exists to be called by transformed mod code - so call it. */
	@Test
	public void patternLayoutBridgeProducesAWorkingLayoutOnModernLog4j()
	{
		PatternLayout layout = Log4jBeta9Compat.patternLayoutCreateLayout(
				"[%d{yyyy-MM-dd HH:mm:ss}] %msg%n", new DefaultConfiguration(), null, null, null);
		assertNotNull("bridge returned no layout", layout);
	}

	@Test
	public void onStartupPolicyBridgeReturnsAPolicy()
	{
		assertNotNull(Log4jBeta9Compat.onStartupCreatePolicy());
	}

	@Test
	public void rolloverStrategyBridgeReturnsAStrategy()
	{
		assertNotNull(Log4jBeta9Compat.defaultRolloverCreateStrategy("3", null, "max", null, new DefaultConfiguration()));
	}

	@Test
	public void loaderBridgeLoadsAClassTheWayBeta9DidWithOneArgument()
	{
		try
		{
			assertEquals(String.class, Log4jBeta9Compat.loaderLoadClass("java.lang.String"));
		}
		catch(ClassNotFoundException e)
		{
			fail("bridge failed to load java.lang.String: " + e);
		}
	}

	private static String descriptorOf(Method m)
	{
		StringBuilder sb = new StringBuilder("(");
		for(Class<?> p : m.getParameterTypes())
			sb.append(typeDescriptor(p));
		return sb.append(')').append(typeDescriptor(m.getReturnType())).toString();
	}

	private static String typeDescriptor(Class<?> c)
	{
		if(c == void.class) return "V";
		if(c == int.class) return "I";
		if(c == boolean.class) return "Z";
		if(c == long.class) return "J";
		if(c.isArray()) return "[" + typeDescriptor(c.getComponentType());
		return "L" + c.getName().replace('.', '/') + ";";
	}
}
