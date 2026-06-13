package tech.vsf.ea.archrules;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;

/**
 * Registry-driven warn → enforce harness. A service's architecture test resolves its system in the
 * EA registry (bundled on the classpath at {@code /ea-registry/<systemId>.yaml}) and evaluates each
 * fitness function in the mode the registry assigns it:
 *
 * <ul>
 *   <li><b>enforce</b> — violations fail the build (the default for any unlisted rule).</li>
 *   <li><b>warn</b> — violations are logged but do not fail the build; the program still sees the
 *       data (the discovery loop) without blocking a service that is mid-adoption.</li>
 *   <li>an active <b>waiver</b> (time-boxed, in the registry) downgrades an otherwise-enforced rule
 *       to warn until its {@code expires} date — then it auto-reverts to enforce and starts failing,
 *       so known debt cannot be forgotten.</li>
 *   <li><b>n/a</b> — skipped (e.g. a value-object-only model with no Entity subtypes).</li>
 * </ul>
 *
 * <pre>{@code
 * private static final FitnessHarness EA = FitnessHarness.forSystem("ecommerce-inventory");
 * private static final JavaClasses CLASSES = new ClassFileImporter().importPackages("vn.ghtk.inventory");
 *
 * @Test void slices() { EA.evaluate("useCaseSliceIsolation", MsfwFitness.useCaseSlices(...), CLASSES); }
 * }</pre>
 */
public final class FitnessHarness {

    private final String systemId;
    private final Map<String, String> modes;          // ruleId -> enforce|warn|n/a
    private final Map<String, LocalDate> waivers;      // ruleId -> expiry

    private FitnessHarness(String systemId, Map<String, String> modes, Map<String, LocalDate> waivers) {
        this.systemId = systemId;
        this.modes = modes;
        this.waivers = waivers;
    }

    @SuppressWarnings("unchecked")
    public static FitnessHarness forSystem(String systemId) {
        String resource = "/ea-registry/" + systemId + ".yaml";
        try (InputStream in = FitnessHarness.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("EA registry entry not found on classpath: " + resource
                        + " — is the system registered and ea-archrules current?");
            }
            Map<String, Object> doc = new Yaml().load(in);
            Map<String, Object> spec = (Map<String, Object>) doc.getOrDefault("spec", Map.of());

            Map<String, String> modes = new HashMap<>();
            Object ff = spec.get("fitnessFunctions");
            if (ff instanceof Map) {
                ((Map<String, Object>) ff).forEach((k, v) -> modes.put(k, String.valueOf(v).trim()));
            }
            Map<String, LocalDate> waivers = new HashMap<>();
            Object w = spec.get("waivers");
            if (w instanceof List) {
                for (Object item : (List<Object>) w) {
                    Map<String, Object> waiver = (Map<String, Object>) item;
                    Object rule = waiver.get("rule");
                    Object expires = waiver.get("expires");
                    if (rule != null && expires != null) {
                        waivers.put(String.valueOf(rule), toLocalDate(expires));
                    }
                }
            }
            return new FitnessHarness(systemId, modes, Collections.unmodifiableMap(waivers));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read EA registry entry " + resource, e);
        }
    }

    /** SnakeYAML reads an unquoted {@code 2026-09-30} as a {@link java.util.Date}; accept either. */
    private static LocalDate toLocalDate(Object value) {
        if (value instanceof java.util.Date date) {
            return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    /**
     * Evaluates one rule in its registry-assigned mode. Passing rules are silent; failing rules
     * either fail the build (enforce) or log and continue (warn / active waiver).
     */
    public void evaluate(String ruleId, ArchRule rule, JavaClasses classes) {
        String mode = modes.getOrDefault(ruleId, "enforce");
        if ("n/a".equals(mode)) {
            return;
        }
        EvaluationResult result = rule.evaluate(classes);
        if (!result.hasViolation()) {
            return;
        }
        LocalDate waiverExpiry = waivers.get(ruleId);
        boolean waived = waiverExpiry != null && !waiverExpiry.isBefore(LocalDate.now());
        if ("warn".equals(mode) || waived) {
            int n = result.getFailureReport().getDetails().size();
            System.out.println("[EA WARN] " + systemId + "/" + ruleId + ": " + n
                    + " violation(s) — not blocking"
                    + (waived ? " (waiver until " + waiverExpiry + ")" : "")
                    + "\n" + result.getFailureReport());
            return;
        }
        throw new AssertionError("[EA ENFORCE] " + systemId + "/" + ruleId + " failed\n"
                + result.getFailureReport());
    }
}
