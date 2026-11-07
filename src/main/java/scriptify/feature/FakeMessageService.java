package scriptify.feature;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import scriptify.api.ScriptifyContext;
import scriptify.nick.PlayerRefresh;

/**
 * Broadcasts join, leave and death lines that are indistinguishable from the real thing.
 *
 * <p>Every message reuses vanilla's own translation key and styling rather than a hand-written string,
 * because on camera a subtly wrong colour or wording is exactly what gives a staged beat away.
 */
public final class FakeMessageService {
	private final ScriptifyContext context;

	public FakeMessageService(ScriptifyContext context) {
		this.context = context;
	}

	/** {@code <player> joined the game}, in vanilla yellow. */
	public void broadcastJoin(ServerPlayer player) {
		broadcast(Component.translatable("multiplayer.player.joined", player.getDisplayName())
			.withStyle(ChatFormatting.YELLOW));
	}

	/** {@code <player> left the game}, in vanilla yellow. */
	public void broadcastLeave(ServerPlayer player) {
		broadcast(Component.translatable("multiplayer.player.left", player.getDisplayName())
			.withStyle(ChatFormatting.YELLOW));
	}

	/**
	 * Broadcasts the exact death line the player would have produced had the damage actually killed
	 * them, so the message matches the cause on screen.
	 */
	public void broadcastDeath(ServerPlayer player, DamageSource source) {
		Component message = source == null
			? Component.translatable("death.attack.generic", player.getDisplayName())
			: source.getLocalizedDeathMessage(player);
		broadcast(message);
	}

	/** Generic death line for GUI-triggered fake deaths, where there is no real damage source. */
	public void broadcastDeath(ServerPlayer player) {
		broadcastDeath(player, null);
	}

	/**
	 * Stages a full disconnect: the leave message plus removal from everyone's tab list. The player
	 * stays connected and can keep filming.
	 */
	public void stageLeave(ServerPlayer player) {
		broadcastLeave(player);
		PlayerRefresh.hideFromPlayerList(player);
	}

	/** Undoes {@link #stageLeave}: puts them back in the list and announces a join. */
	public void stageJoin(ServerPlayer player) {
		PlayerRefresh.resendToOthers(player);
		broadcastJoin(player);
	}

	/** {@code <player> was slain by <killer>}, for a kill by somebody who need not be online, or exist. */
	public void broadcastSlain(ServerPlayer player, String killer) {
		broadcast(Component.translatable("death.attack.player", player.getDisplayName(), Component.literal(killer)));
	}

	/**
	 * {@code <player> has made the advancement [title]}, built by vanilla's own announcement code so the
	 * colours and hover match exactly, plus the real toast in the corner of that player's screen.
	 *
	 * <p>The advancement exists for one packet pair: it is added already completed, then removed again. The
	 * toast has what it needs by then, so it plays, but the advancement never appears in anybody's
	 * advancement screen and nothing about it is saved.
	 */
	public void broadcastAdvancement(ServerPlayer player, net.minecraft.advancements.AdvancementType type,
			Component title, Component description, net.minecraft.world.item.ItemStack icon) {
		var id = net.minecraft.resources.Identifier.fromNamespaceAndPath("scriptify",
			"fake/" + java.util.UUID.randomUUID());
		var requirements = net.minecraft.advancements.AdvancementRequirements.allOf(java.util.List.of("done"));
		var advancement = new net.minecraft.advancements.Advancement(java.util.Optional.empty(),
			java.util.Optional.of(new net.minecraft.advancements.DisplayInfo(icon, title, description,
				java.util.Optional.empty(), type, true, false, true)),
			net.minecraft.advancements.AdvancementRewards.EMPTY,
			java.util.Map.of("done", net.minecraft.advancements.CriteriaTriggers.IMPOSSIBLE.createCriterion(
				new net.minecraft.advancements.criterion.ImpossibleTrigger.TriggerInstance())),
			requirements, false);
		var holder = new net.minecraft.advancements.AdvancementHolder(id, advancement);
		var progress = new net.minecraft.advancements.AdvancementProgress();
		progress.update(requirements);
		progress.grantProgress("done");
		player.connection.send(new net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket(
			false, java.util.List.of(holder), java.util.Set.of(), java.util.Map.of(id, progress), true));
		player.connection.send(new net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket(
			false, java.util.List.of(), java.util.Set.of(id), java.util.Map.of(), false));
		broadcast(type.createAnnouncement(holder, player));
	}

	/**
	 * Disconnects the player with vanilla's kick screen; the server then prints the ordinary leave line on
	 * its own. Nothing is recorded anywhere, so they can rejoin straight away.
	 */
	public void stageKick(ServerPlayer player, String reason) {
		player.connection.disconnect(reason == null
			? Component.translatable("multiplayer.disconnect.kicked")
			: Component.literal(reason));
	}

	/** Same, with the ban screen. They are not on the ban list, so the next join just works. */
	public void stageBan(ServerPlayer player, String reason) {
		player.connection.disconnect(reason == null
			? Component.translatable("multiplayer.disconnect.banned")
			: Component.translatable("multiplayer.disconnect.banned.reason", reason));
	}

	private void broadcast(Component message) {
		this.context.server().getPlayerList().broadcastSystemMessage(message, false);
	}
}
