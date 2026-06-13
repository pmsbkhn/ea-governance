package tech.vsf.ea.archrules;

import java.util.ServiceLoader;

/**
 * Where evaluated {@link FitnessResult}s go — the seam between running a fitness function and the EA
 * portfolio scorecard. The library ships no transport (that would drag in a metrics/HTTP client and
 * break the zero-footprint promise); a consuming pipeline picks one:
 *
 * <ul>
 *   <li><b>default</b> — {@link #NOOP}: results are computed but dropped, so existing test runs stay
 *       silent until a team opts in.</li>
 *   <li><b>{@code -Dea.fitness.sink=stdout}</b> (or env {@code EA_FITNESS_SINK=stdout}) — one JSON
 *       line per result prefixed {@code EA_FITNESS_RESULT }, for a CI log shipper to scrape.</li>
 *   <li><b>custom</b> — register an implementation via {@link ServiceLoader}
 *       ({@code META-INF/services/tech.vsf.ea.archrules.FitnessResultSink}); e.g. push to a
 *       Prometheus Pushgateway or append to a results table. A registered sink wins over the
 *       property default.</li>
 * </ul>
 *
 * <p>The same sink is the target for the dynamic (SLO) layer, so static and runtime verdicts land in
 * one place keyed by {@code system}.
 */
@FunctionalInterface
public interface FitnessResultSink {

    void emit(FitnessResult result);

    /** Discards results. The default so opting in is a deliberate act, never noise. */
    FitnessResultSink NOOP = result -> { };

    /** Prints one JSON line per result, prefixed {@code EA_FITNESS_RESULT }, to {@code System.out}. */
    FitnessResultSink STDOUT = result -> System.out.println("EA_FITNESS_RESULT " + result.toJson());

    /**
     * The process-wide default: the first {@link ServiceLoader}-registered sink if any, else the
     * {@code ea.fitness.sink} property / {@code EA_FITNESS_SINK} env switch, else {@link #NOOP}.
     * Resolved once and cached; an individual harness can still override per instance with
     * {@link FitnessHarness#withSink(FitnessResultSink)}.
     */
    static FitnessResultSink discover() {
        return Holder.DEFAULT;
    }

    final class Holder {
        static final FitnessResultSink DEFAULT = resolve();

        private Holder() { }

        private static FitnessResultSink resolve() {
            for (FitnessResultSink sink : ServiceLoader.load(FitnessResultSink.class)) {
                return sink; // first registered implementation wins
            }
            String mode = System.getProperty("ea.fitness.sink",
                    System.getenv().getOrDefault("EA_FITNESS_SINK", "noop"));
            return "stdout".equalsIgnoreCase(mode.trim()) ? STDOUT : NOOP;
        }
    }
}
