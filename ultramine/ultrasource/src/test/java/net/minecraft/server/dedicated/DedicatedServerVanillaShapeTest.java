package net.minecraft.server.dedicated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Field;

import org.junit.Test;

/**
 * Coremods reach into vanilla's fields, and Mixin's {@code @Shadow} matches on
 * name <em>and</em> descriptor. A field this core keeps under vanilla's name but
 * with its own type is therefore not found at all: the mixin fails to apply and
 * takes the target class load down with it, which is a hard boot failure rather
 * than a degraded feature.
 *
 * <p>That is what a {@code PropertyManager settings} replaced by an UltraMine
 * config object did to ServerUtilities' pause-when-empty mixin. The field name
 * is what reobfuscation maps ({@code settings} -> {@code field_71340_o} ->
 * notch), so the pairing of that name with vanilla's type is the shape a
 * coremod sees, and it is pinned here.
 */
public class DedicatedServerVanillaShapeTest
{
	/**
	 * Loaded without initialization: the shape is a property of the class file,
	 * and running a server class's static setup to read it would prove nothing.
	 */
	private static Class<?> dedicatedServer() throws ClassNotFoundException
	{
		return Class.forName("net.minecraft.server.dedicated.DedicatedServer", false,
				DedicatedServerVanillaShapeTest.class.getClassLoader());
	}

	@Test
	public void settingsFieldKeepsVanillaNameAndType() throws Exception
	{
		Field settings = dedicatedServer().getDeclaredField("settings");
		assertNotNull(settings);
		assertEquals("DedicatedServer.settings must stay vanilla's PropertyManager - coremods @Shadow it",
				PropertyManager.class, settings.getType());
	}

	/** The core's own configuration has to live somewhere else, then. */
	@Test
	public void ultramineConfigLivesInItsOwnField() throws Exception
	{
		Field umConfig = dedicatedServer().getDeclaredField("umConfig");
		assertEquals("org.ultramine.server.UltramineServerConfig", umConfig.getType().getName());
	}
}
