package battlecode.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.google.common.base.Functions;
import com.google.common.base.Predicate;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.GameActionExceptionType;
import battlecode.common.GameConstants;
import battlecode.common.MapLocation;
import battlecode.common.Message;
import battlecode.common.RobotLevel;
import battlecode.common.Team;
import battlecode.common.TerrainTile;
import battlecode.engine.ErrorReporter;
import battlecode.engine.GenericWorld;
import battlecode.engine.PlayerFactory;
import battlecode.engine.instrumenter.RobotMonitor;
import battlecode.engine.signal.*;
import battlecode.serial.DominationFactor;
import battlecode.serial.GameStats;
import battlecode.serial.RoundStats;
import battlecode.server.Config;
import battlecode.world.signal.*;

/**
 * The primary implementation of the GameWorld interface for
 * containing and modifying the game map and the objects on it.
 */
/*
TODO:
- comments
- move methods from RCimpl to here, add signalhandler methods
 */
public class GameWorld extends BaseWorld<InternalObject> implements GenericWorld {

    private static final int NUM_ARCHONS_PER_TEAM = 6; // also need to change value in Match.java
    private final GameMap gameMap;
    private RoundStats roundStats = null;	// stats for each round; new object is created for each round
    private final GameStats gameStats = new GameStats();		// end-of-game stats
    private double[] teamPoints;
    private final Map<MapLocation3D, InternalObject> gameObjectsByLoc;
	//private final Multimap<MapLocation, InternalComponent> looseComponents;
    private final Set<MapLocation>[] teleportersByTeam;
    
    private boolean[][] minedLocs;

    @SuppressWarnings("unchecked")
    public GameWorld(GameMap gm, String teamA, String teamB, long[][] oldArchonMemory) {
		super(gm.getSeed(),teamA,teamB,oldArchonMemory);
        gameMap = gm;
        gameObjectsByLoc = new HashMap<MapLocation3D, InternalObject>();
		//looseComponents = HashMultimap.create();
        teleportersByTeam = new Set[]{
                    new HashSet<MapLocation>(),
                    new HashSet<MapLocation>()};
        minedLocs = new boolean[gm.getHeight()][gm.getWidth()];
        teamPoints = new double[2];
        //testScoreCounter();
    }

	public int getMapSeed() {
		return gameMap.getSeed();
	}

    public GameMap getGameMap() {
        return gameMap;
    }

    public void processBeginningOfRound() {
        currentRound++;

        wasBreakpointHit = false;

        // process all gameobjects
        InternalObject[] gameObjects = new InternalObject[gameObjectsByID.size()];
        gameObjects = gameObjectsByID.values().toArray(gameObjects);
        for (int i = 0; i < gameObjects.length; i++) {
            gameObjects[i].processBeginningOfRound();
        }

	}

