package org.windy.playershelter.api;

/**
 * A progress snapshot of one migration region copy.
 *
 * <p>Addons render it themselves (bossbar / chat / actionbar); the main plugin only reports numbers.
 *
 * @param phase   short machine-ish phase key, e.g. {@code blocks}, {@code entities}, {@code finalize}
 * @param detail  human-readable detail for diagnostics; may be empty
 * @param current finished unit count within the current phase
 * @param total   total unit count within the current phase; {@code 0} when unknown
 * @param overall completion in {@code [0.0, 1.0]}
 */
public record PlayerShelterMigrationProgress(String phase, String detail,
                                             int current, int total, double overall) {
}
