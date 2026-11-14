/**
 * Danger checks for long waits (a script's `mc.sleep`, a tool blocking on a job): polled every
 * {@link WAKE_POLL_MS} while waiting, they report the first sign of danger so the wait ends early
 * and the model can react instead of sleeping through an attack. Used only when the mod's
 * `reflexes` are on.
 */

/** What a check reads: the fields of `player.getState` it needs. */
export interface DangerState {
  health?: number;
  maxHealth?: number;
  air?: number;
  maxAir?: number;
}

export type DangerReason = "dead" | "damaged" | "low_air";

export interface Danger {
  reason: DangerReason;
  health: number;
  /** Health at the previous poll, for "damaged". */
  was?: number;
}

/** How often a watched wait polls. */
export const WAKE_POLL_MS = 500;

/**
 * A stateful check over successive states: death, any health loss since the last poll (unless
 * `ignoreDamage`, e.g. during a fight, where damage is expected), or air below a third.
 */
export function dangerCheck(
  read: () => Promise<DangerState | undefined>,
  opts: { ignoreDamage?: boolean } = {},
): () => Promise<Danger | undefined> {
  let last: number | undefined;
  return async () => {
    const s = await read();
    if (!s || typeof s.health !== "number") return undefined;
    if (s.health <= 0) return { reason: "dead", health: s.health };
    const prev = last;
    last = s.health;
    if (!opts.ignoreDamage && prev !== undefined && s.health < prev) return { reason: "damaged", health: s.health, was: prev };
    if (typeof s.air === "number" && typeof s.maxAir === "number" && s.maxAir > 0 && s.air < s.maxAir / 3) {
      return { reason: "low_air", health: s.health };
    }
    return undefined;
  };
}

export function formatDanger(d: Danger): string {
  if (d.reason === "dead") return "dead";
  if (d.reason === "damaged") return `damaged (health ${fmt(d.was!)} → ${fmt(d.health)})`;
  return `low_air (health ${fmt(d.health)})`;
}

const fmt = (n: number) => String(Math.round(n * 10) / 10);
