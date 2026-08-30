package org.ultramine.server.compat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.RollingRandomAccessFileAppender;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.OnStartupTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.RolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.TriggeringPolicy;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.pattern.RegexReplacement;

/**
 * Binary-compatibility bridge for log4j-core 2.0-beta9 static factories.
 *
 * The whole 1.7.10 mod ecosystem is compiled against log4j 2.0-beta9 (what
 * stock Forge 1614 shipped) and mods configure file loggers through its
 * {@code @PluginFactory} statics. Those signatures changed incompatibly on
 * the way to 2.17.2, so such calls die with NoSuchMethodError - an Error,
 * which typical mod code (e.g. GregTech's GTMod static initializer) does not
 * catch. {@link org.ultramine.server.asm.transformers.Log4jBeta9CompatTransformer}
 * retargets the removed-signature INVOKESTATICs into this class, which
 * implements them on top of the 2.17.2 API.
 *
 * Parameter lists replicate 2.0-beta9 exactly - do not "clean them up".
 */
@SuppressWarnings("deprecation")
public final class Log4jBeta9Compat
{
	private Log4jBeta9Compat() {}

	/** beta9: PatternLayout.createLayout(pattern, config, replace, charsetName, alwaysWriteExceptions) */
	public static PatternLayout patternLayoutCreateLayout(String pattern, Configuration config, RegexReplacement replace, String charsetName, String always)
	{
		Charset charset = StandardCharsets.UTF_8;
		if(charsetName != null && Charset.isSupported(charsetName))
			charset = Charset.forName(charsetName);
		boolean alwaysWriteExceptions = always == null || Boolean.parseBoolean(always);
		return PatternLayout.createLayout(pattern, null, config, replace, charset, alwaysWriteExceptions, false, null, null);
	}

	/** beta9: OnStartupTriggeringPolicy.createPolicy() */
	public static OnStartupTriggeringPolicy onStartupCreatePolicy()
	{
		return OnStartupTriggeringPolicy.createPolicy(1);
	}

	/** beta9: DefaultRolloverStrategy.createStrategy(max, min, fileIndex, compressionLevelStr, config) */
	public static DefaultRolloverStrategy defaultRolloverCreateStrategy(String max, String min, String fileIndex, String compressionLevelStr, Configuration config)
	{
		return DefaultRolloverStrategy.createStrategy(max, min, fileIndex, compressionLevelStr, null, true, config);
	}

	/** beta9: RollingRandomAccessFileAppender.createAppender(...) - 2.17 added bufferSizeStr after immediateFlush */
	public static RollingRandomAccessFileAppender rollingRandomAccessFileCreateAppender(String fileName, String filePattern, String append,
			String name, String immediateFlush, TriggeringPolicy policy, RolloverStrategy strategy,
			Layout<? extends java.io.Serializable> layout, Filter filter, String ignore, String advertise, String advertiseURI,
			Configuration config)
	{
		return RollingRandomAccessFileAppender.createAppender(fileName, filePattern, append, name, immediateFlush, null,
				policy, strategy, layout, filter, ignore, advertise, advertiseURI, config);
	}

	/** beta9: FileAppender.createAppender(...) - 2.17 added bufferSizeStr after bufferedIo */
	public static FileAppender fileAppenderCreateAppender(String fileName, String append, String locking, String name,
			String immediateFlush, String ignore, String bufferedIo, Layout<? extends java.io.Serializable> layout,
			Filter filter, String advertise, String advertiseUri, Configuration config)
	{
		return FileAppender.createAppender(fileName, append, locking, name, immediateFlush, ignore, bufferedIo, null,
				layout, filter, advertise, advertiseUri, config);
	}

	/** beta9: RollingFileAppender.createAppender(...) - 2.17 added bufferSizeStr after bufferedIO */
	public static RollingFileAppender rollingFileCreateAppender(String fileName, String filePattern, String append,
			String name, String bufferedIO, String immediateFlush, TriggeringPolicy policy, RolloverStrategy strategy,
			Layout<? extends java.io.Serializable> layout, Filter filter, String ignore, String advertise, String advertiseUri,
			Configuration config)
	{
		return RollingFileAppender.createAppender(fileName, filePattern, append, name, bufferedIO, null, immediateFlush,
				policy, strategy, layout, filter, ignore, advertise, advertiseUri, config);
	}
}
