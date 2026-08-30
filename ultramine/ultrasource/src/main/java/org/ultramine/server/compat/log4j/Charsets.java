package org.ultramine.server.compat.log4j;

import java.nio.charset.Charset;

/**
 * Stand-in for log4j-core 2.0-beta9's {@code org.apache.logging.log4j.core.helpers.Charsets},
 * which has no 2.17.2 counterpart (the class was dropped, not moved).
 * {@link org.ultramine.server.asm.transformers.Log4jPackageRemapTransformer} redirects mod
 * references here.
 */
public final class Charsets
{
	public static final Charset UTF_8 = Charset.forName("UTF-8");

	private Charsets() {}

	public static Charset getSupportedCharset(String charsetName)
	{
		return getSupportedCharset(charsetName, Charset.defaultCharset());
	}

	public static Charset getSupportedCharset(String charsetName, Charset defaultCharset)
	{
		Charset charset = null;
		if(charsetName != null && Charset.isSupported(charsetName))
			charset = Charset.forName(charsetName);
		if(charset == null)
		{
			charset = defaultCharset;
			if(charsetName != null)
				org.apache.logging.log4j.status.StatusLogger.getLogger()
						.error("Charset " + charsetName + " is not supported, using " + charset.displayName());
		}
		return charset;
	}
}
