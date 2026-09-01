package org.ultramine.server.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.ultramine.server.UltramineServerConfig;
import org.ultramine.server.WorldsConfig;

/**
 * SnakeYAML 2.x removed the no-arg {@code Constructor()}/{@code Representer()} that
 * this provider used to build on 1.x, and changed the loader defaults (safe tag
 * handling, alias/nesting/code-point limits) along the way. These tests exercise the
 * actual shapes this core ships - a real config class round-tripped through disk, and
 * the anchor/merge-key style {@code defaultworlds.yml} uses - so a wrong migration
 * (mismatched Constructor/Representer wiring, lost PropertyUtils, broken merge-key
 * handling) fails here rather than only surfacing on a live server.yml/worlds.yml.
 */
public class YamlConfigProviderTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void roundTripsAConfigResemblingServerYmlThroughDisk() throws IOException, InterruptedException, ExecutionException
	{
		UltramineServerConfig original = new UltramineServerConfig();
		original.listen.minecraft.serverIP = "127.0.0.1";
		original.listen.minecraft.port = 25566;
		original.listen.rcon.enabled = true;
		original.listen.rcon.whitelist = java.util.Arrays.asList("1.2.3.4", "5.6.7.8");
		original.settings.player.maxPlayers = 42;
		original.settings.messages.motd = "A test server ☃";
		original.tools.autobroadcast.messages = new String[] {"first", "second"};
		original.vanilla.unresolved.put("some-unknown-vanilla-key", "keep me");

		File file = tmp.newFile("server.yml");
		YamlConfigProvider.saveConfig(file, original);
		awaitPendingWrites();

		UltramineServerConfig reloaded = YamlConfigProvider.readConfig(file, UltramineServerConfig.class);

		assertEquals("127.0.0.1", reloaded.listen.minecraft.serverIP);
		assertEquals(25566, reloaded.listen.minecraft.port);
		assertTrue(reloaded.listen.rcon.enabled);
		assertEquals(java.util.Arrays.asList("1.2.3.4", "5.6.7.8"), reloaded.listen.rcon.whitelist);
		assertEquals(42, reloaded.settings.player.maxPlayers);
		assertEquals("A test server ☃", reloaded.settings.messages.motd);
		assertEquals(2, reloaded.tools.autobroadcast.messages.length);
		assertEquals("second", reloaded.tools.autobroadcast.messages[1]);
		assertEquals("keep me", reloaded.vanilla.unresolved.get("some-unknown-vanilla-key"));

		File resaved = tmp.newFile("server2.yml");
		YamlConfigProvider.saveConfig(resaved, reloaded);
		awaitPendingWrites();
		UltramineServerConfig reloadedAgain = YamlConfigProvider.readConfig(resaved, UltramineServerConfig.class);
		assertEquals(reloaded.settings.messages.motd, reloadedAgain.settings.messages.motd);
		assertEquals(reloaded.listen.rcon.whitelist, reloadedAgain.listen.rcon.whitelist);
	}

	/** saveConfig writes on GlobalExecutors' single writer thread; block until it has caught up. */
	private static void awaitPendingWrites() throws InterruptedException, ExecutionException
	{
		GlobalExecutors.writingIO().submit(() -> null).get();
	}

	/**
	 * defaultworlds.yml defines each world with YAML anchors and {@code <<} merge
	 * keys against a shared "global" block, so per-world overrides layer on top of
	 * common defaults. SnakeYAML 2.x moved merge-key resolution behind a
	 * LoaderOptions flag with its own default and added an alias-count ceiling;
	 * this proves the shape our shipped defaults actually use still resolves.
	 */
	@Test
	public void parsesAnchorsAndMergeKeysLikeDefaultWorldsYml()
	{
		String yaml = "" +
			"global: &global\n" +
			"    dimension: 0\n" +
			"    generation: &global_gen\n" +
			"        providerID: 0\n" +
			"        seed: '12345'\n" +
			"    chunkLoading: &global_cl\n" +
			"        viewDistance: 15\n" +
			"        chunkCacheSize: 1024\n" +
			"\n" +
			"worlds:\n" +
			"    -   <<: *global\n" +
			"        dimension: 0\n" +
			"        name: 'world'\n" +
			"        generation:\n" +
			"            <<: *global_gen\n" +
			"            providerID: 0\n" +
			"        chunkLoading:\n" +
			"            <<: *global_cl\n" +
			"            chunkCacheSize: 4096\n" +
			"    -   <<: *global\n" +
			"        dimension: -1\n" +
			"        name: 'world_nether'\n" +
			"        generation:\n" +
			"            <<: *global_gen\n" +
			"            providerID: -1\n";

		WorldsConfig cfg = YamlConfigProvider.readConfig(yaml, WorldsConfig.class);

		assertEquals(2, cfg.worlds.size());

		WorldsConfig.WorldConfig overworld = cfg.worlds.get(0);
		assertEquals("world", overworld.name);
		assertEquals(0, overworld.generation.providerID);
		assertEquals("12345", overworld.generation.seed);
		assertEquals("merge-key override must win over the anchored default", 4096, overworld.chunkLoading.chunkCacheSize);
		assertEquals("value only present on the anchor must still come through the merge", 15, overworld.chunkLoading.viewDistance);

		WorldsConfig.WorldConfig nether = cfg.worlds.get(1);
		assertEquals("world_nether", nether.name);
		assertEquals(-1, nether.generation.providerID);
		assertEquals("seed must come from the merged anchor, unset by this entry", "12345", nether.generation.seed);
	}
}
