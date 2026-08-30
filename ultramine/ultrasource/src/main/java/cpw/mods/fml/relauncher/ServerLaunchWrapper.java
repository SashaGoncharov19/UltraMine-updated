package cpw.mods.fml.relauncher;

import org.ultramine.server.bootstrap.UMBootstrap;

import java.lang.reflect.Method;

public class ServerLaunchWrapper {

	/**
	 * @param args
	 */
	public static void main(String[] args)
	{
		new ServerLaunchWrapper().run(args);
	}

	private ServerLaunchWrapper()
	{

	}

	private void run(String[] args)
	{
		UMBootstrap.handleFirstLine(args);
		
		Class<?> launchwrapper = null;
		try
		{
			launchwrapper = Class.forName("net.minecraft.launchwrapper.Launch",true,getClass().getClassLoader());
			Class.forName("org.objectweb.asm.Type",true,getClass().getClassLoader());
		}
		catch (Exception e)
		{
			System.err.printf("We appear to be missing one or more essential library files.\n" +
					"You will need to add them to your server before FML and Forge will run successfully.");
			e.printStackTrace(System.err);
			System.exit(1);
		}

		try
		{
			String[] allArgs = new String[args.length + 2];
			allArgs[0] = "--tweakClass";
			allArgs[1] = "cpw.mods.fml.common.launcher.FMLServerTweaker";
			System.arraycopy(args, 0, allArgs, 2, args.length);
			if(getClass().getClassLoader() instanceof java.net.URLClassLoader)
			{
				//Java 8: the stock launchwrapper path, unchanged
				Method main = launchwrapper.getMethod("main", String[].class);
				main.invoke(null,(Object)allArgs);
			}
			else
			{
				//Java 9+: the app class loader is no longer a URLClassLoader, and
				//launchwrapper, FML and Mixin all depend on it being one - see
				//ModernJavaLaunch.createBootClassLoader. So rebuild the class path
				//in a URLClassLoader and run the launch from inside it.
				Class<?> bootstrap = Class.forName("org.ultramine.server.bootstrap.ModernJavaLaunch", true, getClass().getClassLoader());
				ClassLoader bootLoader = (ClassLoader)bootstrap.getMethod("createBootClassLoader").invoke(null);
				Thread.currentThread().setContextClassLoader(bootLoader);
				Class<?> modernLaunch = Class.forName("org.ultramine.server.bootstrap.ModernJavaLaunch", true, bootLoader);
				modernLaunch.getMethod("launch", String[].class).invoke(null, (Object)allArgs);
			}
		}
		catch (Throwable e)
		{
			//unwrap reflective invocation so the real cause leads the trace
			while (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null)
			{
				e = e.getCause();
			}
			//System.err may be redirected into the async game logger by this
			//point and halt() would kill the JVM before it drains - write the
			//trace straight to the process stderr fd, then flush the logger.
			java.io.PrintStream raw = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true);
			raw.println("A problem occurred running the Server launcher.");
			e.printStackTrace(raw);
			raw.flush();
			try
			{
				org.apache.logging.log4j.LogManager.shutdown();
			}
			catch (Throwable ignored) {}
			//halt instead of exit: a failed launch can leave threads/shutdown
			//hooks that keep or wedge the dying JVM
			Runtime.getRuntime().halt(1);
		}
	}

}