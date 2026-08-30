package org.ultramine.server.compat.log4j;

import java.util.UUID;

/**
 * Stand-in for log4j-core 2.0-beta9's {@code org.apache.logging.log4j.core.helpers.UUIDUtil},
 * which has no 2.17.2 counterpart under that name.
 * {@link org.ultramine.server.asm.transformers.Log4jPackageRemapTransformer} redirects mod
 * references here.
 */
public final class UUIDUtil
{
	public static final String UUID_SEQUENCE = "org.apache.logging.log4j.uuidSequence";

	private UUIDUtil() {}

	public static UUID getTimeBasedUUID()
	{
		return org.apache.logging.log4j.core.util.UuidUtil.getTimeBasedUuid();
	}
}
