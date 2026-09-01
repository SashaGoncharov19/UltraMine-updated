package org.ultramine.server.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * The window this closes is narrow and invisible: between a login being accepted
 * and the player reaching the player list, the account is in flight and the
 * duplicate-session scan cannot see it. A second connection arriving there used to
 * come up alongside the first, and both wrote the same player file on logout.
 */
public class LoginClaimsTest
{
	private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(60);

	private final AtomicLong clock = new AtomicLong();
	private final LoginClaims claims = new LoginClaims(TIMEOUT, clock::get);
	private final UUID account = UUID.randomUUID();

	@Test
	public void theFirstLoginWins()
	{
		assertTrue(claims.claim(account));
	}

	@Test
	public void aSecondLoginForTheSameAccountIsRefusedWhileTheFirstIsInFlight()
	{
		claims.claim(account);
		assertFalse("this is the duplication window - it must stay shut", claims.claim(account));
	}

	@Test
	public void anotherAccountIsUnaffected()
	{
		claims.claim(account);
		assertTrue(claims.claim(UUID.randomUUID()));
	}

	@Test
	public void releasingLetsTheAccountLogInAgain()
	{
		claims.claim(account);
		claims.release(account);
		assertTrue(claims.claim(account));
	}

	/** A login that dies without releasing must not lock the account out for good. */
	@Test
	public void anAbandonedClaimExpires()
	{
		claims.claim(account);
		clock.set(TIMEOUT + 1);
		assertTrue(claims.claim(account));
	}

	/** ...but not one instant before it is due. */
	@Test
	public void aClaimHoldsRightUpToTheTimeout()
	{
		claims.claim(account);
		clock.set(TIMEOUT);
		assertFalse(claims.claim(account));
	}

	@Test
	public void takingOverAnExpiredClaimStartsTheClockAgain()
	{
		claims.claim(account);
		clock.set(TIMEOUT + 1);
		claims.claim(account);
		assertFalse("the replacement claim is fresh, not inherited", claims.claim(account));
	}

	@Test
	public void releaseToleratesAnAccountThatHoldsNothing()
	{
		claims.release(account);
		assertFalse(claims.isHeld(account));
	}
}
