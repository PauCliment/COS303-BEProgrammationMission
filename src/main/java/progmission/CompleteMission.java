package progmission;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.List;
import java.util.TreeMap;

import org.slf4j.Logger;

import fr.cnes.sirius.patrius.assembly.models.SensorModel;
import fr.cnes.sirius.patrius.attitudes.Attitude;
import fr.cnes.sirius.patrius.attitudes.AttitudeLaw;
import fr.cnes.sirius.patrius.attitudes.AttitudeLawLeg;
import fr.cnes.sirius.patrius.attitudes.AttitudeLeg;
import fr.cnes.sirius.patrius.attitudes.AttitudeProvider;
import fr.cnes.sirius.patrius.attitudes.ConstantSpinSlew;
import fr.cnes.sirius.patrius.attitudes.StrictAttitudeLegsSequence;
import fr.cnes.sirius.patrius.bodies.BodyShape;
import fr.cnes.sirius.patrius.bodies.CelestialBodyFactory;
import fr.cnes.sirius.patrius.bodies.GeometricBodyShape;
import fr.cnes.sirius.patrius.events.CodedEvent;
import fr.cnes.sirius.patrius.events.CodedEventsLogger;
import fr.cnes.sirius.patrius.events.GenericCodingEventDetector;
import fr.cnes.sirius.patrius.events.Phenomenon;
import fr.cnes.sirius.patrius.events.postprocessing.AndCriterion;
import fr.cnes.sirius.patrius.events.postprocessing.ElementTypeFilter;
import fr.cnes.sirius.patrius.events.postprocessing.NotCriterion;
import fr.cnes.sirius.patrius.events.postprocessing.Timeline;
import fr.cnes.sirius.patrius.events.sensor.SensorVisibilityDetector;
import fr.cnes.sirius.patrius.frames.FramesFactory;
import fr.cnes.sirius.patrius.frames.TopocentricFrame;
import fr.cnes.sirius.patrius.math.util.MathLib;
import fr.cnes.sirius.patrius.orbits.pvcoordinates.PVCoordinatesProvider;
import fr.cnes.sirius.patrius.propagation.AbstractPropagator;
import fr.cnes.sirius.patrius.propagation.analytical.KeplerianPropagator;
import fr.cnes.sirius.patrius.propagation.events.ConstantRadiusProvider;
import fr.cnes.sirius.patrius.propagation.events.EventDetector;
import fr.cnes.sirius.patrius.propagation.events.EventDetector.Action;
import fr.cnes.sirius.patrius.propagation.events.LocalRadiusProvider;
import fr.cnes.sirius.patrius.propagation.events.ThreeBodiesAngleDetector;
import fr.cnes.sirius.patrius.propagation.events.VariableRadiusProvider;
import fr.cnes.sirius.patrius.time.AbsoluteDate;
import fr.cnes.sirius.patrius.time.AbsoluteDateInterval;
import fr.cnes.sirius.patrius.time.AbsoluteDateIntervalsList;
import fr.cnes.sirius.patrius.utils.exception.PatriusException;
import fr.cnes.sirius.patrius.propagation.events.ThreeBodiesAngleDetector;
import fr.cnes.sirius.patrius.attitudes.TargetGroundPointing;
import fr.cnes.sirius.patrius.math.geometry.euclidean.threed.Vector3D;
import reader.Site;
import utils.ConstantsBE;
import utils.LogUtils;
import utils.ProjectUtils;

/**
 * This class implements the context of an Earth Observation mission.
 * 
 * @author herberl
 */
public class CompleteMission extends SimpleMission {

	/**
	 * Maximum checking interval (s) for the event detection during the orbit
	 * propagation.
	 */
	public static final double MAXCHECK_EVENTS = 120.0;

	/**
	 * Default convergence threshold (s) for the event computation during the orbit
	 * propagation.
	 */
	public static final double TRESHOLD_EVENTS = 1.e-4;

	/**
	 * A hash based on all the constants of the BE. This hash is unique and ensures
	 * we get the right filename for a given {@link Site} and a given set of
	 * {@link ConstantsBE} parameters. It is used in the names of the files
	 * containing the serialized accesses for all {@link Site}.
	 *
	 */
	private static final int HASH_CONSTANT_BE;

	/**
	 * [DO NOT MODIFY THIS METHOD]
	 * 
	 * This block of code is static, shared by all instances of CompleteMission. It
	 * is executed only once when the class is first instantiated, to produce an
	 * unique hash which encodes the whole constants of the BE in one single code.
	 * You don't have to (and must not) modify this code if you want to benefit from
	 * automatic serialization and reading of you access Timelines.
	 */
	static {
		// Creating an unique hash code to be used for serialization
		int hash = 17;

		long doubleBits;
		int doubleHash;

		// The hash code depends from all the parameters from a given simulation : start
		// and end date and all the ConstantBE values. We build a hashcode from all
		// those values converting them as int

		// Start and end dates
		hash = 31 * hash + ConstantsBE.START_DATE.hashCode();
		hash = 31 * hash + ConstantsBE.END_DATE.hashCode();

		// ConstantBE values
		double[] doubles = new double[] { ConstantsBE.ALTITUDE, ConstantsBE.INCLINATION, ConstantsBE.MEAN_ECCENTRICITY,
				ConstantsBE.ASCENDING_NODE_LONGITUDE, ConstantsBE.POINTING_CAPACITY, ConstantsBE.SPACECRAFT_MASS,
				ConstantsBE.MAX_SUN_INCIDENCE_ANGLE, ConstantsBE.MAX_SUN_PHASE_ANGLE, ConstantsBE.INTEGRATION_TIME,
				ConstantsBE.POINTING_AGILITY_DURATIONS[0], ConstantsBE.POINTING_AGILITY_DURATIONS[1],
				ConstantsBE.POINTING_AGILITY_DURATIONS[2], ConstantsBE.POINTING_AGILITY_DURATIONS[3],
				ConstantsBE.POINTING_AGILITY_DURATIONS[4], ConstantsBE.POINTING_AGILITY_ROTATIONS[0],
				ConstantsBE.POINTING_AGILITY_ROTATIONS[1], ConstantsBE.POINTING_AGILITY_ROTATIONS[2],
				ConstantsBE.POINTING_AGILITY_ROTATIONS[3], ConstantsBE.POINTING_AGILITY_ROTATIONS[4], MAXCHECK_EVENTS,
				TRESHOLD_EVENTS };
		for (Double d : doubles) {
			doubleBits = Double.doubleToLongBits(d);
			doubleHash = (int) (doubleBits ^ (doubleBits >>> 32));
			hash = 31 * hash + doubleHash;
		}

		// Finally assigning the current simulation hashConstantBE value
		HASH_CONSTANT_BE = hash;
	}

