package org.ultramine.server.util;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Holds an account for the length of its login handshake.
 *
 * <p>A login is invisible to the duplicate-session scan until the player is added
 * to the player list, and the player data load in between is asynchronous on a
 * dedicated server. A second connection for the same account arriving inside that
 * window scans an empty list, kicks nobody, and comes up alongside the first - two
 * live sessions on one profile, both writing the same player file on logout.
 *
 * <p>A claim closes that window. Claims are released when the login ends either
 * way; one that escapes both paths expires, so a dropped connection cannot lock an
 * account out permanently. Expiry is evaluated only when the same account tries
 * again, which is why nothing here has to be swept.
 */
public final class LoginClaims
{
	private final ConcurrentMap<UUID, Long> claims = new ConcurrentHashMap<UUID, Long>();
	private final long timeoutNanos;
	private final LongSupplier clock;

	public LoginClaims(long timeoutNanos)
	{
		//nanoTime, not wall clock: this measures a duration, and the wall clock can step
		this(timeoutNanos, System::nanoTime);
	}

	LoginClaims(long timeoutNanos, LongSupplier clock)
	{
		this.timeoutNanos = timeoutNanos;
		this.clock = clock;
	}

	/**
	 * @return true when the caller now holds the claim and may proceed with the
	 *         login; false when another handshake for this account is still live.
	 */
	public boolean claim(UUID account)
	{
		long now = clock.getAsLong();
		Long held = claims.putIfAbsent(account, Long.valueOf(now));

		if (held == null)
		{
			return true;
		}

		//An abandoned claim must not outlive its usefulness: take it over rather
		//than leaving the account unable to reconnect.
		return now - held.longValue() > timeoutNanos && claims.replace(account, held, Long.valueOf(now));
	}

	public void release(UUID account)
	{
		if (account != null)
		{
			claims.remove(account);
		}
	}

	public boolean isHeld(UUID account)
	{
		return claims.containsKey(account);
	}
}
