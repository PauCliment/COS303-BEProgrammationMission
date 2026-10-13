package unittest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import fr.cnes.sirius.patrius.events.Phenomenon;
import fr.cnes.sirius.patrius.events.postprocessing.Timeline;
import fr.cnes.sirius.patrius.time.AbsoluteDate;
import fr.cnes.sirius.patrius.time.AbsoluteDateInterval;
import fr.cnes.sirius.patrius.utils.exception.PatriusException;
import progmission.CompleteMission;
import reader.Site;

/**
 * Unit tests for the access plan produced by {@link CompleteMission}.
 *
 * <p>The purpose of these tests is to validate structural properties that must
 * always be respected by a valid access plan, independently of the exact number
 * of access windows found for each target.</p>
 */
public class AccessPlanTest {

    /** Number of target sites used by the nominal mission. */
    private static final int NUMBER_OF_SITES = 20;

    /** Mission instance shared by all tests in this class. */
    private static CompleteMission mission;

    /** Access plan computed once before running the tests. */
    private static Map<Site, Timeline> accessPlan;

    /**
     * Builds the mission and computes the access plan once for the whole test class.
     *
     * @throws PatriusException if PATRIUS cannot initialize or propagate the mission
     */
    @BeforeClass
    public static void setUpAccessPlan() throws PatriusException {
        mission = new CompleteMission("Access plan unit test", NUMBER_OF_SITES);
        accessPlan = mission.computeAccessPlan();
    }

    /**
     * Checks that one non-null Timeline exists for every target site.
     */
    @Test
    public void testOneTimelinePerSite() {
        assertNotNull("The access plan must not be null.", accessPlan);

        assertEquals(
                "The access plan must contain one Timeline per target site.",
                mission.getSiteList().size(),
                accessPlan.size());

        for (final Site site : mission.getSiteList()) {
            assertTrue(
                    "Missing access-plan entry for site " + site.getName() + ".",
                    accessPlan.containsKey(site));

            assertNotNull(
                    "The Timeline for site " + site.getName() + " must not be null.",
                    accessPlan.get(site));
        }
    }

    /**
     * Checks that every access window is fully contained in the mission horizon.
     */
    @Test
    public void testAllAccessWindowsInsideMissionHorizon() {
        final AbsoluteDate missionStart = mission.getStartDate();
        final AbsoluteDate missionEnd = mission.getEndDate();

        for (final Map.Entry<Site, Timeline> entry : accessPlan.entrySet()) {
            final Site site = entry.getKey();

            for (final Phenomenon accessWindow :
                    entry.getValue().getPhenomenaList()) {

                final AbsoluteDateInterval interval =
                        accessWindow.getTimespan();

                final AbsoluteDate start = interval.getLowerData();
                final AbsoluteDate end = interval.getUpperData();

                assertTrue(
                        "Access window for " + site.getName()
                                + " starts before the mission horizon.",
                        start.compareTo(missionStart) >= 0);

                assertTrue(
                        "Access window for " + site.getName()
                                + " ends after the mission horizon.",
                        end.compareTo(missionEnd) <= 0);
            }
        }
    }

    /**
     * Checks that every access window has a strictly positive duration.
     */
    @Test
    public void testAllAccessWindowsHavePositiveDuration() {

        for (final Map.Entry<Site, Timeline> entry : accessPlan.entrySet()) {
            final Site site = entry.getKey();

            for (final Phenomenon accessWindow :
                    entry.getValue().getPhenomenaList()) {

                final double duration =
                        accessWindow.getTimespan().getDuration();

                assertTrue(
                        "Access window for " + site.getName()
                                + " must have a positive duration.",
                        duration > 0.0);
            }
        }
    }

    /**
     * Checks that two access windows associated with the same target never overlap.
     *
     * <p>The windows are explicitly sorted before the comparison so that this test
     * does not rely on the internal ordering of the Timeline.</p>
     */
    @Test
    public void testAccessWindowsDoNotOverlap() {

        for (final Map.Entry<Site, Timeline> entry : accessPlan.entrySet()) {

            final Site site = entry.getKey();
            final List<AbsoluteDateInterval> intervals =
                    new ArrayList<>();

            for (final Phenomenon accessWindow :
                    entry.getValue().getPhenomenaList()) {

                intervals.add(accessWindow.getTimespan());
            }

            Collections.sort(
                    intervals,
                    new Comparator<AbsoluteDateInterval>() {
                        @Override
                        public int compare(
                                final AbsoluteDateInterval first,
                                final AbsoluteDateInterval second) {

                            return first.getLowerData()
                                    .compareTo(second.getLowerData());
                        }
                    });

            for (int i = 1; i < intervals.size(); i++) {

                final AbsoluteDateInterval previous =
                        intervals.get(i - 1);

                final AbsoluteDateInterval current =
                        intervals.get(i);

                assertTrue(
                        "Overlapping access windows detected for site "
                                + site.getName() + ".",
                        previous.getUpperData()
                                .compareTo(current.getLowerData()) <= 0);
            }
        }
    }
}
