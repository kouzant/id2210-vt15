/*
 * Copyright (C) 2009 Swedish Institute of Computer Science (SICS) Copyright (C)
 * 2009 Royal Institute of Technology (KTH)
 *
 * GVoD is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package se.kth.swim;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kth.swim.msg.Status;
import se.kth.swim.msg.net.NetStatus;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.Handler;
import se.sics.kompics.Init;
import se.sics.kompics.Positive;
import se.sics.kompics.Start;
import se.sics.kompics.Stop;
import se.sics.kompics.network.Network;
import se.sics.kompics.timer.CancelPeriodicTimeout;
import se.sics.kompics.timer.SchedulePeriodicTimeout;
import se.sics.kompics.timer.Timeout;
import se.sics.kompics.timer.Timer;
import se.sics.p2ptoolbox.util.network.NatedAddress;

/**
 * @author Alex Ormenisan <aaor@sics.se>
 */
public class AggregatorComp extends ComponentDefinition {

  private static final Logger log = LoggerFactory
    .getLogger(AggregatorComp.class);
  private Positive<Network> network = requires(Network.class);
  private Positive<Timer> timer = requires(Timer.class);

  private final NatedAddress selfAddress;

  // Timestamp from which start to calculate the convergence.
  private long start;
  // Control variables.
  private boolean printConvergence, setStartTime;
  // The size of the overlay.
  private final Integer size;
  // This Map keep traks of the status of all the peers, except the dead ones.
  private final Map<Integer, Status> snapshot;
  // IDs of the nodes which are going to be killed.
  private final Integer[] nodeToKill;
  // Used to get the trsansition between 0 dead nodes and 1 so to start 
  // counting the time for the convergence.
  private Integer previousDeadNode;
  private UUID timerID;
  // Measure the time according to the time of Kompics
  private long ownTimer;

  public AggregatorComp(AggregatorInit init) {
    this.selfAddress = init.selfAddress;
    this.nodeToKill = init.getKilled();
    this.size = init.getSize();
    this.printConvergence = true;
    this.previousDeadNode = 0;
    this.setStartTime = true;
    this.ownTimer = 0;

    // Init the timestamp for when the nodes will be killed
    // Our assumption is that all the node will be killed at the same time
    log.info("{} initiating...", new Object[]{selfAddress.getId()});

    snapshot = new HashMap<>();

    subscribe(handleStart, control);
    subscribe(handleStop, control);
    subscribe(handleStatus, network);
    subscribe(handleTimer, timer);
    System.out.println("Total size: " + size);
	//System.out.println("nodetokill: " + nodeToKill.length);
  }

		
  private Handler<Start> handleStart = new Handler<Start>() {

    @Override
    public void handle(Start event) {
      log.info("{} starting...", new Object[]{selfAddress});
      timerID = scheduleTimer();
    }

  };
  private Handler<Stop> handleStop = new Handler<Stop>() {

    @Override
    public void handle(Stop event) {
      log.info("{} stopping...", new Object[]{selfAddress});
      if (timerID != null) {
        cancelTimer(timerID);
      }
    }

  };

  /**
   * Increments the Kompics time of the current node.
   */
  private Handler<AggregatorTimer> handleTimer = new Handler<AggregatorTimer>() {
    @Override
    public void handle(AggregatorTimer event) {
      ownTimer++;
    }
  };

  private Handler<NetStatus> handleStatus = new Handler<NetStatus>() {

    @Override
    public void handle(NetStatus status) {
      if (printConvergence) {
        log.debug(
          "{} status from:{} pings:{}, alive:{}, dead:{}, suspected:{}",
          new Object[]{status.getSource().getId(),
            status.getHeader().getSource().getId(),
            status.getContent().getReceivedPings(),
            status.getContent().getAliveNodes(),
            status.getContent().getDeadNodes(),
            status.getContent().getSuspectedNodes()});
      }

      // Condition to start counting the convergence when there are dead nodes.
      if (previousDeadNode.equals(0)
        && status.getContent().getDeadNodes() > 1
        && nodeToKill.length > 0) {

        start = ownTimer;
        previousDeadNode++;
      } 
      // Condition to start counting the convergence when there are no dead nodes.
      else if (setStartTime) {
        start = ownTimer;
        setStartTime = false;
      }

      // Conditions to add/update/remove elements from the snapshot.
      if (snapshot.containsKey(status.getSource().getId())
        && !Arrays.asList(nodeToKill).contains(
          status.getSource().getId())) {
        if (checkCorrectness(status.getContent())) {
          snapshot.replace(status.getSource().getId(),
            status.getContent());
        } else {
          snapshot.remove(status.getSource().getId());
        }
      } else {
        if (checkCorrectness(status.getContent())) {
          snapshot.put(status.getSource().getId(),
            status.getContent());
        }
      }

      if (snapshot.size() == (size - nodeToKill.length)
        && printConvergence) {
        // Print only once
        printConvergence = false;

        log.info("CONVERGENCE in {} ms for {} nodes", ownTimer
          - start, snapshot.size());
      }
    }
  };

  private boolean checkCorrectness(Status status) {
    if (status.getAliveNodes().equals(size - nodeToKill.length)
      && status.getDeadNodes().equals(nodeToKill.length)) {
      return true;
    }

    return false;
  }

  private UUID scheduleTimer() {
    SchedulePeriodicTimeout spt = new SchedulePeriodicTimeout(0, 100);
    AggregatorTimer at = new AggregatorTimer(spt);
    spt.setTimeoutEvent(at);
    timerID = at.getTimeoutId();
    trigger(spt, timer);

    return timerID;
  }

  private void cancelTimer(UUID timerID) {
    CancelPeriodicTimeout cpt = new CancelPeriodicTimeout(timerID);
    trigger(cpt, timer);
    timerID = null;
  }

  public static class AggregatorInit extends Init<AggregatorComp> {

    private final NatedAddress selfAddress;
    private final Integer[] killed;
    private final Integer size;
    private final long killAfter;

    public AggregatorInit(NatedAddress selfAddress, Integer size,
      Integer[] killed, long killAfter) {
      this.selfAddress = selfAddress;
      this.killed = killed;
      this.killAfter = killAfter;
      this.size = size;
    }

    public NatedAddress getSelfAddress() {
      return selfAddress;
    }

    public Integer[] getKilled() {
      return killed;
    }

    public long getKillAfter() {
      return killAfter;
    }

    public Integer getSize() {
      return size;
    }
  }

  private static class AggregatorTimer extends Timeout {

    public AggregatorTimer(SchedulePeriodicTimeout request) {
      super(request);
    }
  }
}