	/**
	 * Logger for this class.
	 */
	private static final Logger logger = LogUtils.GLOBAL_LOGGER;

	/**
	 * This {@link Map} will be used to enumerate each site access {@link Timeline},
	 * that is to say a {@link Timeline} with access windows respecting all
	 * observation conditions. This object corresponds to the access plan, which
	 * will be computed in the computeAccessPlan() method.
	 */
	private final Map<Site, Timeline> accessPlan;

	/**
	 * This {@link Map} will be used to enumerate each site's programmed
	 * observation. We suggest to use an {@link AttitudeLawLeg} to encapsulate the
	 * guidance law of each observation. This object corresponds to the observation
	 * plan, which will be computed in the computeObservationPlan() method.
	 */
	private final Map<Site, AttitudeLawLeg> observationPlan;

	/**
	 * This {@link TreeMap} will be used to keep an order for each site's programmed
	 * observation.
	 * Scheduled observations, chronologically sorted (key = observation start)
	 */
	private final TreeMap<AbsoluteDate, Site> scheduledObservations;

	/**
	 * {@link StrictAttitudeLegsSequence} representing the cinematic plan during the
	 * whole mission horizon. Each {@link AttitudeLeg} corresponds to a diffrent
	 * attitude law : either nadir pointing, target pointing or a slew between two
	 * laws. This object corresponds to the cinematic plan, which will be computed
	 * in the computeCinematicPlan() method.
	 */
	private final StrictAttitudeLegsSequence<AttitudeLeg> cinematicPlan;

	/**
	 * Constructor for the {@link CompleteMission} class.
	 *
	 * @param missionName   Name of the mission
	 * @param numberOfSites Number of target {@link Site} to consider, please give a
	 *                      number between 1 and 100.
	 * @throws PatriusException      Can be raised by Patrius when building
	 *                               particular objects. Here it comes from
	 *                               {@link FramesFactory}
	 * @throws IllegalStateException if the mission horizon is too short
	 */
	public CompleteMission(final String missionName, int numberOfSites) throws PatriusException {

		// Since this class extends the SimpleMission class, we need to use the super
		// constructor to instantiate our instance of CompleteMission. All the
		// attributes of the super class will be instantiated during this step.
		super(missionName, numberOfSites);

		// Initialize the mission plans with empty maps. You will fill those HashMaps in
		// the "compute****Plan()" methods.
		this.accessPlan = new HashMap<>();
		this.observationPlan = new HashMap<>();
		this.scheduledObservations = new TreeMap<>();
		this.cinematicPlan = new StrictAttitudeLegsSequence<>();

	}

	/**
	 * Compute the access plan.
	 * 
	 * The access plan corresponds to the object gathering all the
	 * opportunities of access for all the sites of interest during the mission
	 * horizon. One opportunity of access is defined by an access window (an
	 * interval of time during which the satellite can observe the target and during
	 * which all the observation conditions are achieved : visibility, incidence
	 * angle, illumination of the scene,etc.). Here, we use the Patrius class 
	 * {@link Timeline} to encapsulate all the access windows of each site of
	 * interest. One access window will then be described by the {@link Phenomenon}
	 * object, itself defined by two {@link CodedEvent} objects giving the start and
	 * end of the access window. Please find more tips and help in the submethods of
	 * this method.
	 * 
	 * @return the sites access plan with one {@link Timeline} per {@link Site}
	 * @throws PatriusException If a {@link PatriusException} occurs during the
	 *                          computations
	 */
	public Map<Site, Timeline> computeAccessPlan() throws PatriusException {

		logger.info("============= Computing Access Plan =============");

		/**
		 * Step 1 - Before the propagation; we load all already serialized Timelines or
		 * create the associated loggers
		 */
		// We create a Map containing all the loggers for each Site's constraint, that
		// we be used to optimize propagation
		final Map<Site, ArrayList<CodedEventsLogger>> sitesEventsLoggers = new HashMap<>();

		for (final Site targetSite : this.getSiteList()) {
			logger.info(" Site : " + targetSite.getName());

			// Checking if the Site access Timeline has already been serialized or not
			final String filename = generateSerializationName(targetSite, HASH_CONSTANT_BE);
			File file = new File(filename);
			boolean loaded = false;

			// If the file exist for the current Site, we try to load its content
			if (file.exists()) {
				try {
					// Load the timeline from the file and add it to the accessPlan
					final Timeline siteAccessTimeline = loadSiteAccessTimeline(filename);
					this.accessPlan.put(targetSite, siteAccessTimeline);
					loaded = true; // the Site has been loaded, no need to compute the access again
					logger.info(filename + "has been loaded successfully!");
				} catch (ClassNotFoundException | IOException e) {
					logger.warn(filename + " could not be loaded !");
					logger.warn(e.getMessage());
				}
			}
			

			// If it was not serialized or if loading has failed, we need to compute and
			// serialize the site access Timeline so we will create the asscoiated loggers
			if (!loaded) {
				logger.info(targetSite.getName() + " has not been serialized, launching access computation...");

				// Create the loggers of the targetSite and the associated constraint
				ArrayList<CodedEventsLogger> siteLoggers = new ArrayList<>();

				// Create one logger per constraint, by completing and adapting
				// createSiteXConstraintLogger for each constraint, and add all
				// loggers to the loggers List for this Site
				siteLoggers.add(createVisibilityConstraintLogger(targetSite)); // visibility
				siteLoggers.add(createIncidenceConstraintLogger(targetSite)); // sun incidence
				siteLoggers.add(createDazzlingConstraintLogger(targetSite)); // dazzling

				// Finally, store the Site's loggers in the global Map
				sitesEventsLoggers.put(targetSite, siteLoggers);
			}
		}

		/**
		 * Step 2 - Propagating all Sites's detectors and loggers in parallel to
		 * optimize runtime
		 */

		// Now that every detector and logger has been added to the propagator for all
		// Sites, we can propagate the Orbit for all Sites at once
		this.getSatellite().getPropagator().propagate(this.getStartDate(), this.getEndDate());

		/**
		 * Step 3 - After the propagation, we can create all non-existing Timelines and
		 * serialize them
		 */
		// After the propagation, all the loggers can be used to create the access
		// Timelines and serialize them
		for (Entry<Site, ArrayList<CodedEventsLogger>> entry : sitesEventsLoggers.entrySet()) {
			final Site site = entry.getKey();
			final ArrayList<CodedEventsLogger> eventsLoggersList = entry.getValue();

			// Create the timeline using the 3 loggers previously created
			// Make sure to call the method using the loggers in the right order compared to
			// what you declared previously
			final Timeline siteAccessTimeline = createSiteAccessTimeline(
				site,
				eventsLoggersList.get(0),
				eventsLoggersList.get(1),
				eventsLoggersList.get(2)
			);
			this.accessPlan.put(site, siteAccessTimeline);
			ProjectUtils.printTimeline(siteAccessTimeline);

			final String filename = generateSerializationName(site, HASH_CONSTANT_BE);
			try {
				// Serialize the timeline for later reading
				serializeSiteAccessTimeline(filename, siteAccessTimeline);
				logger.info(filename + " has been serialized successfully !");
			} catch (IOException e) {
				logger.warn(filename + " could not be serialized !");
				logger.warn(e.getMessage());
			}
		}
		return this.accessPlan;
	}