    public void processEndOfRound() {
        // process all gameobjects
        InternalObject[] gameObjects = new InternalObject[gameObjectsByID.size()];
        gameObjects = gameObjectsByID.values().toArray(gameObjects);
        for (int i = 0; i < gameObjects.length; i++) {
            gameObjects[i].processEndOfRound();
        }

        // calculate some stats
        double[] totalEnergon = new double[3];
        int[] numArchons = new int[2];
        double[][] archonProduction = new double[2][NUM_ARCHONS_PER_TEAM];
        //~ int[] numRobots = new int[2];
        boolean teamADead = true, teamBDead = true;
        for (InternalObject obj : gameObjectsByID.values()) {
            if (!(obj instanceof InternalRobot))
                continue;
            InternalRobot r = (InternalRobot) obj;
            int team = r.getTeam().ordinal();
            totalEnergon[team] += r.getEnergonLevel();
            //~ numRobots[team]++;
            if (r instanceof InternalRobot) {
                // Buildings can survive for a really long time, so the game
                // should end if one team only has buildings left.  It seems
                // more natural to end the game when all archons are killed
                // then when all non-buildings are killed.
                if (teamADead && r.getTeam() == Team.A) teamADead = false;
                if (teamBDead && r.getTeam() == Team.B) teamBDead = false;
            }
        }
        //~ stats.setActiveTotal(numRobots[0], Team.A);
        //~ stats.setActiveTotal(numRobots[1], Team.B);
        //~ stats.setEnergon(totalEnergon[0], Team.A);
        //~ stats.setEnergon(totalEnergon[1], Team.B);
        //~ stats.setNumArchons(numArchons[0], Team.A);
        //~ stats.setNumArchons(numArchons[1], Team.B);
        //~ stats.setArchonProduction(archonProduction[0], Team.A);
        //~ stats.setArchonProduction(archonProduction[1], Team.B);

        teamPoints[Team.A.ordinal()] += getRoundPoints(Team.A);
        teamPoints[Team.B.ordinal()] += getRoundPoints(Team.B);
        int aPoints = (int) (teamPoints[Team.A.ordinal()]), bPoints = (int) (teamPoints[Team.B.ordinal()]);

        roundStats = new RoundStats(archonProduction[0], archonProduction[1], aPoints, bPoints);

        // check for mercy rule
        //boolean teamAHasMinPoints = teamPoints[Team.A.ordinal()] >= gameMap.getMinPoints() || gameMap.getMaxRounds() < currentRound;
        //boolean teamBHasMinPoints = teamPoints[Team.B.ordinal()] >= gameMap.getMinPoints() || gameMap.getMaxRounds() < currentRound;
        //boolean teamAMercy = teamAHasMinPoints  &&
        //        ((teamPoints[Team.A.ordinal()] - teamPoints[Team.B.ordinal()]) >= gameMap.getMinPoints() * (1 - GameConstants.PointsDecreaseFactor * (currentRound - gameMap.getMaxRounds() + 1)));
        //boolean teamBMercy = teamBHasMinPoints && ((teamPoints[Team.B.ordinal()] - teamPoints[Team.A.ordinal()]) >= gameMap.getMinPoints() * (1 - GameConstants.PointsDecreaseFactor * (currentRound - gameMap.getMaxRounds() + 1)));


        double diff = teamPoints[Team.A.ordinal()] - teamPoints[Team.B.ordinal()];
        boolean teamAMercy = diff > gameMap.getMinPoints() || diff >= gameMap.getMinPoints() * (1 - GameConstants.POINTS_DECREASE_PER_ROUND_FACTOR * (currentRound - gameMap.getStraightMaxRounds() + 1));
        diff -= 2 * diff;
        boolean teamBMercy = diff > gameMap.getMinPoints() || diff >= gameMap.getMinPoints() * (1 - GameConstants.POINTS_DECREASE_PER_ROUND_FACTOR * (currentRound - gameMap.getStraightMaxRounds() + 1));

        // determine if the game is over, and if so, who the winner is
        // the game ends when either team has no living archons, when the round limit is up
        // or when one team has exceeded the map's MIN_POINTS and has a lead of at least MIN_POINTS / 2
        // the algorithm to determine the winner is:
        // (1) team that killed the other team's archons
        // (3) team that has the most points (mined the most flux)
        // (4) team with the greatest energon production among living archons
        // (5) team with the most total energon at the end of the game
        // (6) team with the "smaller" team string
        // (7) Team A wins
        if (teamADead || teamBDead || teamAMercy || teamBMercy) {// || currentRound >= gameMap.getMaxRounds() - 1) {

            running = false;

            for (InternalObject o : gameObjectsByID.values()) {
                if (o instanceof InternalRobot)
                    RobotMonitor.killRobot(o.getID());
            }

            //System.out.println("Game ended");

            gameStats.setPoints(Team.A, aPoints);
            gameStats.setPoints(Team.B, bPoints);
            gameStats.setNumArchons(Team.A, numArchons[0]);
            gameStats.setNumArchons(Team.B, numArchons[1]);
            gameStats.setTotalEnergon(Team.A, totalEnergon[0]);
            gameStats.setTotalEnergon(Team.B, totalEnergon[1]);
            if (!teamADead && teamBDead) {
                winner = Team.A;
                if (numArchons[0] >= NUM_ARCHONS_PER_TEAM)
                    gameStats.setDominationFactor(DominationFactor.DESTROYED);
                else
                    gameStats.setDominationFactor(DominationFactor.PWNED);
            } else if (!teamBDead && teamADead) {
                winner = Team.B;
                if (numArchons[1] >= NUM_ARCHONS_PER_TEAM)
                    gameStats.setDominationFactor(DominationFactor.DESTROYED);
                else
                    gameStats.setDominationFactor(DominationFactor.PWNED);
            } else if (aPoints != bPoints) {
                if (teamAMercy) {
                    gameStats.setDominationFactor(DominationFactor.OWNED);
                    winner = Team.A;
                } else if (teamBMercy) {
                    gameStats.setDominationFactor(DominationFactor.OWNED);
                    winner = Team.B;
                } else {
                    gameStats.setDominationFactor(DominationFactor.BEAT);
                    if (aPoints > bPoints)
                        winner = Team.A;
                    else
                        winner = Team.B;
                }
            } else {
                if (numArchons[0] > numArchons[1]) {
                    winner = Team.A;
                    gameStats.setDominationFactor(DominationFactor.BARELY_BEAT);
                } else if (numArchons[0] < numArchons[1]) {
                    winner = Team.B;
                    gameStats.setDominationFactor(DominationFactor.BARELY_BEAT);
                } else {
                    gameStats.setDominationFactor(DominationFactor.WON_BY_DUBIOUS_REASONS);
                    if (totalEnergon[0] > totalEnergon[1])
                        winner = Team.A;
                    else if (totalEnergon[1] > totalEnergon[0])
                        winner = Team.B;
                    else {
                        if (teamAName.compareTo(teamBName) <= 0)
                            winner = Team.A;
                        else
                            winner = Team.B;
                    }
                }
            }
        }

        // TESTING
        //~ if(winner != null) {
        //~ System.out.println("DF: " + gameStats.getDominationFactor());
        //~ System.out.println("XF: " + gameStats.getExcitementFactor());
        //~ int[] firstKill = gameStats.getTimeToFirstKill();
        //~ System.out.println("1st kill: " + firstKill[0] + ", " + firstKill[1]);
        //~ int[] firstArchonKill = gameStats.getTimeToFirstArchonKill();
        //~ System.out.println("1st archon kill: " + firstArchonKill[0] + ", " + firstArchonKill[1]);
        //~ }
    }

