package unittest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.BeforeClass;
import org.junit.Test;

import fr.cnes.sirius.patrius.attitudes.AttitudeLawLeg;
import fr.cnes.sirius.patrius.events.Phenomenon;
import fr.cnes.sirius.patrius.events.postprocessing.Timeline;
import fr.cnes.sirius.patrius.time.AbsoluteDate;
import fr.cnes.sirius.patrius.time.AbsoluteDateInterval;
import fr.cnes.sirius.patrius.utils.exception.PatriusException;
import progmission.CompleteMission;
import reader.Site;
import utils.ConstantsBE;

/**
 * Unit tests for the observation plan produced by {@link CompleteMission}.
 *
 * <p>These tests verify that the selected observations are compatible with the
 * access plan and with the basic scheduling constraints of the mission.</p>
 */
public class ObservationPlanTest {

    /** Number of target sites used by the nominal mission. */
    private static final int NUMBER_OF_SITES = 20;

    /** Numerical tolerance used when comparing durations in seconds. */
    private static final double DURATION_TOLERANCE = 1.0e-6;

    /** Mission instance shared by all tests in this class. */
    private static CompleteMission mission;

    /** Access plan used to validate the observations. */
    private static Map<Site, Timeline> accessPlan;

    /** Observation plan computed once before running the tests. */
    private static Map<Site, AttitudeLawLeg> observationPlan;

    /**
     * Computes the access and observation plans once for the whole test class.
     *
     * @throws PatriusException if PATRIUS cannot initialize or propagate the mission
     */
    @BeforeClass
    public static void setUpObservationPlan()
            throws PatriusException {

        mission =
                new CompleteMission(
                        "Observation plan unit test",
                        NUMBER_OF_SITES);

        accessPlan = mission.computeAccessPlan();
        observationPlan = mission.computeObservationPlan();
    }

    /**
     * Checks that the observation plan contains at most one observation per site.
     */
    
    @Test
    public void testAtMostOneObservationPerSite() {

        assertNotNull(
                "The observation plan must not be null.",
                observationPlan);

        assertTrue(
                "The observation plan cannot contain more observations than target sites.",
                observationPlan.size()
                        <= mission.getSiteList().size());

        final Set<Site> uniqueSites =
                new HashSet<>(observationPlan.keySet());

        assertEquals(
                "Each site must appear at most once in the observation plan.",
                observationPlan.size(),
                uniqueSites.size());

        for (final Site observedSite :
                observationPlan.keySet()) {

            assertTrue(
                    "Observed site "
                            + observedSite.getName()
                            + " is not part of the mission target list.",
                    mission.getSiteList()
                            .contains(observedSite));
        }
    }

    /**
     * Checks that every observation lasts exactly the required integration time.
     */
    @Test
    public void testObservationDurationEqualsIntegrationTime() {

        for (final Map.Entry<Site, AttitudeLawLeg> entry :
                observationPlan.entrySet()) {

            final double duration =
                    entry.getValue()
                            .getTimeInterval()
                            .getDuration();

            assertEquals(
                    "Observation duration for "
                            + entry.getKey().getName()
                            + " must equal ConstantsBE.INTEGRATION_TIME.",
                    ConstantsBE.INTEGRATION_TIME,
                    duration,
                    DURATION_TOLERANCE);
        }
    }

    /**
     * Checks that every observation is fully contained in one accessibility window
     * of the corresponding target site.
     */
    @Test
    public void testEveryObservationIsInsideAnAccessWindow() {

        for (final Map.Entry<Site, AttitudeLawLeg> entry :
                observationPlan.entrySet()) {

            final Site site = entry.getKey();
            final AttitudeLawLeg observation =
                    entry.getValue();

            final Timeline timeline =
                    accessPlan.get(site);

            assertNotNull(
                    "No access Timeline exists for observed site "
                            + site.getName() + ".",
                    timeline);

            final AbsoluteDate observationStart =
                    observation.getDate();

            final AbsoluteDate observationEnd =
                    observation.getEnd();

            boolean insideAccessWindow = false;

            for (final Phenomenon accessWindow :
                    timeline.getPhenomenaList()) {

                final AbsoluteDateInterval accessInterval =
                        accessWindow.getTimespan();

                final boolean startsInside =
                        observationStart.compareTo(
                                accessInterval.getLowerData()) >= 0;

                final boolean endsInside =
                        observationEnd.compareTo(
                                accessInterval.getUpperData()) <= 0;

                if (startsInside && endsInside) {
                    insideAccessWindow = true;
                    break;
                }
            }

            assertTrue(
                    "Observation of "
                            + site.getName()
                            + " is not fully contained in an ACCESSIBILITY-WINDOW.",
                    insideAccessWindow);
        }
    }

    /**
     * Checks that no two scheduled observations overlap in time.
     */
    @Test
    public void testObservationsDoNotOverlap() {

        final List<AttitudeLawLeg> observations =
                new ArrayList<>(observationPlan.values());

        Collections.sort(
                observations,
                new Comparator<AttitudeLawLeg>() {
                    @Override
                    public int compare(
                            final AttitudeLawLeg first,
                            final AttitudeLawLeg second) {

                        return first.getDate()
                                .compareTo(second.getDate());
                    }
                });

        for (int i = 1; i < observations.size(); i++) {

            final AttitudeLawLeg previous =
                    observations.get(i - 1);

            final AttitudeLawLeg current =
                    observations.get(i);

            assertTrue(
                    "Two observations overlap in time.",
                    previous.getEnd()
                            .compareTo(current.getDate()) <= 0);
        }
    }
}