	/**
	 * Computes the cinematic plan.
	 * 
	 * Here we compute the cinematic plan, which is the cinematic chain of
	 * attitude law legs (observation, default law and slews) needed to perform the
	 * mission. We start and end the mission in default law and during the
	 * horizon, we alternate between default law, observation legs and slew legs.
	 * 
	 * @return a {@link StrictAttitudeLegsSequence} that gives all the cinematic
	 *         plan of the {@link Satellite}. It is a chronological sequence of all
	 *         the {@link AttitudeLawLeg} that are necessary to define the
	 *         {@link Attitude} of the {@link Satellite} during all the mission
	 *         horizon. Those legs can have 3 natures : pointing a target site,
	 *         pointing nadir and performing a slew between one of the two previous
	 *         kind of legs.
	 * @throws PatriusException
	 */
	public StrictAttitudeLegsSequence<AttitudeLeg> computeCinematicPlan() throws PatriusException {

		logger.info("============= Computing Cinematic Plan =============");

		this.cinematicPlan.clear();

		// The propagator will be used to compute Attitudes
		final KeplerianPropagator propagator = this.createDefaultPropagator();

		// Get the Nadir attitude law
		final AttitudeLaw nadirLaw = this.getSatellite().getDefaultAttitudeLaw();

		// Get the mission plan start dates
		final AbsoluteDate planStart = this.getStartDate();
		final Site firstSite = this.scheduledObservations.firstEntry().getValue();
		final AttitudeLeg firstObs = observationPlan.get(firstSite);
		final AbsoluteDate obsStart = firstObs.getDate();
		final Attitude obsStartAtt = firstObs.getAttitude(propagator, obsStart, getEme2000());

		// Get the first slew date and find the initial attitudes
		final double slewStartDuration = slewDurationNadirToAtt(nadirLaw, obsStartAtt, propagator);
		final AbsoluteDate slewStart = obsStart.shiftedBy(-slewStartDuration);
		final Attitude slewStartAtt = nadirLaw.getAttitude(propagator, slewStart, getEme2000());

		// Compute and add the initial nadir and slew
		final AttitudeLawLeg startNadir = new AttitudeLawLeg(nadirLaw, planStart, slewStart, "NADIR-Initial");
		final ConstantSpinSlew startSlew = new ConstantSpinSlew(slewStartAtt, obsStartAtt, "SLEW-NADIR-TO-" + firstSite.getName());
		this.cinematicPlan.add(startNadir);
		this.cinematicPlan.add(startSlew);

		// Go through all the observations
		Entry<AbsoluteDate, Site> previousObsEntry = null;
		AttitudeLawLeg previousObsLeg = null;
		for (final Entry<AbsoluteDate, Site> entry : scheduledObservations.entrySet()) {

			// Get the current observation window
			final AttitudeLawLeg obsLeg = this.observationPlan.get(entry.getValue());
			
			// Add the slew to get to the current observation window
			if (entry.getKey() != scheduledObservations.firstKey() && previousObsEntry != null) {
				
				// Get the starting slew state 
				final AbsoluteDate fromDate = previousObsLeg.getEnd();	
				final Site fromTarget = previousObsEntry.getValue();
				final Attitude fromAtt = previousObsLeg.getAttitude(propagator, fromDate, getEme2000());
				
				// Get the target slew state
				final AbsoluteDate toDate = entry.getKey();		
				final Site toTarget = entry.getValue();	
				final Attitude toAtt = obsLeg.getAttitude(propagator, toDate, getEme2000());

				// Get the durations needed to transition to and from the hypothetical nadir
				final double nadirStartDuration = slewDurationAttToNadir(fromAtt, nadirLaw, propagator);
				final double nadirEndDuration = slewDurationNadirToAtt(nadirLaw, toAtt, propagator);

				// Compute the possibility of transitioning to NADIR
				final boolean directSlew = toDate.durationFrom(fromDate) <= (nadirStartDuration + nadirEndDuration);
				
				// Compute and add a direct slew
				if (directSlew) {
					final String slewCode = "SLEW-" + fromTarget.getName() + "-TO-" + toTarget.getName();
					final ConstantSpinSlew slew = new ConstantSpinSlew(fromAtt, toAtt, slewCode);
					this.cinematicPlan.add(slew);
				}

				// Compute and add an Obs --[Slew]--> Nadir --[Slew]--> Obs
				else {

					// Get the intermediate Nadir start/end states
					final AbsoluteDate nadirStart = fromDate.shiftedBy(nadirStartDuration);
					final AbsoluteDate nadirEnd = toDate.shiftedBy(-nadirEndDuration);

					final Attitude nadirStartAtt = nadirLaw.getAttitude(propagator, nadirStart, getEme2000());
					final Attitude nadirEndtAtt = nadirLaw.getAttitude(propagator, nadirEnd, getEme2000());

					// Add slew from Obs to Nadir
					final String firstSlewCode = "SLEW-" + fromTarget.getName() + "-TO-NADIR";
					final ConstantSpinSlew firstSlew = new ConstantSpinSlew(fromAtt, nadirStartAtt, firstSlewCode);
					this.cinematicPlan.add(firstSlew);

					// Add the nadir leg
					final String intermediateNadirCode = "NADIR-" + fromTarget.getName() + "-TO-" + toTarget.getName();
					final AttitudeLawLeg intermediateNadir = new AttitudeLawLeg(nadirLaw, nadirStart, nadirEnd, intermediateNadirCode);
					this.cinematicPlan.add(intermediateNadir);

					// Add slew from Nadir to Next Obs
					final String secondSlewCode = "SLEW-NADIR-TO-" + toTarget.getName();
					final ConstantSpinSlew lastSlew = new ConstantSpinSlew(nadirEndtAtt, toAtt, secondSlewCode);
					this.cinematicPlan.add(lastSlew);

				}

			}

			// Add the pointing law for the current observation
			this.cinematicPlan.add(obsLeg);

			// Update the previous observation
			previousObsEntry = entry;
			previousObsLeg = obsLeg;

		}

		// Get the mission plan end dates
		final AbsoluteDate planEnd = this.getEndDate();
		final Site lastSite = this.scheduledObservations.lastEntry().getValue();
		final AttitudeLeg lastObs = observationPlan.get(lastSite);
		final AbsoluteDate obsEnd = lastObs.getEnd();
		final Attitude obsEndAtt = lastObs.getAttitude(propagator, obsEnd, getEme2000());

		// Get the last slew date and find the final attitudes
		final double slewEndDuration = slewDurationAttToNadir(obsEndAtt, nadirLaw, propagator);
		final AbsoluteDate slewEnd = obsEnd.shiftedBy(+slewEndDuration);
		final Attitude slewEndtAtt = nadirLaw.getAttitude(propagator, slewEnd, getEme2000());

		// Compute and add the final nadir and slew
		final ConstantSpinSlew endSlew = new ConstantSpinSlew(obsEndAtt, slewEndtAtt, "SLEW-" + lastSite.getName() + "TO-NADIR");
		final AttitudeLawLeg endNadir = new AttitudeLawLeg(nadirLaw, slewEnd, planEnd, "NADIR-Final");
		this.cinematicPlan.add(endSlew);
		this.cinematicPlan.add(endNadir);

		return this.cinematicPlan;
	}

