package tech.vsf.ea.archrules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

/**
 * Stack-agnostic fitness functions. Every framework concept (base types, annotations, repository
 * port, package conventions) is passed in as a fully-qualified NAME string or a package pattern —
 * this class imports nothing from any governed framework, so it stays compile-independent of the
 * systems it audits. A cohort binds the concrete names once (see {@code MsfwFitness}).
 *
 * <p>Each method returns an {@link ArchRule} ready to use as an {@code @ArchTest} field or to
 * {@code .check(JavaClasses)} directly.</p>
 */
public final class FitnessRules {

    private FitnessRules() {
    }

    /**
     * A module/layer must not depend on anything "outward". The first argument is the package the
     * rule guards; the rest are packages it may not reach. Use for hexagonal domain purity
     * ({@code domain..} may not see application/adapter/Spring) and for framework module-graph
     * integrity ({@code domain-core} may not see Spring; producer may not see consumer).
     */
    public static ArchRule packageDependsOnNothingOutward(String guardedPackage, String... forbiddenPackages) {
        return noClasses()
                .that().resideInAPackage(guardedPackage)
                .should().dependOnClassesThat().resideInAnyPackage(forbiddenPackages)
                .because(guardedPackage + " must not depend on " + String.join(", ", forbiddenPackages))
                .allowEmptyShould(true);
    }

    /**
     * Use-case slices defined by {@code sliceMatcher} (e.g. {@code "..application.(*).."}) must not
     * depend on each other — each vertical slice stays self-contained.
     */
    public static ArchRule useCaseSlicesDoNotCrossDepend(String sliceMatcher) {
        return useCaseSlicesDoNotCrossDepend(sliceMatcher, new String[0]);
    }

    /**
     * Same, but dependencies whose <em>target</em> resides in a {@code sharedPackage} are ignored —
     * for layouts where the slice matcher's {@code (*)} group also captures shared layers (domain,
     * common) as peer "slices". A slice legitimately depends on the domain/common it is built on;
     * only slice↔slice edges should fail. (Finding F-2: needed by the flat package layout where
     * feature slices are siblings of {@code domain}; harmless where they are not.)
     */
    public static ArchRule useCaseSlicesDoNotCrossDepend(String sliceMatcher, String... sharedPackages) {
        SliceRule rule = SlicesRuleDefinition.slices()
                .matching(sliceMatcher)
                .should().notDependOnEachOther();
        if (sharedPackages.length > 0) {
            rule = rule.ignoreDependency(DescribedPredicate.alwaysTrue(), resideInAnyPackage(sharedPackages));
        }
        return rule
                .because("use-case slices must stay independent of one another (" + sliceMatcher + ")")
                .as("use-case slices matching '" + sliceMatcher + "' do not depend on each other");
    }

    /**
     * No public {@code setX(...)} on classes assignable to the given base type — state changes only
     * through intention-revealing verbs. Pass the base type by FQN (e.g. the framework Aggregate).
     */
    public static ArchRule noPublicSettersOn(String baseTypeFqn) {
        return noMethods()
                .that().areDeclaredInClassesThat().areAssignableTo(baseTypeFqn)
                .should().bePublic().andShould().haveNameMatching("set[A-Z].*")
                .because(baseTypeFqn + " state must change only through verb methods, never public setters")
                .allowEmptyShould(true);
    }

    /**
     * Every use-case method that persists a state change (calls {@code save}/{@code delete} on the
     * repository port) must be annotated with the outbox publish-handler annotation — so state
     * changes and their events commit together. All three references are FQN strings.
     *
     * @param applicationPackage   package holding use-cases (e.g. {@code "..application.."})
     * @param useCaseClassSuffix   simple-name suffix marking a use-case class (e.g. {@code "Uc"})
     * @param repositoryPortFqn    repository port FQN whose {@code save}/{@code delete} mean a write
     * @param publishHandlerFqn    annotation FQN that must be present on such methods
     */
    public static ArchRule stateWritingUseCasesArePublishHandlers(String applicationPackage,
                                                                  String useCaseClassSuffix,
                                                                  String repositoryPortFqn,
                                                                  String publishHandlerFqn) {
        ArchCondition<JavaMethod> annotatedWhenWritingState =
                new ArchCondition<>("be annotated with @" + simpleName(publishHandlerFqn)
                        + " when they persist a state change") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        boolean writesState = method.getMethodCallsFromSelf().stream().anyMatch(call -> {
                            String name = call.getName();
                            return call.getTargetOwner().isAssignableTo(repositoryPortFqn)
                                    && (name.equals("save") || name.equals("delete"));
                        });
                        if (writesState && !method.isAnnotatedWith(publishHandlerFqn)) {
                            events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                    + " writes state via the repository but is not annotated with @"
                                    + simpleName(publishHandlerFqn)));
                        }
                    }
                };
        return methods()
                .that().areDeclaredInClassesThat().resideInAPackage(applicationPackage)
                .and().areDeclaredInClassesThat().haveSimpleNameEndingWith(useCaseClassSuffix)
                .should(annotatedWhenWritingState)
                .because("state-writing use cases must publish through the outbox (@"
                        + simpleName(publishHandlerFqn) + ")")
                .allowEmptyShould(true);
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
