package tech.vsf.ea.archrules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The harness must emit one {@link FitnessResult} for every evaluation, on every outcome path —
 * that stream is what the EA portfolio scorecard consumes, so a missed verdict is a hole in the
 * audit, not just a missing log line.
 */
class FitnessHarnessEmitTest {

    private static final JavaClasses CLASSES =
            new ClassFileImporter().importPackages("tech.vsf.ea.archrules");

    // Reliable rules over this very library: it depends on archunit (→ violation) but not on a
    // made-up package (→ clean).
    private static final ArchRule PASSES =
            noClasses().should().dependOnClassesThat().resideInAnyPackage("zzz.nope..");
    private static final ArchRule FAILS =
            noClasses().should().dependOnClassesThat().resideInAnyPackage("com.tngtech.archunit..");

    private final List<FitnessResult> emitted = new ArrayList<>();
    private final FitnessHarness ea =
            FitnessHarness.forSystem("test-emit").withSink(emitted::add);

    @Test
    void reads_allowed_sync_quanta_from_registry() {
        assertEquals(Set.of("inventory", "payment"), ea.allowedSyncQuanta());
    }

    @Test
    void clean_rule_emits_pass() {
        ea.evaluate("anyEnforcedRule", PASSES, CLASSES);

        FitnessResult r = only();
        assertEquals(FitnessResult.Verdict.PASS, r.verdict());
        assertEquals("enforce", r.mode());          // unlisted rule defaults to enforce
        assertEquals(0, r.violations());
        assertEquals("test-emit", r.system());
        assertEquals(FitnessResult.Layer.CODE, r.layer());
        assertNull(r.waiverExpires());
        assertNotNull(r.ts());
    }

    @Test
    void enforced_violation_emits_fail_then_throws() {
        assertThrows(AssertionError.class, () -> ea.evaluate("anyEnforcedRule", FAILS, CLASSES));

        FitnessResult r = only();
        assertEquals(FitnessResult.Verdict.FAIL, r.verdict());
        assertTrue(r.violations() > 0);
    }

    @Test
    void warn_mode_violation_emits_warn_without_throwing() {
        ea.evaluate("warnRule", FAILS, CLASSES);     // registry: warnRule -> warn

        FitnessResult r = only();
        assertEquals(FitnessResult.Verdict.WARN, r.verdict());
        assertEquals("warn", r.mode());
        assertTrue(r.violations() > 0);
    }

    @Test
    void active_waiver_emits_waived_with_expiry() {
        ea.evaluate("waivedRule", FAILS, CLASSES);   // registry: waiver until 2999-01-01

        FitnessResult r = only();
        assertEquals(FitnessResult.Verdict.WAIVED, r.verdict());
        assertEquals("enforce", r.mode());           // still enforce; the waiver downgrades it
        assertNotNull(r.waiverExpires());
    }

    @Test
    void na_rule_emits_skipped_without_evaluating() {
        ea.evaluate("naRule", FAILS, CLASSES);       // registry: naRule -> n/a; FAILS never runs

        FitnessResult r = only();
        assertEquals(FitnessResult.Verdict.SKIPPED, r.verdict());
        assertEquals(0, r.violations());
    }

    private FitnessResult only() {
        assertEquals(1, emitted.size(), "expected exactly one emitted result");
        return emitted.get(0);
    }
}