	/**
	 * Compute the slew duration from an initial {@link Attitude} to a target 
	 * {@link AttitudeLaw} representing nadir pointing, using an iterative 
	 * fixed-point solver approach.
	 * 
	 * If the solver fails to converge within the maximum number of iterations or 
	 * encounters an exception during orbital propagation, a safe fallback value 
	 * corresponding to the maximum allowable slew duration is returned.
	 * 
	 * Note: this method uses a convergence tolerance and includes a small safety buffer 
	 * on the final returned duration.
	 * 
	 * @param startAttitude The initial {@link Attitude} at the start of the slew.
	 * @param nadirLeg      The {@link AttitudeLaw} defining the target nadir law.
	 * @param propagator    The {@link KeplerianPropagator} used for state propagation.
	 * @return The computed slew duration (in seconds) with an added tolerance buffer, 
	 *         or the maximum slew duration as a safe fallback.
	 */
	private double slewDurationAttToNadir (Attitude startAttitude, AttitudeLaw nadirLaw, AbstractPropagator propagator) {

		// Initialize the slew durations
		double durationGuess = getSatellite().getMaxSlewDuration();
		double previousDurationGuess = 0.0;

		// Initialize the solver parameters
		int iterations = 0;
		boolean iterationFlag = false;
		final int maxIterations = 100;
		final double tolerance = 1.0e-4;

		final AbsoluteDate startDate = startAttitude.getDate();

		while (Math.abs(durationGuess - previousDurationGuess) > tolerance) {

			// Update the solver parameters
			iterations += 1;
			previousDurationGuess = durationGuess;

			// Update the final attitude;
			try {
				Attitude finalAttitude = nadirLaw.getAttitude(propagator, startDate.shiftedBy(durationGuess), getEme2000());
				durationGuess = getSatellite().computeSlewDuration(startAttitude, finalAttitude);
			}
			// If an error is found, the loop is exited
			catch (PatriusException error) {
				logger.info("Error computing the slew time");
				iterationFlag = true;
				break;
			}

			// Exit condition if iteration count becomes too high
			if (iterations > maxIterations) {
				iterationFlag = true;
				break;
			}
		}

		if (iterationFlag == false) {
			// Return the found duration + tolerance to make sure it is feasible
			return durationGuess + tolerance;
		}
		else {
			// Safe fall-back
			return getSatellite().getMaxSlewDuration();
		}
	}