    public InternalObject getObject(MapLocation loc, RobotLevel level) {
        return gameObjectsByLoc.get(new MapLocation3D(loc, level));
    }

	public <T extends InternalObject> T getObjectOfType(MapLocation loc, RobotLevel level, Class <T> cl) {
		InternalObject o = getObject(loc,level);
		if(cl.isInstance(o))
			return cl.cast(o);
		else
			return null;
	}

    public InternalRobot getRobot(MapLocation loc, RobotLevel level) {
        InternalObject obj = getObject(loc, level);
        if (obj instanceof InternalRobot)
            return (InternalRobot) obj;
        else
            return null;
    }

	/**
	 * Returns a set of all loose components at {@code loc}.
	 */
	/*
	public Collection<InternalComponent> getComponentsAt(MapLocation loc) {
		return looseComponents.get(loc);
	}

	public Iterable<InternalComponent> getLooseComponents(Predicate<MapLocation> p) {
		Set<MapLocation> keys = looseComponents.keySet();
		Iterable<Collection<InternalComponent>> c = Iterables.transform(keys,Functions.forMap(looseComponents.asMap()));
		return Iterables.concat(c);
	}
	*/

    // should only be called by the InternalObject constructor
    public void notifyAddingNewObject(InternalObject o) {
        if (gameObjectsByID.containsKey(o.getID()))
            return;
        gameObjectsByID.put(o.getID(), o);
        if (o.getLocation() != null) {
            gameObjectsByLoc.put(new MapLocation3D(o.getLocation(), o.getRobotLevel()), o);
        }
    }

	/*
	public static boolean canStealComponent(InternalRobot thief, InternalRobot victim) {
		return (!victim.isOn())&&thief.getLocation().distanceSquaredTo(victim.getLocation())<=2;
	}
	*/

	public Collection<InternalObject> allObjects() {
		return gameObjectsByID.values();
	}

    public void notifyAddingNewTeleporter(InternalRobot r) {
        teleportersByTeam[r.getTeam().ordinal()].add(r.getLocation());
    }

    // TODO: move stuff to here
    // should only be called by InternalObject.setLocation
    public void notifyMovingObject(InternalObject o, MapLocation oldLoc, MapLocation newLoc) {
        if (oldLoc != null) {
            MapLocation3D oldLoc3D = new MapLocation3D(oldLoc, o.getRobotLevel());
            if (gameObjectsByLoc.get(oldLoc3D) != o) {
                ErrorReporter.report("Internal Error: invalid oldLoc in notifyMovingObject");
                return;
            }
            gameObjectsByLoc.remove(oldLoc3D);
        }
        if (newLoc != null) {
            gameObjectsByLoc.put(new MapLocation3D(newLoc, o.getRobotLevel()), o);
        }
    }

