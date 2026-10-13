package unittest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import fr.cnes.sirius.patrius.attitudes.AttitudeLeg;
import fr.cnes.sirius.patrius.attitudes.StrictAttitudeLegsSequence;
import fr.cnes.sirius.patrius.utils.exception.PatriusException;
import progmission.CompleteMission;

/**
 * Tests for the cinematic constraints of {@link CompleteMission}.
 *
 * <p>The project already provides a method to validate the cinematic plan.
 * Therefore, these tests reuse that method instead of duplicating the
 * kinematic-validation logic.</p>
 */
public class KinematicConstraintsTest {

    /** Number of target sites used by the nominal mission. */
    private static final int NUMBER_OF_SITES = 20;

    /** Mission instance shared by all tests in this class. */
    private static CompleteMission mission;

    /** Cinematic plan computed once before running the tests. */
    private static StrictAttitudeLegsSequence<AttitudeLeg>
            cinematicPlan;

    /**
     * Computes all mission plans required to build the cinematic sequence.
     *
     * @throws PatriusException if PATRIUS cannot initialize or propagate the mission
     */
    @BeforeClass
    public static void setUpCinematicPlan()
            throws PatriusException {

        mission =
                new CompleteMission(
                        "Kinematic constraints unit test",
                        NUMBER_OF_SITES);

        mission.computeAccessPlan();
        mission.computeObservationPlan();

        cinematicPlan =
                mission.computeCinematicPlan();
    }

    /**
     * Checks that a cinematic plan has actually been generated.
     */
    @Test
    public void testCinematicPlanIsNotEmpty() {

        assertNotNull(
                "The cinematic plan must not be null.",
                cinematicPlan);

        assertFalse(
                "The cinematic plan must contain at least one attitude leg.",
                cinematicPlan.isEmpty());
    }

    /**
     * Checks the full cinematic plan using the validity checker provided by the
     * project.
     *
     * @throws PatriusException if PATRIUS cannot evaluate the plan
     */
    @Test
    public void testCinematicPlanIsValid()
            throws PatriusException {

        final boolean validity =
                mission.checkCinematicPlan(cinematicPlan);

        assertTrue(
                "The cinematic plan violates at least one kinematic constraint.",
                validity);
    }
}