	/**
	 * Compute the slew duration from an initial {@link AttitudeLaw} representing 
	 * nadir pointing to a target {@link Attitude}, using an iterative fixed-point 
	 * solver approach.
	 * 
	 * This serves as the reverse counterpart to {@link #slewDurationAttToNadir(Attitude, AttitudeLawLeg, KeplerianPropagator)}, 
	 * stepping backward in time from the target arrival date to determine the required start state.
	 * 
	 * If the solver fails to converge within the maximum number of iterations or 
	 * encounters an exception during orbital propagation, a safe fallback value 
	 * corresponding to the maximum allowable slew duration is returned.
	 * 
	 * @param nadirLeg     The {@link AttitudeLaw} defining the initial nadir law.
	 * @param endAttitude  The final {@link Attitude} at the arrival of the slew.
	 * @param propagator   The {@link AbstractPropagator} used for state propagation.
	 * @return The computed slew duration (in seconds) with an added tolerance buffer, 
	 *         or the maximum slew duration as a safe fallback.
	 */
	private double slewDurationNadirToAtt (AttitudeLaw nadirLaw, Attitude endAttitude, AbstractPropagator propagator) {

		// Initialize the slew durations
		double durationGuess = getSatellite().getMaxSlewDuration();
		double previousDurationGuess = 0.0;

		// Initialize the solver parameters
		// Initialize the solver parameters
		int iterations = 0;
		boolean iterationFlag = false;
		final int maxIterations = 100;
		final double tolerance = 1.0e-4;

		final AbsoluteDate endDate = endAttitude.getDate();

		while (Math.abs(durationGuess - previousDurationGuess) > tolerance) {

			// Update the solver parameters
			iterations += 1;
			previousDurationGuess = durationGuess;

			// Update the final attitude;
			try {
				Attitude startAttitude = nadirLaw.getAttitude(propagator, endDate.shiftedBy(-durationGuess), getEme2000());
				durationGuess = getSatellite().computeSlewDuration(startAttitude, endAttitude);
			}
			// If an error is found, the loop is exited
			catch (PatriusException error) {
				logger.info("Error computing the slew time");
				iterationFlag = true;
				break;
			}

			// Exit condition if iteration count becomes too high
			if (iterations > maxIterations) {
				iterationFlag = true;
				break;
			}
		}

		if (iterationFlag == false) {
			// Return the found duration + tolerance to make sure it is feasible
			return durationGuess + tolerance;
		}
		else {
			// Safe fall-back
			return getSatellite().getMaxSlewDuration();
		}
	}


