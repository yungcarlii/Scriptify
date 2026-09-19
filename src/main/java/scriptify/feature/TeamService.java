package scriptify.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

/**
 * Scene teams, built on vanilla scoreboard teams.
 *
 * <p>Using the vanilla system rather than a parallel one means nametag colours, collision and
 * friendly-fire rules are enforced by the game itself and understood by every other plugin and
 * datapack on the server.
 *
 * <p>New teams are created with the settings a filmed scene usually wants — friendly fire off and
 * collision off — because two cast members shoving each other out of frame ruins a take.
 */
public final class TeamService {
	private final MinecraftServer server;

	public TeamService(MinecraftServer server) {
		this.server = server;
	}

	private ServerScoreboard scoreboard() {
		return this.server.getScoreboard();
	}

	/**
	 * Creates a team.
	 *
	 * @return {@code false} when a team with that name already exists.
	 */
	public boolean create(String name) {
		String key = clean(name);
		if (scoreboard().getPlayerTeam(key) != null) {
			return false;
		}
		PlayerTeam team = scoreboard().addPlayerTeam(key);
		team.setAllowFriendlyFire(false);
		team.setCollisionRule(Team.CollisionRule.NEVER);
		team.setSeeFriendlyInvisibles(false);
		return true;
	}

	public boolean delete(String name) {
		PlayerTeam team = scoreboard().getPlayerTeam(clean(name));
		if (team == null) {
			return false;
		}
		scoreboard().removePlayerTeam(team);
		return true;
	}

	public boolean add(String name, ServerPlayer player) {
		PlayerTeam team = scoreboard().getPlayerTeam(clean(name));
		if (team == null) {
			return false;
		}
		// Teams key on the plain-text name, which is the real account name — nicknames do not affect it.
		scoreboard().addPlayerToTeam(player.getGameProfile().name(), team);
		return true;
	}

	public boolean remove(ServerPlayer player) {
		return scoreboard().removePlayerFromTeam(player.getGameProfile().name());
	}

	/** Sets the nametag colour, which also tints the name in chat and the tab list. */
	public boolean setColour(String name, ChatFormatting colour) {
		PlayerTeam team = scoreboard().getPlayerTeam(clean(name));
		if (team == null || !scriptify.ui.Colours.isColour(colour)) {
			return false;
		}
		// A team's colour is a ChatFormatting here. 26.2 splits it out into a TeamColor of its own and
		// takes an Optional, which is the one place this method differs between the two versions; the
		// isColour guard above is what keeps BOLD and RESET out of it either way.
		team.setColor(colour);
		return true;
	}

	/** A bracketed tag shown before every member's name. Accepts {@code &} colour codes. */
	public boolean setPrefix(String name, String prefix) {
		PlayerTeam team = scoreboard().getPlayerTeam(clean(name));
		if (team == null) {
			return false;
		}
		team.setPlayerPrefix(prefix == null || prefix.isEmpty()
			? Component.empty()
			: Component.literal(prefix.replace('&', ChatFormatting.PREFIX_CODE) + " "));
		return true;
	}

	public boolean setFriendlyFire(String name, boolean allowed) {
		PlayerTeam team = scoreboard().getPlayerTeam(clean(name));
		if (team == null) {
			return false;
		}
		team.setAllowFriendlyFire(allowed);
		return true;
	}

	public boolean setCollision(String name, boolean enabled) {
		PlayerTeam team = scoreboard().getPlayerTeam(clean(name));
		if (team == null) {
			return false;
		}
		team.setCollisionRule(enabled ? Team.CollisionRule.ALWAYS : Team.CollisionRule.NEVER);
		return true;
	}

	/** Hides nametags for the whole team — useful when a scene should not reveal who is who. */
	public boolean setNameTags(String name, boolean visible) {
		PlayerTeam team = scoreboard().getPlayerTeam(clean(name));
		if (team == null) {
			return false;
		}
		team.setNameTagVisibility(visible ? Team.Visibility.ALWAYS : Team.Visibility.NEVER);
		return true;
	}

	public boolean exists(String name) {
		return scoreboard().getPlayerTeam(clean(name)) != null;
	}

	public PlayerTeam get(String name) {
		return scoreboard().getPlayerTeam(clean(name));
	}

	public List<String> names() {
		List<String> names = new ArrayList<>();
		scoreboard().getPlayerTeams().forEach(team -> names.add(team.getName()));
		names.sort(String::compareTo);
		return names;
	}

	public List<String> members(String name) {
		PlayerTeam team = scoreboard().getPlayerTeam(clean(name));
		return team == null ? List.of() : List.copyOf(team.getPlayers());
	}

	/** The team a player currently belongs to, or {@code null}. */
	public String teamOf(ServerPlayer player) {
		PlayerTeam team = scoreboard().getPlayersTeam(player.getGameProfile().name());
		return team == null ? null : team.getName();
	}

	/**
	 * Team names go into scoreboard storage and command arguments, so they are normalised to a safe
	 * character set rather than rejected outright.
	 */
	private static String clean(String name) {
		String clean = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
		if (clean.isBlank()) {
			throw new IllegalArgumentException("Team name must contain letters, numbers, underscores, or hyphens.");
		}
		return clean.length() > 16 ? clean.substring(0, 16) : clean;
	}
}
