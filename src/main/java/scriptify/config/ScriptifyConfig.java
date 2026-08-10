package scriptify.config;

public final class ScriptifyConfig {
	public int schemaVersion = 2;
	public DeathControl deathControl = new DeathControl();

	public void validate() {
		this.deathControl = this.deathControl == null ? new DeathControl() : this.deathControl;
	}

	public static final class DeathControl {
		public boolean enabled = true;
		/** Broadcast the real death line before the fake disconnect. */
		public boolean showDeathMessage = true;
		public boolean fakeLeaveMessage = true;
		/** Let a held totem pop instead of staging the death. */
		public boolean respectTotems = true;
		/** Also drop the player from everyone's tab list, so the fake disconnect holds up to scrutiny. */
		public boolean hideFromPlayerList = true;
	}

}