	/**
	 * Compute the observation plan.
	 * 
	 * Reminder : the observation plan corresponds to the sequence of observations
	 * programmed for the satellite during the mission horizon. Each observation is
	 * defined by an observation window (start date; end date defining an
	 * {@link AbsoluteDateInterval}), a target (target {@link Site}) and an
	 * {@link AttitudeLawLeg} giving the attitude guidance to observe the target.
	 * 
	 * @return the sites observation plan with one {@link AttitudeLawLeg} per
	 *         {@link Site}
	 * @throws PatriusException If a {@link PatriusException} occurs during the
	 * computations
	*/
	public Map<Site, AttitudeLawLeg> computeObservationPlan() throws PatriusException {

		logger.info("============= Computing Observation Plan =============");

		this.observationPlan.clear();
		this.scheduledObservations.clear();
		double totalValue = 0.0; // Score of the final observation plan

		// Safety margin (s) added to every theoretical slew duration
		final double margin = 0.1;

		// Default (nadir) attitude law, used for the slews at the start / end of the mission
		final AttitudeLaw nadirLaw = this.getSatellite().getDefaultAttitudeLaw();
		final double obsDuration = ConstantsBE.INTEGRATION_TIME;

		// Sampling step (s) of the possible observation start dates in a window
		final double step = 5.0;

		// One single propagator reused for all the incidence computations
		final KeplerianPropagator propagator = this.createDefaultPropagator();

		// Hierarchical greedy: sites with the highest score are handled first
		final List<Site> sites = new ArrayList<>(this.accessPlan.keySet());
		sites.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

		// Loop through the potential target sites in descending order of score
		for (final Site target : sites) {

			final TopocentricFrame siteFrame = new TopocentricFrame(this.getEarth(), target.getPoint(), target.getName());

			// Observation law of the target, built once and reused for the cinematic checks and for the final AttitudeLawLeg
			final AttitudeLaw observationLaw = createObservationLaw(target);

			// Best feasible observation found so far for this site
			AbsoluteDate bestStart = null;
			double bestValue = 0.0;

			// Loop through all possible accesses for this target (to get the one with maximum elevation)
			for (final Phenomenon accessWindow : this.accessPlan.get(target).getPhenomenaList()) {

				final AbsoluteDate firstStart = accessWindow.getTimespan().getLowerData();

				// Duration over which the observation start date can slide in the window
				final double span = accessWindow.getTimespan().getUpperData().durationFrom(firstStart) - obsDuration;
				if (span <= 0) {
					continue; // window too short to contain a full observation
				}

				// The last sample is clamped to the latest possible start of the window
				for (double offset = 0.0; offset < span + step; offset += step) {
					final AbsoluteDate start = firstStart.shiftedBy(Math.min(offset, span));
					final AbsoluteDate end = start.shiftedBy(obsDuration);
					final AbsoluteDate middle = start.shiftedBy(obsDuration / 2);

					// Attitudes of the candidate observation at its start and at its end
					final Attitude candidateStartAtt = observationLaw.getAttitude(propagator, start, this.getEme2000());
					final Attitude candidateEndAtt = observationLaw.getAttitude(propagator, end, this.getEme2000());

					boolean free;

					// Cinematic check with the previous scheduled observation
					// (or with the nadir law if the candidate would be the first observation)
					final Entry<AbsoluteDate, Site> previous = scheduledObservations.floorEntry(start);
					if (previous != null) {
					    final AttitudeLawLeg previousLeg = this.observationPlan.get(previous.getValue());
					    final Attitude previousEndAtt = previousLeg.getAttitude(propagator, previousLeg.getEnd(), this.getEme2000());
					    final double slewDuration = this.getSatellite().computeSlewDuration(previousEndAtt, candidateStartAtt);
					    free = start.durationFrom(previousLeg.getEnd()) >= slewDuration + margin;
					} else {
					    free = start.durationFrom(this.getStartDate()) >= slewDurationNadirToAtt(nadirLaw, candidateStartAtt, propagator);
					}

					// Cinematic check with the next scheduled observation
					// (or with the nadir law if the candidate would be the last observation)
					if (free) {
					    final Entry<AbsoluteDate, Site> next = scheduledObservations.ceilingEntry(start);
					    if (next != null) {
					        final AttitudeLawLeg nextLeg = this.observationPlan.get(next.getValue());
					        final Attitude nextStartAtt = nextLeg.getAttitude(propagator, next.getKey(), this.getEme2000());
					        final double slewDuration = this.getSatellite().computeSlewDuration(candidateEndAtt, nextStartAtt);
        					free = next.getKey().durationFrom(end) >= slewDuration + margin;
    					} else {
					        free = this.getEndDate().durationFrom(end) >= slewDurationAttToNadir(candidateEndAtt, nadirLaw, propagator);
					    }
					}

					if (!free) {
					    continue;
					}

					// Value = site score * cos(incidence angle) at the middle of the observation.
					// cos(incidence) = sin(elevation of the satellite seen from the site)
					final Vector3D satPosition = propagator.getPVCoordinates(middle, this.getEme2000()).getPosition();
					final double elevation = siteFrame.getElevation(satPosition, this.getEme2000(), middle);
					final double value = target.getScore() * MathLib.sin(elevation);

					if (value > bestValue) {
						bestValue = value;
						bestStart = start;
					}
				}
			}

			// If a feasible observation exists, insert it in the plan
			if (bestStart != null) {
				final AbsoluteDate bestEnd = bestStart.shiftedBy(obsDuration);
				final AbsoluteDateInterval bestInterval = new AbsoluteDateInterval(bestStart, bestEnd);
				final AttitudeLawLeg obsLeg = new AttitudeLawLeg(observationLaw, bestInterval, "OBS_" + target.getName());
				this.observationPlan.put(target, obsLeg);
				scheduledObservations.put(bestStart, target);
				totalValue += bestValue;
			}
		}

		// Console display of the final plan, in chronological order
		logger.info("Final observation plan (Score: " + totalValue + ") :");
		int index = 1;
		for (final Entry<AbsoluteDate, Site> entry : scheduledObservations.entrySet()) {
			final AttitudeLawLeg leg = this.observationPlan.get(entry.getValue());
			logger.info("Observation n." + index + " : " + entry.getValue().getName() + " - Interval: [" + leg.getDate() + " - " + leg.getEnd() + "]");
			index++;
		}

		return this.observationPlan;
	}

	/**
	 * Adapted instance of {@link EventDetector} matching the input need for
	 * monitoring the events defined by the Visibility constraint.
	 * 
	 * @param targetSite The {@link Site} defining the city to observe.
	 * @return An {@link EventDetector} that detects the constraint (in this
	 * case, a {@link SensorVisibilityDetector}).
	 */
	private EventDetector createVisibilityConstraintDetector(Site targetSite) {

		
		// Find the local frame and apparent radius providers for the different sites
		final TopocentricFrame sitePvProv = new TopocentricFrame(this.getEarth(), targetSite.getPoint(), targetSite.getName());
		final LocalRadiusProvider siteRadProv = new ConstantRadiusProvider(10000.0); // Site approximated as a single point

		// Define a visibility cone using the satellite's sensor
		final SensorModel visibilityCone = new SensorModel(this.getSatellite().getAssembly(), "sensor");

		// Add geometrical parameters to the visibility model
		visibilityCone.addMaskingCelestialBody(this.getEarth()); // Accounts for the Earth's geometry
		visibilityCone.setMainTarget(sitePvProv, siteRadProv); // Accounts for the characteristics of the target site
		
		// Create an appropriate detector of visibility within the sensor cone
		final EventDetector detector = new SensorVisibilityDetector(visibilityCone, MAXCHECK_EVENTS, TRESHOLD_EVENTS, Action.CONTINUE, Action.CONTINUE);
		return detector;
	}

	/**
	 * Adapted instance of {@link EventDetector} matching the input need for
	 * monitoring the events defined by the Sun Incidence angle constraint.
	 * 
	 * @param targetSite The {@link Site} defining the city to observe.
	 * @return An {@link EventDetector} that detects the constraint (in this
	 * case, a {@link ThreeBodiesAngleDetector}).
	 */
	private EventDetector createIncidenceConstraintDetector(Site targetSite) {

		 // Local frame of the observation site
		final TopocentricFrame sitePvProv = new TopocentricFrame(
				this.getEarth(),
				targetSite.getPoint(),
				targetSite.getName()
		);

		// ThreeBodiesAngleDetector calculates:
		// angle(Sun -> Site, Earth -> Site)
		// which is the angle at the Site between:
		// Site -> Sun and Site -> Earth.
		//
		// Since Site -> Earth is opposite to the local zenith:
		//
		//     detectorAngle = 180° - solarIncidenceAngle
		//
		// We want:
		//
		//     solarIncidenceAngle <= MAX_SUN_INCIDENCE_ANGLE
		//
		// therefore:
		//
		//     detectorAngle >= 180° - MAX_SUN_INCIDENCE_ANGLE

		final double thresholdAngle = MathLib.toRadians(
				180.0 - ConstantsBE.MAX_SUN_INCIDENCE_ANGLE
		);

		// Initialize the three bodies sun incidence angle detector
		final EventDetector detector = new ThreeBodiesAngleDetector(
				this.getSun(),
				sitePvProv,
				this.getEarth(),
				thresholdAngle,
				MAXCHECK_EVENTS,
				TRESHOLD_EVENTS,
				Action.CONTINUE
		);

		return detector;
	}