    public void removeObject(InternalObject o) {
        if (o.getLocation() != null) {
            MapLocation3D loc3D = new MapLocation3D(o.getLocation(), o.getRobotLevel());
            if (gameObjectsByLoc.get(loc3D) == o)
                gameObjectsByLoc.remove(loc3D);
            else
                System.out.println("Couldn't remove " + o + " from the game");
        } else
            System.out.println("Couldn't remove " + o + " from the game");

        if (gameObjectsByID.get(o.getID()) == o)
            gameObjectsByID.remove(o.getID());

        if (o instanceof InternalRobot) {
            InternalRobot r = (InternalRobot) o;
            r.freeMemory();
        }
    }

    public boolean exists(InternalObject o) {
        return gameObjectsByID.containsKey(o.getID());
    }

    /**
     *@return the TerrainType at a given MapLocation <tt>loc<tt>
     */
    public TerrainTile getMapTerrain(MapLocation loc) {
        return gameMap.getTerrainTile(loc);
    }

    // TODO: optimize this too
    public int getUnitCount(Team team) {
        int result = 0;
        for (InternalObject o : gameObjectsByID.values()) {
            if (!(o instanceof InternalRobot))
                continue;
            if (((InternalRobot) o).getTeam() == team)
                result++;
        }

        return result;
    }

    public double getPoints(Team team) {
        return teamPoints[team.ordinal()];
    }

    public boolean canMove(InternalRobot r, Direction dir) {
        if (dir == Direction.NONE || dir == Direction.OMNI)
            return false;

        MapLocation loc = r.getLocation().add(dir);

        return canMove(r.getRobotLevel(), loc);
    }

    public boolean canMove(RobotLevel level, MapLocation loc) {

        return gameMap.getTerrainTile(loc).isTraversableAtHeight(level) && (gameObjectsByLoc.get(new MapLocation3D(loc, level)) == null);
    }

    public void splashDamageGround(MapLocation loc, double damage, double falloutFraction) {
        //TODO: optimize this
        InternalRobot[] robots = getAllRobotsWithinRadiusDonutSq(loc, 2, -1);
        for (InternalRobot r : robots) {
            if (r.getRobotLevel() == RobotLevel.ON_GROUND) {
                if (r.getLocation().equals(loc))
                    r.changeEnergonLevelFromAttack(-damage);
                else
                    r.changeEnergonLevelFromAttack(-damage * falloutFraction);
            }
        }
    }

    public InternalObject[] getAllGameObjects() {
        return gameObjectsByID.values().toArray(new InternalObject[gameObjectsByID.size()]);
    }

    public Signal[] getAllSignals(boolean includeBytecodesUsedSignal) {
        ArrayList<InternalRobot> energonChangedRobots = new ArrayList<InternalRobot>();
        ArrayList<InternalRobot> fluxChangedRobots = new ArrayList<InternalRobot>();
        ArrayList<InternalRobot> allRobots = null;
        if (includeBytecodesUsedSignal)
            allRobots = new ArrayList<InternalRobot>();
        for (InternalObject obj : gameObjectsByID.values()) {
            if (!(obj instanceof InternalRobot))
                continue;
            InternalRobot r = (InternalRobot) obj;
            if (includeBytecodesUsedSignal)
                allRobots.add(r);
            if (r.clearEnergonChanged()) {
            	energonChangedRobots.add(r);
            }
        }
        signals.add(new EnergonChangeSignal(energonChangedRobots.toArray(new InternalRobot[]{})));
        if (includeBytecodesUsedSignal)
            signals.add(new BytecodesUsedSignal(allRobots.toArray(new InternalRobot[]{})));
        return signals.toArray(new Signal[signals.size()]);
    }

    public RoundStats getRoundStats() {
        return roundStats;
    }

    public GameStats getGameStats() {
        return gameStats;
    }

    public void beginningOfExecution(int robotID) {
        InternalRobot r = (InternalRobot) getObjectByID(robotID);
        if (r != null)
            r.processBeginningOfTurn();
    }

    public void endOfExecution(int robotID) {
        InternalRobot r = (InternalRobot) getObjectByID(robotID);
        r.setBytecodesUsed(RobotMonitor.getBytecodesUsed());
        r.processEndOfTurn();
    }

	public void resetStatic() {
	}

	public double getRoundPoints(Team t) {
		return 0.;
	}

