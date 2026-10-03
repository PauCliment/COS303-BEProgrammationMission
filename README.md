# Making sense of this codebase

## General structure of a run

To run a simulation, one is to execute the `src/test/java/simulation/CompleteMissionMain.java`. This will instantiate a `CompleteMission` object with the mission data and will set off the calculation of the satellite's mission by different steps, each one of which calls a different method of said object. The sequence of calculations goes as:

```mermaid
flowchart TD
    A["Initialize CompleteMission"] -->|"computeAccessPlan()"| B["Access Plan"]
    B -->|"computeObservationPlan()"| C["Observation Plan"]
    C -->|"computeCinematicPlan()"| D["Cinematic Plan"]
    D -->|"checkCinematicPlan()"| E{"Cinematic plan valid?"}
    E -->|"Yes"| F["Compute final score"]
    E -->|"No"| G["Invalid plan"]
    F -->|"generateVTSVisualization()"| H["VTS Visualization"]
```

The `CompleteMission` class follows the UML:

```mermaid
classDiagram

    SimpleMission <|-- CompleteMission

    CompleteMission "1" *-- "0..*" Site : accessPlan
    CompleteMission "1" *-- "0..*" Timeline : accessPlan values

    CompleteMission "1" *-- "0..*" AttitudeLawLeg : observationPlan values
    CompleteMission "1" *-- "1" StrictAttitudeLegsSequence : cinematicPlan

    CompleteMission ..> CodedEventsLogger : creates
    CompleteMission ..> EventDetector : creates
    CompleteMission ..> AttitudeLaw : creates
    CompleteMission ..> ConstantSpinSlew : uses
    CompleteMission ..> Phenomenon : processes
    CompleteMission ..> AbsoluteDateInterval : uses
    CompleteMission ..> KeplerianPropagator : uses

    class SimpleMission {
        <<parent class>>

        +getSiteList()
        +getSatellite()
        +getEarth()
        +getSun()
        +getStartDate()
        +getEndDate()
        +getEme2000()
        +createDefaultPropagator()
        +checkCinematicPlan()
        +computeFinalScore()
        +generateVTSVisualization()
    }

    class CompleteMission {
        <<mission implementation>>

        +MAXCHECK_EVENTS : double
        +TRESHOLD_EVENTS : double

        -HASH_CONSTANT_BE : int
        -accessPlan : Map~Site, Timeline~
        -observationPlan : Map~Site, AttitudeLawLeg~
        -cinematicPlan : StrictAttitudeLegsSequence~AttitudeLeg~

        +CompleteMission(missionName, numberOfSites)

        +computeAccessPlan() Map~Site, Timeline~
        +computeObservationPlan() Map~Site, AttitudeLawLeg~
        +computeCinematicPlan() StrictAttitudeLegsSequence~AttitudeLeg~

        -createConstraintXDetector() EventDetector
        -createObservationLaw(target) AttitudeLaw
        -createSiteAccessTimeline(targetSite, visibilityLogger, sunLogger, dazzlingLogger) Timeline
        -createSiteXConstraintLogger(targetSite) CodedEventsLogger

        +getAccessPlan() Map~Site, Timeline~
        +getObservationPlan() Map~Site, AttitudeLawLeg~
        +getCinematicPlan() StrictAttitudeLegsSequence~AttitudeLeg~
        +toString() String
    }

    class Site {
        <<target>>
        +getName()
        +getPoint()
    }

    class Timeline {
        <<Patrius>>
        +getPhenomenaList()
        +addPhenomenon()
    }

    class Phenomenon {
        <<Patrius>>
        +getTimespan()
    }

    class AttitudeLaw {
        <<Patrius>>
        +getAttitude()
    }

    class AttitudeLawLeg {
        <<Patrius>>
        +getDate()
        +getEnd()
        +getAttitude()
    }

    class AttitudeLeg {
        <<Patrius>>
    }

    class StrictAttitudeLegsSequence {
        <<Patrius>>
        +add()
        +toPrettyString()
    }

    class EventDetector {
        <<Patrius>>
    }

    class CodedEventsLogger {
        <<Patrius>>
        +monitorDetector()
    }

    class ConstantSpinSlew {
        <<Patrius>>
    }

    class KeplerianPropagator {
        <<Patrius>>
        +propagate()
        +addEventDetector()
    }

    AttitudeLawLeg --|> AttitudeLeg
    ConstantSpinSlew --|> AttitudeLeg
    StrictAttitudeLegsSequence o-- AttitudeLeg : contains

    Timeline o-- Phenomenon : contains
```

After