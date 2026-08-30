package cpw.mods.fml.relauncher;

import java.security.Permission;

/**
 * A custom security manager stopping certain events from happening
 * unexpectedly.
 *
 * @author cpw
 *
 */
public class FMLSecurityManager extends SecurityManager {
	@Override
	public void checkPermission(Permission perm)
	{
		String permName = perm.getName() != null ? perm.getName() : "missing";
		if (permName.startsWith("exitVM"))
		{
			Class<?>[] classContexts = getClassContext();
			//stock FML checked length > 3 for [4] and > 4 for [5] - an off-by-one
			//that turns a shallow-stack exit (e.g. Runtime.halt from the launch
			//wrapper) into an ArrayIndexOutOfBoundsException
			String callingClass = classContexts.length > 4 ? classContexts[4].getName() : "none";
			String callingParent = classContexts.length > 5 ? classContexts[5].getName() : "none";
			// FML is allowed to call system exit and the Minecraft applet (from the quit button)
			if (!(callingClass.startsWith("cpw.mods.fml.") || ( "net.minecraft.client.Minecraft".equals(callingClass) && "net.minecraft.client.Minecraft".equals(callingParent)) || ("net.minecraft.server.dedicated.DedicatedServer".equals(callingClass) && "net.minecraft.server.MinecraftServer".equals(callingParent))))
			{
				throw new ExitTrappedException();
			}
		}
		else if ("setSecurityManager".equals(permName))
		{
			throw new SecurityException("Cannot replace the FML security manager");
		}
		return;
	}

	public static class ExitTrappedException extends SecurityException {
		private static final long serialVersionUID = 1L;
	}
}