    // ******************************
    // SIGNAL HANDLER METHODS
    // ******************************

	SignalHandler<Exception> signalHandler = new AutoSignalHandler<Exception>(this) {
		public Exception exceptionResponse(Exception e) {
			return e;
		}
	};

	public Exception visitSignal(Signal s) {
		return signalHandler.visitSignal(s);
	}

    public Exception visitAttackSignal(AttackSignal s) {
        try {
            InternalRobot attacker = (InternalRobot) getObjectByID(s.getRobotID());
            MapLocation targetLoc = s.getTargetLoc();
            RobotLevel level = s.getTargetHeight();
            InternalRobot target = getRobot(targetLoc, level);

            double totalDamage = s.getWeaponType().attackPower;

			if(target!=null) {
				// takeDamage is responsible for checking the armor
				target.takeDamage(totalDamage);
			}

			/* splash, in case we still want it
            if (attacker.getRobotType() == RobotType.CHAINER) {
                InternalRobot[] hits = getAllRobotsWithinRadiusDonutSq(targetLoc, GameConstants.CHAINER_SPLASH_RADIUS_SQUARED, -1);
                for (InternalRobot r : hits) {
                    if (r.getRobotLevel() == level)
                        r.changeEnergonLevelFromAttack(-totalDamage);
                }
            } else if (target != null) {
                target.changeEnergonLevelFromAttack(-totalDamage);
            }
			*/

            addSignal(s);
        } catch (Exception e) {
            return e;
        }
        return null;
    }

	public Exception visitBroadcastSignal(BroadcastSignal s) {
		InternalObject sender = gameObjectsByID.get(s.robotID);
		Collection<InternalObject> objs = gameObjectsByLoc.values();
		Predicate<InternalObject> pred = Util.robotWithinDistance(sender.getLocation(),s.range);
		for(InternalObject o : Iterables.filter(objs,pred)) {
			InternalRobot r = (InternalRobot)o;
			r.enqueueIncomingMessage((Message)s.message.clone());
		}
		s.message = null;
		addSignal(s);
		return null;
	}

    public Exception visitDeathSignal(DeathSignal s) {
        if (!running) {
            // All robots emit death signals after the game
            // ends.  We still want the client to draw
            // the robots.
            return null;
        }
        try {
            int ID = s.getObjectID();
            InternalObject obj = getObjectByID(ID);

            if (obj instanceof InternalRobot) {
                InternalRobot r = (InternalRobot) obj;
                RobotMonitor.killRobot(ID);
                if (r.hasBeenAttacked()) {
                	gameStats.setUnitKilled(r.getTeam(), currentRound);
                }
            }
            if (obj != null) {
                removeObject(obj);
                addSignal(s);
            }
        } catch (Exception e) {
            return e;
        }
        return null;
    }

    public Exception visitEnergonChangeSignal(EnergonChangeSignal s) {
        int[] robotIDs = s.getRobotIDs();
        double[] energon = s.getEnergon();
        for (int i = 0; i < robotIDs.length; i++) {
            try {
                InternalRobot r = (InternalRobot) getObjectByID(robotIDs[i]);
                System.out.println("el " + energon[i] + " " + r.getEnergonLevel());
                r.changeEnergonLevel(energon[i] - r.getEnergonLevel());
            } catch (Exception e) {
                return e;
            }
        }
        return null;
    }

    public Exception visitIndicatorStringSignal(IndicatorStringSignal s) {
        try {
            addSignal(s);
        } catch (Exception e) {
            return e;
        }
        return null;
    }

    public Exception visitMatchObservationSignal(MatchObservationSignal s) {
        addSignal(s);
        return null;
    }

    public Exception visitControlBitsSignal(ControlBitsSignal s) {
        try {
            InternalRobot r = (InternalRobot) getObjectByID(s.getRobotID());
            r.setControlBits(s.getControlBits());

            addSignal(s);
        } catch (Exception e) {
            return e;
        }
        return null;
    }

    public Exception visitMovementOverrideSignal(MovementOverrideSignal s) {
        try {
            InternalRobot r = (InternalRobot) getObjectByID(s.getRobotID());
            if (!canMove(r.getRobotLevel(), s.getNewLoc()))
                return new GameActionException(GameActionExceptionType.CANT_MOVE_THERE, "Cannot move to location: " + s.getNewLoc());
            r.setLocation(s.getNewLoc());
        } catch (Exception e) {
            return e;
        }
        addSignal(s);
        return null;
    }