	/**
	 * Adapted instance of {@link EventDetector} matching the input need for
	 * monitoring the events defined by the Sun Incidence angle constraint.
	 * 
	 * @param targetSite The {@link Site} defining the city to observe.
	 * @return An {@link EventDetector} that detects the constraint (in this
	 * case, a {@link ThreeBodiesAngleDetector}).
	 */
	private EventDetector createDazzlingConstraintDetector(Site targetSite) {

		// Initialize the target site frame provider
		final TopocentricFrame target = new TopocentricFrame(
			this.getEarth(),
			targetSite.getPoint(),
			targetSite.getName()
		);

		// Handle the possible Patrius exception
		try {

			// Initialize the sun object and the maximum allowed angle
			final PVCoordinatesProvider sun = CelestialBodyFactory.getSun();
			final double maxPhaseAngle = MathLib.toRadians(ConstantsBE.MAX_SUN_PHASE_ANGLE);

			// Initialize and return the phase angle detector
			return new ThreeBodiesAngleDetector(
				target,
				sun,
				ThreeBodiesAngleDetector.BodyOrder.FIRST,
				maxPhaseAngle,
				MAXCHECK_EVENTS,
				TRESHOLD_EVENTS,
				Action.CONTINUE
			);

		} catch (PatriusException error) {
			throw new IllegalStateException(error);
		}
	}
	

	/**
	 * Creates an observation leg, that is to say an {@link AttitudeLaw} that give
	 * the {@link Attitude} (pointing direction) of the {@link Satellite} in order
	 * to perform the observation of the input target {@link Site}.
	 * 
	 * An {@link AttitudeLaw} is an {@link AttitudeProvider} providing the method
	 * {@link AttitudeProvider#getAttitude()} which can be used to compute the
	 * {@link Attitude} of the {@link Satellite} at any given {@link AbsoluteDate}
	 * (instant) during the mission horizon.
	 * 
	 * To perform an observation, the satellite needs to point the target for a
	 * fixed duration.
	 * 
	 * Here, we use the {@link TargetGroundPointing}. This law provides a the
	 * Attitude of a Satellite that only points one target at the surface of a
	 * BodyShape.
	 * 
	 * An {@link AttitudeLaw} is valid at anu time in theory.
	 * 
	 * @param target Input target {@link Site}
	 * @return An {@link AttitudeLawLeg} adapted to the observation.
	 */
	private AttitudeLaw createObservationLaw(Site target) throws PatriusException {

		// Target position expressed in the Earth-fixed frame
		final Vector3D targetPosition = this.getEarth().transform(target.getPoint());

		// Satellite axis pointing the target (sensor boresight) and normal axis.
		// They must be consistent with the sensor definition in the Satellite class.
		final Vector3D lineOfSight = Vector3D.MINUS_K;
		final Vector3D normalAxis = Vector3D.PLUS_I;

		return new TargetGroundPointing(this.getEarth(), targetPosition, lineOfSight, normalAxis);
	}

	/**
	 * This method computes the input {@link Site}'s access {@link Timeline}.
	 * That is to say the {@link Timeline} which contains all the {@link Phenomenon}
	 * respecting the access conditions for this site : good visibility + corrrect
	 * illumination of the {@link Site}.
	 * 
	 * @param targetSite Input target {@link Site}
	 * @return The {@link Timeline} of all the access {@link Phenomenon} for the
	 *         input {@link Site}.
	 * @throws PatriusException If a {@link PatriusException} occurs.
	 */
	private Timeline createSiteAccessTimeline(Site targetSite, CodedEventsLogger visibilityLogger,
			CodedEventsLogger sunIncidenceDetector, CodedEventsLogger dazzlingDetector) throws PatriusException {

		final AbsoluteDateInterval timelineInterval =  new AbsoluteDateInterval(this.getStartDate(), this.getEndDate());
		
		// Creation of one timeline for every monitored event
		final Timeline visibilityTimeline = new Timeline(visibilityLogger, timelineInterval, null);
		final Timeline sunIncidenceTimeline = new Timeline(sunIncidenceDetector, timelineInterval, null);
		final Timeline dazzlingTimeline = new Timeline(dazzlingDetector, timelineInterval, null);

		
		// Creating a global Timeline containing all phenomena, this Timeline will be
		// filtered and processed to that only the access Phenomennon remain, this is
		// our siteAccessTimeline.
		final Timeline siteAccessTimeline = new Timeline(timelineInterval);

		// Adding the phenomena of all the considered timelines
		siteAccessTimeline.merge(visibilityTimeline); // Merge the visibility timeline into the global Timeline
		siteAccessTimeline.merge(sunIncidenceTimeline); // Merge the incidence timeline into the global Timeline
		siteAccessTimeline.merge(dazzlingTimeline); // Merge the dazzling timeline into the global Timeline

		// Add the first two criterions when the target is visible and well lit
		final AndCriterion visibilityIncidenceCriterion = new AndCriterion(
			"VISIBILITY-WINDOW",
			"INCIDENCE-WINDOW",
			"VISIBILITY-INCIENCE-WINDOW",
			"The target is visible and well lit"
		);
		visibilityIncidenceCriterion.applyTo(siteAccessTimeline);

		// Add the inverse of the last criterion when the satellite isn't dazzled
		final NotCriterion nonDazzlingCriterion = new NotCriterion(
			"DAZZLE-WINDOW",
			"NO_DAZZLE-WINDOW",
			"The satellite isn't dazzled"
		);
		nonDazzlingCriterion.applyTo(siteAccessTimeline);

		// Applying our criterion adds all the new phenonmena inside the global timeline
		final AndCriterion accessibilityCriterion = new AndCriterion(
			"VISIBILITY-INCIENCE-WINDOW",
			"NO_DAZZLE-WINDOW",
			"ACCESSIBILITY-WINDOW",
			"The target is accessible"
		);
		accessibilityCriterion.applyTo(siteAccessTimeline);		

		// Then create an ElementTypeFilter that will filter all phenomenon not
		// respecting the input condition you gave it
		final ElementTypeFilter obsConditionFilter = new ElementTypeFilter("ACCESSIBILITY-WINDOW", false);
		
		// Finally, we filter the global timeline to keep only X1 AND X2 phenomena
		obsConditionFilter.applyTo(siteAccessTimeline);

		// Log the final access timeline associated to the current target
		logger.info("\n" + targetSite.getName());

		return siteAccessTimeline;
	}