    public Exception visitMovementSignal(MovementSignal s) {
        try {
            InternalRobot r = (InternalRobot) getObjectByID(s.getRobotID());
            MapLocation loc = (s.isMovingForward() ? r.getLocation().add(r.getDirection()) : r.getLocation().add(r.getDirection().opposite()));

            r.setLocation(loc);

            addSignal(s);
        } catch (Exception e) {
            return e;
        }
        return null;
    }

    public Exception visitSetDirectionSignal(SetDirectionSignal s) {
        try {
            InternalRobot r = (InternalRobot) getObjectByID(s.getRobotID());
            Direction dir = s.getDirection();

            r.setDirection(dir);

            addSignal(s);
        } catch (Exception e) {
            return e;
        }
        return null;
    }

    public Exception visitSpawnSignal(SpawnSignal s) {
        try {
            InternalRobot parent;
            int parentID = s.getParentID();
            MapLocation loc;
            if (parentID == 0) {
                parent = null;
                loc = s.getLoc();
                if (loc == null) {
                    ErrorReporter.report("Null parent and loc in visitSpawnSignal", true);
                    return new Exception();
                }
            } else {
                parent = (InternalRobot) getObjectByID(parentID);
                loc = parent.getLocation().add(parent.getDirection());
            }

            //note: this also adds the signal
            GameWorldFactory.createPlayer(this, s.getType(), loc, s.getTeam(), parent);

        } catch (Exception e) {
            return e;
        }
        return null;
    }

    public Exception visitMapOriginSignal(MapOriginSignal s) {
        addSignal(s);
        return null;
    }
    // *****************************
    //    UTILITY METHODS
    // *****************************
    private static MapLocation origin = new MapLocation(0, 0);

    protected static boolean inAngleRange(MapLocation sensor, Direction dir, MapLocation target, double cosHalfTheta) {
        MapLocation dirVec = origin.add(dir);
        double dx = target.getX() - sensor.getX();
        double dy = target.getY() - sensor.getY();
        int a = dirVec.getX();
        int b = dirVec.getY();
        double dotProduct = a * dx + b * dy;

        if (dotProduct < 0) {
            if (cosHalfTheta > 0)
                return false;
        } else if (cosHalfTheta < 0)
            return true;

        double rhs = cosHalfTheta * cosHalfTheta * (dx * dx + dy * dy) * (a * a + b * b);

        if (dotProduct < 0)
            return (dotProduct * dotProduct <= rhs + 0.00001d);
        else
            return (dotProduct * dotProduct >= rhs - 0.00001d);
    }

    // TODO: make a faster implementation of this
    private InternalRobot[] getAllRobotsWithinRadiusDonutSq(MapLocation center, int outerRadiusSquared, int innerRadiusSquared) {
        ArrayList<InternalRobot> robots = new ArrayList<InternalRobot>();

        for (InternalObject o : gameObjectsByID.values()) {
            if (!(o instanceof InternalRobot))
                continue;
            if (o.getLocation() != null && o.getLocation().distanceSquaredTo(center) <= outerRadiusSquared
                    && o.getLocation().distanceSquaredTo(center) > innerRadiusSquared)
                robots.add((InternalRobot) o);
        }

        return robots.toArray(new InternalRobot[robots.size()]);
    }

    // TODO: make a faster implementation of this
    public MapLocation[] getAllMapLocationsWithinRadiusSq(MapLocation center, int radiusSquared) {
        ArrayList<MapLocation> locations = new ArrayList<MapLocation>();

        int radius = (int) Math.sqrt(radiusSquared);

        int minXPos = center.getX() - radius;
        int maxXPos = center.getX() + radius;
        int minYPos = center.getY() - radius;
        int maxYPos = center.getY() + radius;

        for (int x = minXPos; x <= maxXPos; x++) {
            for (int y = minYPos; y <= maxYPos; y++) {
                MapLocation loc = new MapLocation(x, y);
                TerrainTile tile = gameMap.getTerrainTile(loc);
                if (!tile.equals(TerrainTile.OFF_MAP) && loc.distanceSquaredTo(center) < radiusSquared)
                    locations.add(loc);
            }
        }

        return locations.toArray(new MapLocation[locations.size()]);
    }

    protected void adjustTeamPoints(InternalRobot r, int points) {
        teamPoints[r.getTeam().ordinal()] += points;
    }
}