	/**
	 * Creates logger for the visibility constraints.
	 *
	 * @param targetSite Input target {@link Site}
	 * @return The {@link CodedEventsLogger} used to monitor all events linked with
	 *         the chosen constraint
	 */
	private CodedEventsLogger createVisibilityConstraintLogger(Site targetSite) {

		// Define event type's code
		final String typeCode = "VISIBILITY"; 

		// Initialise the appropriate constraint detector
		final EventDetector constraintXDetector = createVisibilityConstraintDetector(targetSite);

		// Add the detector to the propagator (We're adding one for each site)		
		this.getSatellite().getPropagator().addEventDetector(constraintXDetector);

		// Create CodedEvent objects to actually detect the events and visualize them
		final GenericCodingEventDetector codingEventXDetector = new GenericCodingEventDetector(
			constraintXDetector,
			typeCode + "-START",
			typeCode + "-END",
			true,
			typeCode + "-WINDOW"
		);
		final CodedEventsLogger eventXLogger = new CodedEventsLogger();
		final EventDetector eventXDetector = eventXLogger.monitorDetector(codingEventXDetector);
		
		// Add the logger to the propagator, it will monitor the event coded by the codingEventDetector
		this.getSatellite().getPropagator().addEventDetector(eventXDetector);

		return eventXLogger;
	}

	/**
	 * Creates logger for the sun incidence angle constraints.
	 *
	 * @param targetSite Input target {@link Site}
	 * @return The {@link CodedEventsLogger} used to monitor all events linked with
	 *         the chosen constraint
	 */
	private CodedEventsLogger createIncidenceConstraintLogger(Site targetSite) {

		// Define event type's code
		final String typeCode = "INCIDENCE";

		// Create the solar illumination detector
		final EventDetector constraintXDetector = createIncidenceConstraintDetector(targetSite);

		// Add the detector to the propagator (We're adding one for each site)		
		this.getSatellite().getPropagator().addEventDetector(constraintXDetector);

		// Create CodedEvent objects to actually detect the events and visualize them
		final GenericCodingEventDetector codingEventXDetector =
				new GenericCodingEventDetector(
						constraintXDetector,
						typeCode + "-START",
						typeCode + "-END",
						true,
						typeCode + "-WINDOW"
				);
		final CodedEventsLogger eventXLogger = new CodedEventsLogger();
		final EventDetector eventXDetector = eventXLogger.monitorDetector(codingEventXDetector);

		// Add the logger to the propagator, it will monitor the event coded by the codingEventDetector
		this.getSatellite().getPropagator().addEventDetector(eventXDetector);

		return eventXLogger;
	}

	/**
	 * Creates logger for the dazzling angle constraints.
	 * The time windows returned correspond to the sensor being dazzled!!
	 *
	 * @param targetSite Input target {@link Site}
	 * @return The {@link CodedEventsLogger} used to monitor all events linked with
	 *         the chosen constraint
	 */
	private CodedEventsLogger createDazzlingConstraintLogger(Site targetSite) {

		// Define event type's code
		final String typeCode = "DAZZLE"; 

		// Initialise the appropriate constraint detector
		final EventDetector constraintXDetector = createDazzlingConstraintDetector(targetSite);

		// Add the detector to the propagator (We're adding one for each site)		
		this.getSatellite().getPropagator().addEventDetector(constraintXDetector);

		// Create CodedEvent objects to actually detect the events and visualize them
		final GenericCodingEventDetector codingEventXDetector = new GenericCodingEventDetector(
			constraintXDetector,
			typeCode + "-START",
			typeCode + "-END",
			true,
			typeCode + "-WINDOW"
		);
		final CodedEventsLogger eventXLogger = new CodedEventsLogger();
		final EventDetector eventXDetector = eventXLogger.monitorDetector(codingEventXDetector);
		
		// Add the logger to the propagator, it will monitor the event coded by the codingEventDetector
		this.getSatellite().getPropagator().addEventDetector(eventXDetector);

		return eventXLogger;
	}

	/**
	 * @return the accessPlan
	 */
	public Map<Site, Timeline> getAccessPlan() {
		return this.accessPlan;
	}

	/**
	 * @return the cinematicPlan
	 */
	public StrictAttitudeLegsSequence<AttitudeLeg> getCinematicPlan() {
		return this.cinematicPlan;
	}

	/**
	 * @return the observationPlan
	 */
	public Map<Site, AttitudeLawLeg> getObservationPlan() {
		return this.observationPlan;
	}

	@Override
	public String toString() {
		return "CompleteMission [name=" + this.getName() + ", startDate=" + this.getStartDate() + ", endDate="
				+ this.getEndDate() + ", satellite=" + this.getSatellite() + "]";
	}
}
